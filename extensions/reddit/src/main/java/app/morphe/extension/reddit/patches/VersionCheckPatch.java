/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.patches;

import app.morphe.extension.shared.Utils;

public class VersionCheckPatch {
    @SuppressWarnings("SameParameterValue")
    private static boolean isVersionOrGreater(String version) {
        return Utils.getAppVersionName().compareTo(version) >= 0;
    }

    public static final boolean is_2025_52_or_greater = isVersionOrGreater("2025.52.0");
}
