/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.spoof;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class SpoofAppVersionPatch {

    public static String getDefaultTarget() {
        return "";
    }

    public static final boolean SPOOF_APP_VERSION_ENABLED = SharedYouTubeSettings.SPOOF_APP_VERSION.get();
    public static final String SPOOF_APP_VERSION_TARGET = SharedYouTubeSettings.SPOOF_APP_VERSION_TARGET.get();

    public static String getUniversalAppVersionOverride(String version) {
        return SPOOF_APP_VERSION_ENABLED
                ? SPOOF_APP_VERSION_TARGET
                : version;
    }

    public static boolean isSpoofingToLessThan(String version) {
        return SPOOF_APP_VERSION_ENABLED && SPOOF_APP_VERSION_TARGET.compareTo(version) < 0;
    }
}
