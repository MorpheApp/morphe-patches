/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class HideAdsPatch {

    /**
     * Injection point.
     */
    public static boolean hideGetPremiumLabel() {
        return Settings.HIDE_GET_PREMIUM_LABEL.get();
    }

    /**
     * Injection point.
     */
    public static boolean hideVideoAds(boolean original) {
        if (Settings.HIDE_VIDEO_ADS.get()) {
            return false;
        }
        return original;
    }
}
