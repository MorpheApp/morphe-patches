/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5173
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.patches.youtube.layout.returnyoutubeusername

import app.morphe.patcher.patch.patch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.litho.relayout.lithoRelayoutPatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.shared.misc.settings.preference.PreferenceScreenPreference
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.textcomponent.hookLithoSpannableString
import app.morphe.patches.shared.misc.textcomponent.lithoSpannableStringPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/returnyoutubeusername/ReturnYouTubeUsernamePatch;"

@Suppress("unused")
val returnYouTubeUsernamePatch = patch(
    name = "Return YouTube Username",
    description = "Adds an option to show channel names instead of handles in comments, below the video title and in Shorts.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        lithoSpannableStringPatch,
        // Shows the channel name once it is fetched.
        lithoRelayoutPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.GENERAL.addPreferences(
            PreferenceScreenPreference(
                key = "morphe_return_youtube_username_screen",
                sorting = PreferenceScreenPreference.Sorting.UNSORTED,
                preferences = setOf(
                    SwitchPreference("morphe_return_youtube_username", summary = true),
                    ListPreference("morphe_return_youtube_username_display_format"),
                )
            )
        )

        // Handles of channels are replaced with the channel name.
        hookLithoSpannableString(EXTENSION_CLASS)
    }
}
