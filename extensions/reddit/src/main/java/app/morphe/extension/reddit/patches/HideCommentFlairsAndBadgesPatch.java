/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5298
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideCommentFlairsAndBadgesPatch {

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     */
    @Nullable
    public static String getUserFlairText(@Nullable String flairText) {
        return Settings.HIDE_COMMENT_USER_FLAIRS.get() ? null : flairText;
    }

    /**
     * Injection point.
     */
    @Nullable
    public static List<?> getUserFlairRichText(@Nullable List<?> flairRichText) {
        return Settings.HIDE_COMMENT_USER_FLAIRS.get() ? null : flairRichText;
    }

    /**
     * Injection point.
     */
    @Nullable
    public static Object getCommunityBadge(@Nullable Object badge) {
        return Settings.HIDE_COMMENT_COMMUNITY_BADGES.get() ? null : badge;
    }
}
