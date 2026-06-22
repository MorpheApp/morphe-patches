/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class ShowViewCountPatch {
    private static final String ANDROID_POST_UNIT_VIEWS_COUNT = "android_post_unit_views_count_v2";

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean showViewCount(String experimentName, boolean original) {
        // Can be called before app context is set.
        if (Utils.getContext() == null) {
            Logger.printInfo(() -> "Cannot show view count, context is not yet set");
            return original;
        }

        if (Settings.SHOW_VIEW_COUNT.get() && experimentName != null
                && experimentName.startsWith(ANDROID_POST_UNIT_VIEWS_COUNT)) {
            return true;
        }

        return original;
    }
}
