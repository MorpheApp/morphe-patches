/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class ShowViewCountPatch {

    /**
     * Options for displaying the view count on posts.
     */
    public enum ViewCount {
        DEFAULT,
        SHOW,
        HIDE
    }

    private static final String ANDROID_POST_UNIT_VIEWS_COUNT = "android_post_unit_views_count_v2";

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean showViewCount(String experimentName, boolean original) {
        // Can be called before app context is set.
        if (!Utils.isContextSet()) {
            Logger.printInfo(() -> "Cannot show view count, context is not yet set");
            return original;
        }

        if (experimentName != null && experimentName.startsWith(ANDROID_POST_UNIT_VIEWS_COUNT)) {
            return switch (Settings.VIEW_COUNT.get()) {
                case SHOW -> true;
                case HIDE -> false;
                case DEFAULT -> original;
            };
        }

        return original;
    }
}
