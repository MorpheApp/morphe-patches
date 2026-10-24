/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import app.morphe.extension.shared.Utils;

public class VersionCheckPatch {
    public static final boolean is_2026_25_or_greater = Utils.getAppVersionName().compareTo("2026.25.0") >= 0;
}
