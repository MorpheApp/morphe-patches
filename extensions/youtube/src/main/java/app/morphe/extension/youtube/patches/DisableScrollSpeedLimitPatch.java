/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2582
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableScrollSpeedLimitPatch {

    /**
     * Injection point.
     */
    public static boolean disableSpeedScrolling(boolean original) {
        if (Settings.DISABLE_SCROLLING_SPEED.get()) {
            return false;
        }
        return original;
    }
}
