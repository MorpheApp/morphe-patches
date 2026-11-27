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
     * @return {@code false} when restoring the old Cast behavior so companion mode flags are disabled,
     *         or the original flag value otherwise.
     */
    public static boolean useCastCompanion(boolean original) {
        return !RESTORE_OLD_CAST_BEHAVIOR && original;
    }
}
