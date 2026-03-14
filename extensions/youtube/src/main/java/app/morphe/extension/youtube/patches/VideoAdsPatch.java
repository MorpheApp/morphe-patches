/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class VideoAdsPatch {
    private static final boolean HIDE_VIDEO_ADS = Settings.HIDE_VIDEO_ADS.get();

    /**
     * Injection point.
     */
    public static boolean hideVideoAds() {
        return HIDE_VIDEO_ADS;
    }

    /**
     * Injection point.
     */
    public static String hideVideoAds(String osName) {
        return HIDE_VIDEO_ADS
                ? "Android Automotive"
                : osName;
    }

}
