package app.morphe.patches.music.video.information

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patcher.util.smali.toInstructions
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
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

internal const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/shared/VideoInformation;"

// Register layout inside the synthetic setVideoInformation(playerResponseModel) method.
private const val REG_PLAYER_RESPONSE = 4
private const val REG_VIDEO_ID = 0
private const val REG_VIDEO_LENGTH = 1
// Second half of the wide video-length value (v1:v2).
private const val REG_VIDEO_LENGTH_DUMMY = 2

private lateinit var playerResponseModelClass: String
private lateinit var videoIdCall: String
private lateinit var videoLengthCall: String

internal lateinit var videoInformationMethod: MutableMethod

private lateinit var playerConstructorMethod: MutableMethod
private var playerConstructorIndex = -1

private lateinit var videoTimeMethod: MutableMethod
private var videoTimeInsertIndex = 0
// p-registers of the current-position long parameter inside videoTimeMethod, e.g. "p2, p3".
private lateinit var videoTimeRegisters: String

private lateinit var seekSourceEnumType: String
private lateinit var seekSourceMethodName: String

@Suppress("unused")
val musicVideoInformationPatch = bytecodePatch(
    description = "Hooks video ID, video time, and seekTo for YouTube Music.",
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        val playerClass = VideoEndFingerprint.classDef
        val playerType = playerClass.type

        seekSourceEnumType = VideoEndFingerprint.method.parameterTypes[1].toString()
        seekSourceMethodName = VideoEndFingerprint.method.name

        // ── SeekTo bridge ─────────────────────────────────────────────────────

        // Add seekTo(J)Z to the player class so our extension can call it.
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
                        invoke-virtual {p0, p1, p2, v0}, $playerType->$seekSourceMethodName(J$seekSourceEnumType)Z
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
                invoke-virtual {v0, p0, p1}, $playerType->seekTo(J)Z
                move-result p0
                return p0
                :ignore
                const/4 v0, 0x0
                return v0
            """,
        )

        // ── Constructor hook ──────────────────────────────────────────────────

        playerConstructorMethod = playerClass.methods.first { it.name == "<init>" } as MutableMethod
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

        // ── Video time hook ───────────────────────────────────────────────────

        // PlayerControllerSetTimeFingerprint matches broadcastCurrentProgress(state, positionMs, …),
        // which runs ~once per second during playback. Hook it directly at its first `long`
        // parameter (the current position). Do NOT walk to the progress-object constructor it calls:
        // that class has multiple <init> overloads, so picking one by name is unreliable and the
        // long it receives is not the playback position.
        videoTimeMethod = PlayerControllerSetTimeFingerprint.method
        run {
            // broadcastCurrentProgress(state, sentinel, positionMs, …): the first long is a -1
            // sentinel, the 2nd long is the current playback position. Range form is required
            // because these sit in high registers a plain invoke-static (v0-v15) cannot address.
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

        // ── Video ID hook ─────────────────────────────────────────────────────

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

            videoIdCall = "invoke-interface {v$REG_PLAYER_RESPONSE}, " +
                    getInstruction<ReferenceInstruction>(videoIdInterfaceIdx).reference

            // Find the video length method directly from the interface class definition.
            val videoLengthMethod = classDefBy(playerResponseModelClass).methods
                .first { it.returnType == "J" && it.parameterTypes.isEmpty() }
            videoLengthCall = "invoke-interface {v$REG_PLAYER_RESPONSE}, " +
                    "$playerResponseModelClass->${videoLengthMethod.name}()J"

            // Inject a private helper method into this class that extracts video ID + length
            // from the player response model and forwards them to the extension.
            videoInformationMethod = ImmutableMethod(
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
                        invoke-static {v$REG_VIDEO_ID}, $EXTENSION_CLASS->setVideoId(Ljava/lang/String;)V
                        $videoLengthCall
                        move-result-wide v$REG_VIDEO_LENGTH
                        invoke-static {v$REG_VIDEO_LENGTH, v$REG_VIDEO_LENGTH_DUMMY}, $EXTENSION_CLASS->setVideoLength(J)V
                        return-void
                    """.toInstructions(),
                    null, null,
                ),
            ).toMutable()

            videoIdClass.methods.add(videoInformationMethod)

            addInstruction(
                videoIdInterfaceIdx + 2,
                "invoke-direct/range {p0 .. p1}, $definingClass->setVideoInformation($playerResponseModelClass)V",
            )
        }
    }
}

// ── Exported hook functions ───────────────────────────────────────────────────

/** Hook called on the main thread when the player initialises. Target: `()V`. */
internal fun onMusicCreateHook(targetClass: String, targetMethod: String) =
    playerConstructorMethod.addInstruction(
        playerConstructorIndex++,
        "invoke-static { }, $targetClass->$targetMethod()V",
    )

/** Hook called ~every 1000ms with current playback position. Target: `(J)V`. */
fun musicVideoTimeHook(targetClass: String, targetMethod: String) =
    videoTimeMethod.addInstruction(
        videoTimeInsertIndex++,
        "invoke-static/range { $videoTimeRegisters }, $targetClass->$targetMethod(J)V",
    )

/** Hook called with the new video ID when a track changes. Descriptor: `Lsome/Class;->method(Ljava/lang/String;)V`. */
fun musicVideoIdHook(descriptor: String) =
    videoInformationMethod.addInstruction(
        videoInformationMethod.implementation!!.instructions.size - 1,
        "invoke-static {v$REG_VIDEO_ID}, $descriptor",
    )
