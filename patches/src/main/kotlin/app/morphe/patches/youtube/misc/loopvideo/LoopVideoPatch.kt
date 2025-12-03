package app.morphe.patches.youtube.misc.loopvideo

import app.revanced.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loopvideo.button.loopVideoButtonPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.video.information.videoEndMethod
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/LoopVideoPatch;"

val loopVideoPatch = bytecodePatch(
    name = "Loop video",
    description = "Adds an option to loop videos and display loop video button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        addResourcesPatch,
        loopVideoButtonPatch,
        videoInformationPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "19.43.41",
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        addResources("youtube", "misc.loopvideo.loopVideoPatch")

        PreferenceScreen.MISC.addPreferences(
            SwitchPreference("morphe_loop_video"),
        )

        videoEndMethod.apply {
            // Add call to start playback again, but must not allow exit fullscreen patch call
            // to be reached if the video is looped.
            val insertIndex = indexOfFirstInstructionReversedOrThrow(Opcode.INVOKE_VIRTUAL) + 1

            addInstructionsAtControlFlowLabel(
                insertIndex,
                """
                    invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldLoopVideo()Z
                    move-result v0
                    if-eqz v0, :do_not_loop
                    invoke-virtual { p0 }, ${videoStartPlaybackFingerprint.method}
                    return-void
                    :do_not_loop
                    nop
                """
            )
        }
    }
}
