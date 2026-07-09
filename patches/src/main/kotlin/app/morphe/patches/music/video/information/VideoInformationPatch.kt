/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.music.video.information

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.toInstructions
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.videoinformation.PlayerControllerSetTimeReferenceFingerprint
import app.morphe.util.addStaticFieldToExtension
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.lang.ref.WeakReference

internal const val EXTENSION_CLASS = "Lapp/morphe/extension/music/shared/VideoInformation;"

// Register layout inside the synthetic setVideoInformation(playerResponseModel) method.
private const val REG_PLAYER_RESPONSE = 4
private const val REG_VIDEO_ID = 0
private const val REG_VIDEO_LENGTH = 1
// Second half of the wide video-length value (v1:v2).
private const val REG_VIDEO_LENGTH_DUMMY = 2

private lateinit var playerResponseModelClass: String
private lateinit var videoIdCall: String
private lateinit var videoLengthCall: String

internal lateinit var videoInformationMethodRef: WeakReference<MutableMethod>

private lateinit var playerConstructorMethodRef: WeakReference<MutableMethod>
private var playerConstructorIndex = -1

private lateinit var videoTimeMethodRef: WeakReference<MutableMethod>
private var videoTimeInsertIndex = 0
// p-registers of the current-position long parameter inside videoTimeMethod, e.g. "p2, p3".
private lateinit var videoTimeRegisters: String

private lateinit var seekSourceEnumType: String
private lateinit var seekSourceMethodName: String

