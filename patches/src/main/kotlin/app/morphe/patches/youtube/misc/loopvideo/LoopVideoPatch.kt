package app.morphe.patches.youtube.misc.loopvideo

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResources
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.loopvideo.button.loopVideoButtonPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.video.information.playerStatusMethod
import app.morphe.patches.youtube.video.information.videoInformationPatch

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

        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_loop_video"),
        )

        playerStatusMethod.addInstructionsWithLabels(
            0,
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
