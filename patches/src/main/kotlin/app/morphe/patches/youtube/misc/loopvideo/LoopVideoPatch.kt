package app.morphe.patches.youtube.misc.loopvideo

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loopvideo.button.loopVideoButtonPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.video.information.playerStatusMethod
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/youtube/patches/LoopVideoPatch;"

val loopVideoPatch = bytecodePatch(
    name = "Loop video",
    description = "Adds an option to loop videos and display loop video button in the video player.",
) {
    dependsOn(
        sharedExtensionPatch,
        loopVideoButtonPatch,
        videoInformationPatch
    )

    compatibleWith(
        "com.google.android.youtube"(
            "20.14.43",
            "20.21.37",
            "20.31.42",
            "20.46.41",
        )
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_loop_video"),
        )

        playerStatusMethod.apply {
            // Add call to start playback again, but must not allow exit fullscreen patch call
            // to be reached if the video is looped.
            val insertIndex =
                indexOfFirstInstructionOrThrow(Opcode.SGET_OBJECT)

            // Since 'videoInformationPatch' is used as a dependency of this patch,
            // the loop is implemented through 'VideoInformation.seekTo(0)'.
            addInstructionsWithLabels(
                insertIndex,
                """
                    invoke-static { p1 }, $EXTENSION_CLASS_DESCRIPTOR->shouldLoopVideo(Ljava/lang/Enum;)Z
                    move-result v0
                    if-eqz v0, :do_not_loop
                    return-void
                    :do_not_loop
                    nop
                """
            )
        }
    }
}
