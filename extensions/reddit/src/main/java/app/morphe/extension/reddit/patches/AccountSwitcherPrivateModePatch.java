/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5161
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class AccountSwitcherPrivateModePatch {

    public enum PrivateMode {
        REDDIT_DEFAULT,
        ANONYMOUS_BROWSING,
        INCOGNITO
    }

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return If the account switcher shows Incognito instead of Anonymous Browsing.
     */
    public static boolean isIncognitoEnabled(boolean original) {
        switch (Settings.ACCOUNT_SWITCHER_PRIVATE_MODE.get()) {
            case ANONYMOUS_BROWSING:
                return false;
            case INCOGNITO:
                return true;
            default:
                return original;
        }
    }
}
