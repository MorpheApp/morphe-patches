/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableFullscreenGesturesPatch {

    /**
     * Injection point.
     */
    public static boolean disableFullscreenGestures(String nextGestureType) {
        Logger.printDebug(() -> "The next player gesture will be: " + nextGestureType);
        return ("MAXIMIZED_PULLED_UP".equals(nextGestureType) &&
                Settings.DISABLE_FULLSCREEN_PULLED_UP_GESTURE.get()) ||
                ("MAXIMIZED_TO_FULLSCREEN_SLIDING".equals(nextGestureType)
                        && Settings.DISABLE_FULLSCREEN_SLIDING_GESTURE.get()) ||
                ("FULLSCREEN_DRAGGED_DOWN".equals(nextGestureType)
                        && Settings.DISABLE_FULLSCREEN_DRAGGED_DOWN_GESTURE.get());
    }
}
