/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.layout.orientation

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.music.shared.MusicActivityOnCreateFingerprint
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/music/patches/ForcePortraitOrientationPatch;"

@Suppress("unused")
val forcePortraitOrientationPatch = bytecodePatch(
    name = "Force portrait orientation",
    description = "Adds an option to keep the app in portrait orientation when the device is rotated."
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            SwitchPreference("morphe_music_force_portrait_orientation", summary = true),
        )

        MusicActivityOnCreateFingerprint.method.apply {
            // p0 of onCreate(Bundle). The method has more than 16 registers, so use a range call.
            val activityRegister = implementation!!.registerCount - 2

            addInstruction(
                0,
                "invoke-static/range { v$activityRegister .. v$activityRegister }, $EXTENSION_CLASS->setRequestedOrientation(Landroid/app/Activity;)V"
            )
        }
    }
}
