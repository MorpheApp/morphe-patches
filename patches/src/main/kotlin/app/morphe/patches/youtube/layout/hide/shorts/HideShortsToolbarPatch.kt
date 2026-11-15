/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.hide.shorts

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.YouTubeActivityOnCreateFingerprint

private const val EXTENSION_CLASS = "Lapp/morphe/extension/youtube/patches/HideShortsToolbarPatch;"

@Suppress("unused")
val hideShortsToolbarPatch = bytecodePatch(
    name = "Hide Shorts toolbar",
    description = "Adds options to hide the toolbar and the system status bar while watching Shorts.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.addShortsPlayerPreferences(
            SwitchPreference("morphe_hide_shorts_toolbar", summary = true),
            SwitchPreference("morphe_hide_shorts_status_bar", summary = true),
        )

        YouTubeActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION_CLASS->initialize(Landroid/app/Activity;)V",
        )
    }
}
