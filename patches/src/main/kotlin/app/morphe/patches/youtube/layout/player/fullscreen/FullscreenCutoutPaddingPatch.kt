/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.NonInteractivePreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

@Suppress("unused")
internal val fullscreenCutoutPaddingPatch = bytecodePatch(
    name = "Fullscreen cutout padding",
    description = "Adds options to move the fullscreen video away from the camera cutout on " +
            "foldables and tablets, so the camera sits in the black bar instead of on top of " +
            "the video.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch
    )

    // Cannot declare as top level since this patch is in the same package as
    // other patches that declare same constant name with internal visibility.
    @Suppress("LocalVariableName")
    val EXTENSION_CLASS =
        "Lapp/morphe/extension/youtube/patches/FullscreenCutoutPaddingPatch;"

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_fullscreen_cutout_padding_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_fullscreen_cutout_padding", summary = true),
                    ListPreference("morphe_fullscreen_cutout_padding_mode"),
                    NonInteractivePreference(
                        key = "morphe_fullscreen_cutout_extra_margin",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
                    ),
                    ListPreference("morphe_fullscreen_cutout_manual_side"),
                    NonInteractivePreference(
                        key = "morphe_fullscreen_cutout_manual_amount",
                        tag = "app.morphe.extension.shared.settings.preference.SeekBarPreference"
                    )
                )
            )
        )

        // Registers a listener for entering and leaving fullscreen.
        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $EXTENSION_CLASS->initialize()V",
        )
    }
}
