/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3580
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableContinueWatchingPromptPatch {

    /**
     * Injection point.
     *
     * @param comparison Result of comparing the time since the last user interaction with
     *                   the inactivity limit. Negative when the limit has not been reached.
     */
    public static int adjustInactivityComparison(int comparison) {
        return Settings.DISABLE_CONTINUE_WATCHING_PROMPT.get() ? -1 : comparison;
    }

    /**
     * Injection point.
     */
    public static boolean disableConfirmDialog() {
        return Settings.DISABLE_CONTINUE_WATCHING_PROMPT.get();
    }
}
