/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class HideAskButtonPatch {
    private static final String ANDROID_SEARCH_BAR_ASK_BUTTON = "android_search_bar_ask_button";

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean hideAskButton(String experimentName, boolean original) {
        if (Settings.HIDE_ASK_BUTTON.get() && experimentName != null && experimentName.startsWith(ANDROID_SEARCH_BAR_ASK_BUTTON)) {
            return false;
        }

        return original;
    }
}
