/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5331
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class FixLithoLayoutLoopPatch {

    /**
     * Injection point.
     * Called when Litho detects a state update loop, once for each loop.
     *
     * @param attribution Attribution of the last state update.
     */
    public static void onStateUpdateLoop(@Nullable String attribution) {
        Logger.printInfo(() -> "Litho state update loop during layout: " + attribution);
    }
}
