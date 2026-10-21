/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3862
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.feedviews

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.all.misc.resources.addResourcesPatch
import app.morphe.patches.shared.misc.settings.preference.SwitchPreference
import app.morphe.patches.shared.misc.textcomponent.hookLithoSpannableString
import app.morphe.patches.shared.misc.textcomponent.lithoSpannableStringPatch
import app.morphe.patches.youtube.layout.hide.general.hideLayoutComponentsPatch
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.feedcards.feedVideoCardsHookPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE
import app.morphe.util.setExtensionIsPatchIncluded

private const val EXTENSION_CLASS =
    "Lapp/morphe/extension/youtube/patches/FeedViewCountPatch;"

@Suppress("unused")
val feedViewCountPatch = bytecodePatch(
    name = "Feed view count",
    description = "Adds the view count to the bottom left of the thumbnails of feed videos.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
        addResourcesPatch,
        // Finds the video cards. The view count is drawn by the extension.
        feedVideoCardsHookPatch,
        // Its hook of the text below the title calls the extension, before its
        // Hide view count option removes the view count.
        hideLayoutComponentsPatch,
        // The view count is removed from the texts of the cards that show the badge,
        // known by the Litho path of the text.
        lithoSpannableStringPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.FEED.addPreferences(
            SwitchPreference("morphe_feed_view_count", summary = true),
        )

        hookLithoSpannableString(EXTENSION_CLASS)

        setExtensionIsPatchIncluded(EXTENSION_CLASS)
    }
}
