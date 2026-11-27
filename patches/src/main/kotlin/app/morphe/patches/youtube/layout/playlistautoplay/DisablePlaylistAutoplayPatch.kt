/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2628
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.playlistautoplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.patch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/DisablePlaylistAutoplayPatch;"

/**
 * Captures the playlist repeat mode, as YouTube also uses the AUTOPLAY navigation to repeat the current video.
 */
internal val playlistRepeatModeHookPatch = patch {
    dependsOn(sharedExtensionPatch)

    execute {
        NativeNavigationStateFingerprint.method.addInstructions(
            0,
            "invoke-static { p3 }, $EXTENSION_CLASS->setRepeatMode(I)V"
        )
    }
}

val disablePlaylistAutoplayPatch = patch(
    name = "Disable playlist autoplay",
    description = "Adds an option to stop a playlist from automatically advancing to the next video.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        playlistRepeatModeHookPatch,
        navigationIntentHookPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.PLAYER.addPreferences(
            SwitchPreference("morphe_disable_playlist_autoplay", summary = true)
        )

        navigationIntentHook(EXTENSION_CLASS, "shouldSkipPlaylistAutoplay")
    }
}
