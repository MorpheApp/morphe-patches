/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class FixFullScreenZoomGesturePatch {

    /**
     * Injection point.
     */
    public static boolean disableBrokenZoomFlag(boolean original) {
        if (original) {
            Logger.printInfo(() -> "Disabling problematic flag that interferes " +
                    "with fullscreen pinch to zoom: " + 45698813);
        }
        return false;
    }
}
