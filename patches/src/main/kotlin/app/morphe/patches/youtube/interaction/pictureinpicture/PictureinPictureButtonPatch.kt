/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3397
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.interaction.pictureinpicture

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.layout.buttons.overlay.addPlayerOverlayPreferences
import app.morphe.patches.youtube.layout.buttons.overlay.playerOverlayButtonsSettingsPatch
import app.morphe.patches.youtube.layout.flyout.flyoutPatch
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.addPlayerFlyoutMenuPreferences
import app.morphe.patches.youtube.layout.hide.player.flyoutmenu.playerFlyoutPreferences
import app.morphe.patches.youtube.layout.player.icons.copyPlayerButtonIcons
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playercontrols.addTopControl
import app.morphe.patches.youtube.misc.playercontrols.initializeTopControl
import app.morphe.patches.youtube.misc.playercontrols.legacyPlayerControlsPatch
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private val pictureinPictureButtonResourcePatch = resourcePatch {
    dependsOn(
        settingsPatch,
        legacyPlayerControlsPatch,
    )

    execute {
        copyPlayerButtonIcons("pictureinpicturebutton", "morphe_pip_button")
    }
}

private const val EXTENSION_BUTTON = "Lapp/morphe/extension/youtube/videoplayer/PictureinPictureButton;"
private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/PictureinPictureButtonPatch;"

@Suppress("unused")
val pictureinPictureButtonPatch = bytecodePatch(
    name = "Picture-in-picture button",
    description = "Adds an option to display a picture-in-picture button in the video player."
) {
    dependsOn(
        pictureinPictureButtonResourcePatch,
        sharedExtensionPatch,
        settingsPatch,
        legacyPlayerControlsPatch,
        playerOverlayButtonsSettingsPatch,
        playerFlyoutPreferences,
        flyoutPatch,
        bytecodePatch {
            finalize {
                addTopControl(
                    "pictureinpicturebutton",
                    "@+id/morphe_pip_button",
                    "@+id/morphe_pip_button",
                )
            }
        }
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        addPlayerOverlayPreferences(
            SwitchPreference("morphe_pip_button_overlay")
        )
        addPlayerFlyoutMenuPreferences(
            SwitchPreference("morphe_pip_button_flyout")
        )

        initializeTopControl(EXTENSION_BUTTON)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
