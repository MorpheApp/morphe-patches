/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5441
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class RestoreOldCastPatch {

    private static final boolean RESTORE_OLD_CAST_BEHAVIOR = Settings.RESTORE_OLD_CAST_BEHAVIOR.get();

    /**
     * Injection point.
     *
     * @param original If the app disconnects the Cast session and plays a newly selected video on the phone.
     */
    public static boolean disconnectCastOnVideoSelect(boolean original) {
        return original && !RESTORE_OLD_CAST_BEHAVIOR;
    }

    /**
     * Injection point.
     *
     * @param original If the app disconnects the Cast session when the player is closed or minimized.
     */
    public static boolean disconnectCastOnPlayerClose(boolean original) {
        return original && !RESTORE_OLD_CAST_BEHAVIOR;
    }

    /**
     * Injection point.
     *
     * @param original If swiping down the player while casting dismisses it, instead of minimizing it.
     */
    public static boolean dismissPlayerOnSwipeWhileCasting(boolean original) {
        return original && !RESTORE_OLD_CAST_BEHAVIOR;
    }
}
