/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2431
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.patches.youtube.shared.getPlayerTypeFingerprint

private const val EXTENSION_CLASS_FORCE_LANDSCAPE =
    "Lapp/morphe/extension/youtube/patches/ForceFullscreenLandscapePatch;"

@Suppress("unused")
val forceFullscreenLandscapePatch = bytecodePatch(
    name = "Force fullscreen landscape",
    description = "Adds an option to rotate the player to landscape when entering fullscreen mode " +
            "on tablets and other large screen devices.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playerTypeHookPatch
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_force_fullscreen_landscape", summary = true)
        )

        getPlayerTypeFingerprint().method.addInstruction(
            0,
            "invoke-static { p1 }, $EXTENSION_CLASS_FORCE_LANDSCAPE->" +
                    "onPlayerTypeChanged(Ljava/lang/Enum;)V"
        )
    }
}
