/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3288
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.medianotification

import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.medianotification.mediaNotificationControlsPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

@Suppress("unused")
val mediaNotificationControlsPatchMusic = mediaNotificationControlsPatch(
    block = {
        dependsOn(
            sharedExtensionPatch,
            settingsPatch
        )

        compatibleWith(COMPATIBILITY_YOUTUBE_MUSIC)
    },
    preferenceScreen = PreferenceScreen.PLAYER,
    additionalPreferences = setOf(
        // The lock screen and notification shade buttons are the media controls,
        // so the option that changes one of them belongs in this screen.
        SwitchPreference("morphe_music_replace_shuffle_with_loop_button", summary = true)
    )
)