@Suppress("unused")
val musicVideoInformationPatch = bytecodePatch(
    description = "Hooks video ID, video time, and seekTo for YouTube Music."
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val playerClass = VideoEndFingerprint.classDef
        val playerType = playerClass.type

        seekSourceEnumType = VideoEndFingerprint.method.parameterTypes[1].toString()
        seekSourceMethodName = VideoEndFingerprint.method.name

        // Add seekTo(J)Z to the player class so the extension can call it.
        playerClass.methods.add(
            ImmutableMethod(
                playerType, "seekTo",
                listOf(ImmutableMethodParameter("J", emptySet(), "time")),
                "Z",
                AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
                emptySet(), null,
                ImmutableMethodImplementation(
                    4,
                    """
                        sget-object v0, $seekSourceEnumType->a:$seekSourceEnumType
                        invoke-virtual { p0, p1, p2, v0 }, $playerType->$seekSourceMethodName(J$seekSourceEnumType)Z
                        move-result p1
                        return p1
                    """.toInstructions(),
                    null, null,
                ),
            ).toMutable()
        )

        // Wire static field + overrideVideoTime() in extension via addStaticFieldToExtension.
        addStaticFieldToExtension(
            EXTENSION_CLASS,
            "overrideVideoTime",
            "videoInformationClass",
            playerType,
            """
                if-eqz v0, :ignore
                invoke-virtual { v0, p0, p1 }, $playerType->seekTo(J)Z
                move-result p0
                return p0
                :ignore
                const/4 v0, 0x0
                return v0
            """
        )

        val playerConstructorMethod = playerClass.methods.first { it.name == "<init>" }
        playerConstructorMethodRef = WeakReference(playerConstructorMethod)
        playerConstructorIndex = playerConstructorMethod.indexOfFirstInstructionOrThrow {
            opcode == Opcode.INVOKE_DIRECT &&
                    getReference<MethodReference>()?.name == "<init>"
        } + 1

        // Store the player instance in the extension's static field each time it's created.
        playerConstructorMethod.addInstruction(
            playerConstructorIndex++,
            "sput-object p0, $EXTENSION_CLASS->videoInformationClass:$playerType",
        )

        // Reset the extension's per-video state (time/length) whenever a new player is constructed.
        onMusicCreateHook(EXTENSION_CLASS, "initialize")

        // Hook broadcastCurrentProgress at its position-long parameter. The progress-object
        // constructor it eventually calls has multiple overloads and the long passed there is not
        // the playback position, so resolve the position from this method's own parameter list.
        val videoTimeMethod = PlayerControllerSetTimeReferenceFingerprint.method
        videoTimeMethodRef = WeakReference(videoTimeMethod)
        run {
            // The method takes (state, sentinel:long=-1, positionMs:long, …). Use the range form
            // since the long pair may sit above v15, beyond a plain invoke-static's reach.
            var pReg = 1 // p0 = this; declared parameters start at p1.
            var jCount = 0
            for (type in videoTimeMethod.parameterTypes) {
                val ts = type.toString()
                if (ts == "J") {
                    jCount++
                    if (jCount == 2) {
                        videoTimeRegisters = "p$pReg .. p${pReg + 1}"
                        break
                    }
                }
                pReg += if (ts == "J" || ts == "D") 2 else 1
            }
        }

        // Feed the current playback position into the extension so getVideoTime()/seekTo() work.
        // Inserted first so it runs before any consumer hook (e.g. SponsorBlock) added later.
        musicVideoTimeHook(EXTENSION_CLASS, "setVideoTime")


        val videoIdClass = VideoIdFingerprint.classDef

        VideoIdFingerprint.method.apply {
            // Find the interface call that retrieves the video ID string from the response model.
            val videoIdInterfaceIdx = indexOfFirstInstructionOrThrow {
                val ref = getReference<MethodReference>()
                (opcode == Opcode.INVOKE_INTERFACE_RANGE || opcode == Opcode.INVOKE_INTERFACE) &&
                        ref?.returnType == "Ljava/lang/String;" &&
                        ref.parameterTypes.isEmpty()
            }

            playerResponseModelClass = (getInstruction<ReferenceInstruction>(videoIdInterfaceIdx)
                .reference as MethodReference).definingClass

            videoIdCall = "invoke-interface { v$REG_PLAYER_RESPONSE }, " +
                    getInstruction<ReferenceInstruction>(videoIdInterfaceIdx).reference

            // Find the video length method directly from the interface class definition.
            val videoLengthMethod = classDefBy(playerResponseModelClass).methods
                .first { it.returnType == "J" && it.parameterTypes.isEmpty() }
            videoLengthCall = "invoke-interface {v$REG_PLAYER_RESPONSE}, " +
                    "$playerResponseModelClass->${videoLengthMethod.name}()J"

            // Inject a private helper method into this class that extracts video ID + length
            // from the player response model and forwards them to the extension.
            val videoInformationMethod = ImmutableMethod(
                definingClass,
                "setVideoInformation",
                listOf(ImmutableMethodParameter(playerResponseModelClass, emptySet(), null)),
                "V",
                AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
                emptySet(), null,
                ImmutableMethodImplementation(
                    REG_PLAYER_RESPONSE + 1,
                    """
                        $videoIdCall
                        move-result-object v$REG_VIDEO_ID
                        invoke-static { v$REG_VIDEO_ID }, $EXTENSION_CLASS->setVideoId(Ljava/lang/String;)V
                        $videoLengthCall
                        move-result-wide v$REG_VIDEO_LENGTH
                        invoke-static { v$REG_VIDEO_LENGTH, v$REG_VIDEO_LENGTH_DUMMY }, $EXTENSION_CLASS->setVideoLength(J)V
                        return-void
                    """.toInstructions(),
                    null, null,
                ),
            ).toMutable()
            videoIdClass.methods.add(videoInformationMethod)
            videoInformationMethodRef = WeakReference(videoInformationMethod)

            addInstruction(
                videoIdInterfaceIdx + 2,
                "invoke-direct/range { p0 .. p1 }, $definingClass->setVideoInformation($playerResponseModelClass)V",
            )
        }
    }
}


/** Hook called on the main thread when the player initializes. */
internal fun onMusicCreateHook(targetClass: String, targetMethod: String) =
    playerConstructorMethodRef.get()!!.addInstruction(
        playerConstructorIndex++,
        "invoke-static { }, $targetClass->$targetMethod()V"
    )

/** Hook called ~every 1000ms with current playback position. */
fun musicVideoTimeHook(targetClass: String, targetMethod: String) =
    videoTimeMethodRef.get()!!.addInstruction(
        videoTimeInsertIndex++,
        "invoke-static/range { $videoTimeRegisters }, $targetClass->$targetMethod(J)V"
    )

/** Hook called with the new video ID when a track changes. */
fun musicVideoIdHook(descriptor: String) =
    videoInformationMethodRef.get()!!.apply {
        addInstruction(
            implementation!!.instructions.size - 1,
            "invoke-static { v$REG_VIDEO_ID }, $descriptor"
        )
    }
