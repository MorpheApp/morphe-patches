/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5161
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public final class PrivateBrowsingModePatch {

    public enum PrivateBrowsingMode {
        DEFAULT,
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
     * @return If private browsing uses Incognito instead of Anonymous Browsing.
     */
    public static boolean isIncognitoEnabled(boolean original) {
        return switch (Settings.PRIVATE_BROWSING_MODE.get()) {
            case DEFAULT -> original;
            case ANONYMOUS_BROWSING -> false;
            case INCOGNITO -> true;
        };
    }
}
