/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;

@SuppressWarnings("unused")
public class RememberRepeatStatePatch {

    /**
     * Injection point
     */
    public static boolean rememberRepeatState() {
        return Settings.REMEMBER_REPEAT_STATE.get();
    }
}
