package app.morphe.patches.youtube.layout.niconicocomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.layout.sponsorblock.ControlsOverlayFingerprint
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.LayoutConstructorFingerprint
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint
import app.morphe.patches.youtube.video.information.userSelectedPlaybackSpeedHook
import app.morphe.patches.youtube.video.information.videoInformationPatch
import app.morphe.patches.youtube.video.information.videoSpeedChangedHook
import app.morphe.patches.youtube.video.information.videoTimeHook
import app.morphe.patches.youtube.video.videoid.hookVideoId
import app.morphe.patches.youtube.video.videoid.videoIdPatch
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION_CONTROLLER =
    "Lapp/morphe/extension/youtube/niconicocomments/NiconicoCommentsController;"
private const val EXTENSION_TOGGLE_BUTTON =
    "Lapp/morphe/extension/youtube/niconicocomments/NiconicoCommentsToggleButton;"

@Suppress("unused")
val niconicoCommentsPatch = bytecodePatch(
    name = "Niconico-style live/replay comments (Experimental)",
    description = "Adds public live/replay chat overlays and a player on/off button.",
    default = false,
) {
    // Keep compatibility limited to APK versions with patching or device validation.
    compatibleWith(COMPATIBILITY_YOUTUBE.excluding(
        *COMPATIBILITY_YOUTUBE.targets
            .filter { it.version !in setOf("21.20.405", "21.23.492") }
            .map { it.version }
            .toTypedArray(),
    ))
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
        videoIdPatch,
        videoInformationPatch,
        playerOverlayButtonsHookPatch,
    )

    execute {
        // Migrate settings before the user can import a backup or open the player.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CONTROLLER->loadPreference(Landroid/content/Context;)V",
        )
        hookVideoId("$EXTENSION_CONTROLLER->onVideoIdChanged(Ljava/lang/String;)V")
        videoTimeHook(EXTENSION_CONTROLLER, "setVideoTime")
        videoSpeedChangedHook(EXTENSION_CONTROLLER, "setPlaybackRate")
        userSelectedPlaybackSpeedHook(EXTENSION_CONTROLLER, "setPlaybackRate")
        addPlayerBottomButton(EXTENSION_TOGGLE_BUTTON)

        ControlsOverlayFingerprint.let {
            // Refresh matches after other patches have modified the shared overlay method.
            it.clearMatch()
            LayoutConstructorFingerprint.clearMatch()
            val overlayMatch = it.instructionMatches.last()
            val overlayRegister = overlayMatch.getInstruction<OneRegisterInstruction>().registerA
            it.method.addInstruction(
                overlayMatch.index + 1,
                "invoke-static { v$overlayRegister }, $EXTENSION_CONTROLLER->initialize(Landroid/view/ViewGroup;)V",
            )
        }
    }
}
