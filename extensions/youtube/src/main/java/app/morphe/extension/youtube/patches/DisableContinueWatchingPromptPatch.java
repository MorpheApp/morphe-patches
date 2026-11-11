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

    public enum ContinueWatchingPromptMode {
        /**
         * YouTube decides.
         */
        DEFAULT,
        /**
         * No 'Continue watching?' prompt and no inactivity limit for autoplay.
         */
        DISABLED,
        /**
         * Same as {@link #DISABLED}, and the end screen counts down to the next video even when
         * something holds an autoplay block (dialogs, bottom sheets, watch list scrolled past
         * the first item...). The autoplay switch of the player still turns autoplay off.
         */
        ALWAYS_COUNTDOWN
    }

    /**
     * Injection point.
     *
     * @param comparison Result of comparing the time since the last user interaction with
     *                   the inactivity limit. Negative when the limit has not been reached.
     */
    public static int adjustInactivityComparison(int comparison) {
        return Settings.CONTINUE_WATCHING_PROMPT.get() == ContinueWatchingPromptMode.DEFAULT
                ? comparison
                : -1;
    }

    /**
     * Injection point.
     */
    public static boolean disableConfirmDialog() {
        return Settings.CONTINUE_WATCHING_PROMPT.get() != ContinueWatchingPromptMode.DEFAULT;
    }

    /**
     * Injection point.
     *
     * @param blocked Whether something holds an autoplay block when the end screen decides
     *                between the countdown and the suggested video without countdown.
     */
    public static boolean adjustAutoplayBlocked(boolean blocked) {
        return blocked && Settings.CONTINUE_WATCHING_PROMPT.get() != ContinueWatchingPromptMode.ALWAYS_COUNTDOWN;
    }
}
