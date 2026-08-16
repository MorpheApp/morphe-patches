/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2431
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.player.fullscreen

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.playertype.addPlayerTypeHook
import app.morphe.patches.youtube.misc.playertype.playerTypeHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val FORCE_LANDSCAPE_EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/ForceFullscreenLandscapePatch;"

@Suppress("unused")
val forceFullscreenLandscapePatch = bytecodePatch(
    name = "Force fullscreen landscape",
    description = "Adds an option to force fullscreen portrait mode when viewing videos with landscape aspect ratio.",
) {
    compatibleWith(COMPATIBILITY_YOUTUBE)

    dependsOn(
        playerTypeHookPatch,
        settingsPatch,
    )

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_force_fullscreen_landscape", summary = true),
        )

        addPlayerTypeHook(
            "$FORCE_LANDSCAPE_EXTENSION_CLASS->onPlayerTypeChanged(Ljava/lang/Enum;)V",
        )
    }
}
