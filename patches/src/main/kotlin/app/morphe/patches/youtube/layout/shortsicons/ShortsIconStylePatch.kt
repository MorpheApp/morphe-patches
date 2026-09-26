/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3287
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.patches.youtube.layout.shortsicons

import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.misc.settings.preference.ListPreference
import app.morphe.patches.youtube.layout.player.icons.copyPlayerIconStyles
import app.morphe.patches.youtube.layout.player.icons.wrapAppBitmapIcon
import app.morphe.patches.youtube.misc.extension.sharedExtensionPatch
import app.morphe.patches.youtube.misc.settings.PreferenceScreen
import app.morphe.patches.youtube.misc.settings.settingsPatch
import app.morphe.patches.youtube.shared.Constants.COMPATIBILITY_YOUTUBE

// App icon of a Shorts action button and the wrapper that takes its place.
// The yt_delhi icons are the Shorts player since 21.21, the youtube_shorts icons are the older one.
private val shortsIcons = listOf(
    "yt_delhi_heart_outline_24dp" to "DelhiHeart",
    "yt_delhi_heart_fill_white_24dp" to "DelhiHeartFill",
    "yt_delhi_comment_24dp" to "DelhiComment",
    "yt_delhi_bookmark_not_filled_24dp" to "DelhiSave",
    "yt_delhi_bookmark_filled_24dp" to "DelhiSaveFill",
    "yt_delhi_share_24dp" to "DelhiShare",
    "yt_delhi_remix_24dp" to "DelhiRemix",
    "yt_delhi_thumbs_up_not_filled_24dp" to "DelhiLike",
    "yt_delhi_thumbs_up_filled_24dp" to "DelhiLikeFill",
    "yt_delhi_thumbs_down_not_filled_24dp" to "DelhiDislike",
    "yt_delhi_thumbs_down_filled_24dp" to "DelhiDislikeFill",
    "youtube_shorts_heart_outline_32dp" to "ShortsHeart",
    "youtube_shorts_heart_fill_32dp" to "ShortsHeartFill",
    "youtube_shorts_heart_off_32dp" to "ShortsHeartOff",
    "youtube_shorts_comment_outline_32dp" to "ShortsComment",
    "youtube_shorts_save_outline_32dp" to "ShortsSave",
    "youtube_shorts_save_fill_32dp" to "ShortsSaveFill",
    "youtube_shorts_share_outline_32dp" to "ShortsShare",
    "youtube_shorts_remix_outline_32dp" to "ShortsRemix",
    "youtube_shorts_like_outline_32dp" to "ShortsLike",
    "youtube_shorts_like_fill_32dp" to "ShortsLikeFill",
    "youtube_shorts_dislike_outline_32dp" to "ShortsDislike",
    "youtube_shorts_dislike_fill_32dp" to "ShortsDislikeFill",
)

@Suppress("unused")
val shortsIconStylePatch = resourcePatch(
    name = "Shorts icon style",
    description = "Adds an option to change the style of the Shorts action button icons.",
) {
    dependsOn(
        sharedExtensionPatch,
        settingsPatch,
    )

    compatibleWith(COMPATIBILITY_YOUTUBE)

    execute {
        PreferenceScreen.SHORTS.addPreferences(
            ListPreference(
                key = "morphe_shorts_icon_style",
                tag = "app.morphe.extension.youtube.settings.preference.PlayerIconStyleListPreference"
            )
        )

        copyPlayerIconStyles(
            "shortsicons",
            "morphe_shorts_heart",
            "morphe_shorts_heart_fill",
            "morphe_shorts_comment",
            "morphe_shorts_save",
            "morphe_shorts_save_fill",
            "morphe_shorts_share",
            "morphe_shorts_remix",
            "morphe_shorts_like",
            "morphe_shorts_like_fill",
            "morphe_shorts_dislike",
            "morphe_shorts_dislike_fill"
        )

        // The buttons are Litho components that load these by resource id, so the resource itself is replaced.
        // The original is kept under a new name for the default style.
        shortsIcons.forEach { (appName, wrapperClass) ->
            wrapAppBitmapIcon(appName, wrapperClass, originalName = "morphe_$appName")
        }
    }
}
