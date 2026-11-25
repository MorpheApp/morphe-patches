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
     * Called when a state update of a component tree is not laid out, as too many were dispatched
     * while a layout is calculated. Logged once each time the updates stop being laid out.
     *
     * @param stateUpdates State updates counted by the component tree.
     * @param maxStateUpdates State updates that are laid out.
     */
    public static void onStateUpdateLoop(int stateUpdates, int maxStateUpdates, @Nullable String attribution) {
        if (stateUpdates == maxStateUpdates + 1) {
            Logger.printInfo(() -> "Litho state update loop, state updates are not laid out: " + attribution);
        }
    }
}
