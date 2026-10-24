/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3268
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.shared

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.getFreeRegisterProvider
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.youtube.misc.playservice.is_21_20_or_greater
import app.morphe.patches.youtube.misc.playservice.versionCheckPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.video.information.PlaybackStartDescriptorToStringFingerprint
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import java.lang.ref.WeakReference

private lateinit var playbackStartVideoIdMethod: WeakReference<MutableMethod>

private lateinit var videoPlaybackIntentMethod: WeakReference<MutableMethod>
private var videoPlaybackIntentInsertIndex: Int = 0
private var videoPlaybackIntentPlayerDescriptorClassRegister: Int = 0
private var videoPlaybackIntentFreeRegister1: Int = 0
private var videoPlaybackIntentFreeRegister2: Int = 0

private lateinit var shortsPlaybackIntentMethod: WeakReference<MutableMethod>

private val videoIntentContextHooks = mutableListOf<Pair<String, String>>()
private val videoIntentCommandTransforms = mutableListOf<Pair<String, String>>()

@Suppress("unused")
val openVideoIntentPatch = bytecodePatch(
    description = "Provides hooks into intent components to open videos for extension filtering."
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        addResourcesPatch,
        settingsPatch,
        versionCheckPatch,
    )

    execute {
        videoIntentContextHooks.clear()
        videoIntentCommandTransforms.clear()
        VideoPlaybackIntentFingerprint.let {
            it.method.apply {
                val match = it.instructionMatches[1]
                var moveResultRegister = match.getInstruction<OneRegisterInstruction>().registerA
                val insertIndex = match.index + 1
                val registerProvider = getFreeRegisterProvider(
                    insertIndex,
                    2,
                    moveResultRegister
                )
                var free1 = registerProvider.getFreeRegister()
                var free2 = registerProvider.getFreeRegister()

                videoPlaybackIntentMethod = WeakReference(this)
                videoPlaybackIntentInsertIndex = insertIndex
                videoPlaybackIntentPlayerDescriptorClassRegister = moveResultRegister
                videoPlaybackIntentFreeRegister1 = free1
                videoPlaybackIntentFreeRegister2 = free2
            }
        }

        playbackStartVideoIdMethod = WeakReference(
            PlaybackStartDescriptorToStringFingerprint.instructionMatches[1].getMethodCalled()
        )

        // Same method is modified by openShortsInRegularPlayerPatch,
        // and by coincidence that patch runs before this patch which is critical.
        shortsPlaybackIntentMethod = WeakReference(
            (if (is_21_20_or_greater) ShortsPlaybackIntentFingerprint
            else ShortsPlaybackIntentFingerprintLegacy).method
        )
    }

    finalize {
        // Existing filtering hooks cache an instruction index during execute.
        // Insert entry transforms only after all consumers have used that index.
        val method = videoPlaybackIntentMethod.get()!!
        videoIntentContextHooks.asReversed().forEach { (classDescriptor, methodName) ->
            method.addInstructionsAtControlFlowLabel(0, """
                invoke-static/range {p1 .. p2}, $classDescriptor->$methodName(Lcom/google/protobuf/MessageLite;Ljava/util/Map;)Ljava/util/Map;
                move-result-object p2
            """)
        }
        // Normalize the immutable command before context hooks or the native
        // descriptor can observe its original playlist. Retain its native type.
        videoIntentCommandTransforms.asReversed().forEach { (classDescriptor, methodName) ->
            method.addInstructionsAtControlFlowLabel(0, """
                invoke-static/range {p1 .. p2}, $classDescriptor->$methodName(Lcom/google/protobuf/MessageLite;Ljava/util/Map;)Lcom/google/protobuf/MessageLite;
                move-result-object p1
                check-cast p1, ${method.parameterTypes.first()}
            """)
        }
    }
}

/** Transform the command context before native watch-descriptor construction, without consuming it. */
fun hookVideoIntentContext(classDescriptor: String, methodName: String) {
    videoIntentContextHooks += classDescriptor to methodName
}

/** Transform the watch command before its context and native playback descriptor are read. */
fun hookVideoIntentCommandTransform(classDescriptor: String, methodName: String) {
    videoIntentCommandTransforms += classDescriptor to methodName
}

/**
 * Hooks the command that opens a regular video, before the video is opened.
 *
 * @param methodDescriptor Method with the signature (Ljava/lang/Object;Ljava/util/Map;)V.
 */
fun hookVideoIntentCommand(methodDescriptor: String) {
    videoPlaybackIntentMethod.get()!!.addInstruction(
        0,
        "invoke-static/range { p1 .. p2 }, $methodDescriptor"
    )
    videoPlaybackIntentInsertIndex++
}

fun hookVideoIntent(
    classDescriptor: String,
    methodName: String = "onVideoIntentLoaded",
    detectVideo: Boolean,
    detectShorts: Boolean
) {
    if (detectVideo) {
        videoPlaybackIntentMethod.get()!!.addInstructionsAtControlFlowLabel(
            videoPlaybackIntentInsertIndex,
            patchLogic(
                "v$videoPlaybackIntentPlayerDescriptorClassRegister",
                "v$videoPlaybackIntentFreeRegister1",
                "v$videoPlaybackIntentFreeRegister2",
                classDescriptor,
                methodName
            )
        )
    }

    if (detectShorts) {
        shortsPlaybackIntentMethod.get()!!.addInstructionsWithLabels(
            0,
            patchLogic(
                "p1",
                "v0",
                "v1",
                classDescriptor,
                methodName
            )
        )
    }
}

private fun patchLogic(
    playerDescriptorClassRegister: String,
    free1: String,
    free2: String,
    classDescriptor: String,
    methodName: String
): String {
    val methodParameter = playerDescriptorClassRegister.startsWith("p")
    val currentPlaybackStartVideoIdMethod = playbackStartVideoIdMethod.get()!!

    return """
        move-object/from16 $free1, p2
        ${if (methodParameter) "move-object/from16 $free2, $playerDescriptorClassRegister"
        else ""}
        invoke-virtual { ${
            if (methodParameter) free2
            else playerDescriptorClassRegister
        } }, ${currentPlaybackStartVideoIdMethod.definingClass}->${currentPlaybackStartVideoIdMethod.name}()Ljava/lang/String;
        move-result-object $free2
        invoke-static { $free1, $free2 }, $classDescriptor->$methodName(Ljava/util/Map;Ljava/lang/String;)Z
        move-result $free1
        if-eqz $free1, :ignore
        return-void
        :ignore
        nop
    """
}
