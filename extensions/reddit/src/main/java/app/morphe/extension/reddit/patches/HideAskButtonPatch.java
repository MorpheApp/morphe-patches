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
public final class HideAskButtonPatch {
    private static final String ANDROID_SEARCH_BAR_ASK_BUTTON = "android_search_bar_ask_button";

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }


    private static boolean isContextNotYetSet() {
        // Possible fix for background crash of app when context is not yet set.
        if (Utils.getContext() == null) {
            Logger.printInfo(() -> "Cannot hide ask button, context is null");
            return true;
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean hideAskButton(String experimentName, boolean original) {
        // Possible fix for background crash of app when context is not yet set.
        if (isContextNotYetSet()) {
            return original;
        }
        if (Settings.HIDE_ASK_BUTTON.get() && experimentName != null
                && experimentName.startsWith(ANDROID_SEARCH_BAR_ASK_BUTTON)) {
            return false;
        }

        return original;
    }

    /**
     * Injection point.
     */
    public static boolean shouldHideAskButton() {
        if (isContextNotYetSet()) {
            return false;
        }
        return Settings.HIDE_ASK_BUTTON.get();
    }
}
