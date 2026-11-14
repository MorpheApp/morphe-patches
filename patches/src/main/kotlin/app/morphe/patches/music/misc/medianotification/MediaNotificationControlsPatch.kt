/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3288
 * https://github.com/MorpheApp/morphe-patches/pull/4617
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.music.misc.medianotification

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patches.music.misc.extension.sharedExtensionPatch
import app.morphe.patches.music.misc.settings.PreferenceScreen
import app.morphe.patches.music.misc.settings.settingsPatch
import app.morphe.patches.music.shared.Constants.COMPATIBILITY_YOUTUBE_MUSIC
import app.morphe.patches.shared.misc.medianotification.mediaNotificationControlsPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference

private const val EXTENSION_CLASS_REPLACE_SHUFFLE =
    "Lapp/morphe/extension/music/patches/ReplaceShuffleWithLoopButtonPatch;"

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
        SwitchPreference("morphe_music_replace_shuffle_with_loop_button", summary = true)
    ),
    executeBlock = {
        // Shuffle and loop are custom actions of the media session. Only a few custom actions
        // fit in the media controls and shuffle is listed before loop, so the loop button
        // only shows if the shuffle action is not shown.
        ShuffleActionShownFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static { }, $EXTENSION_CLASS_REPLACE_SHUFFLE->hideShuffleAction()Z
                move-result v0
                if-eqz v0, :show_shuffle
                const/4 v0, 0x0
                return v0
                :show_shuffle
                nop
            """
        )
    }
)
