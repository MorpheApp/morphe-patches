/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2616
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.player.buttons.addPlayerBottomButton
import app.morphe.patches.youtube.layout.player.buttons.playerOverlayButtonsHookPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.initializeLegacyBottomControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.getPlayerTypeFingerprint
import app.morphe.util.ResourceGroup
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.copyResources

private const val EXTENSION_CLASS_VIDEO_SCALE =
    "Lapp/morphe/extension/youtube/patches/FullscreenVideoScalePatch;"
private const val EXTENSION_BUTTON =
    "Lapp/morphe/extension/youtube/videoplayer/FullscreenVideoScaleButton;"

private val fullscreenVideoScaleResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch
    )

    execute {
        copyResources(
            "fullscreenvideoscalebutton",
            ResourceGroup(
                "drawable",
                "morphe_fullscreen_video_scale_fit.xml",
                "morphe_fullscreen_video_scale_fit_bold.xml",
                "morphe_fullscreen_video_scale_stretch.xml",
                "morphe_fullscreen_video_scale_stretch_bold.xml",
                "morphe_fullscreen_video_scale_zoom.xml",
                "morphe_fullscreen_video_scale_zoom_bold.xml"
            )
        )

        addLegacyBottomControl("fullscreenvideoscalebutton")
    }
}

@Suppress("unused")
val fullscreenVideoScalePatch = bytecodePatch(
    name = "Fullscreen video scale",
    description = "Adds options to stretch or zoom videos to fill the screen in fullscreen mode.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
        playerOverlayButtonsSettingsPatch,
        playerOverlayButtonsHookPatch,
        legacyPlayerControlsPatch,
        fullscreenVideoScaleResourcePatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.VIDEO.addPreferences(
            ListPreference("morphe_fullscreen_video_scale")
        )

        addPlayerOverlayPreferences(
            SwitchPreference("morphe_fullscreen_video_scale_button", summary = true)
        )

        addPlayerBottomButton(EXTENSION_BUTTON)
        initializeLegacyBottomControl(EXTENSION_BUTTON)

        getPlayerTypeFingerprint().method.addInstruction(
            0,
            "invoke-static { p0 }, $EXTENSION_CLASS_VIDEO_SCALE->" +
                    "attachPlayerOverlay(Landroid/view/View;)V"
        )

        YouTubePlayerOverlaysLayoutConstructorFingerprint.matchAll().forEach {
            it.method.addInstruction(
                it.instructionMatches.first().index,
                "invoke-static { p0 }, $EXTENSION_CLASS_VIDEO_SCALE->" +
                        "attachPlayerOverlay(Landroid/view/View;)V"
            )
        }

        YouTubePlayerViewOnLayoutFingerprint.let {
            it.method.addInstructionsAtControlFlowLabel(
                it.instructionMatches.first().index,
                "invoke-static { p0 }, $EXTENSION_CLASS_VIDEO_SCALE->" +
                        "onPlayerViewLayout(Landroid/view/View;)V"
            )
        }
    }
}
