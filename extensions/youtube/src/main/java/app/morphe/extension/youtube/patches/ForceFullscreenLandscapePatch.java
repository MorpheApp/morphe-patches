/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2431
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.content.pm.ActivityInfo;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class ForceFullscreenLandscapePatch {
    private static final String FULLSCREEN = "WATCH_WHILE_FULLSCREEN";
    private static final String ENTERING_FULLSCREEN = "WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN";

    private static boolean orientationForced;
    private static int previousRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    private ForceFullscreenLandscapePatch() {
    }

    /**
     * Injection point.
     */
    public static void onPlayerTypeChanged(@Nullable Enum<?> playerType) {
        if (playerType == null) return;

        String name = playerType.name();
        boolean isFullscreen = FULLSCREEN.equals(name) || ENTERING_FULLSCREEN.equals(name);

        Activity activity = Utils.getActivity();
        if (activity == null) {
            Logger.printDebug(() -> "Cannot change fullscreen orientation (activity is null)");
            return;
        }

        if (Settings.FORCE_FULLSCREEN_LANDSCAPE.get() && isFullscreen) {
            if (orientationForced) return;

            previousRequestedOrientation = activity.getRequestedOrientation();
            orientationForced = true;
            Logger.printDebug(() -> "Forcing landscape orientation for fullscreen video");
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        } else if (orientationForced) {
            orientationForced = false;
            int orientationToRestore = previousRequestedOrientation;
            previousRequestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
            Logger.printDebug(() -> "Restoring orientation after fullscreen video");
            activity.setRequestedOrientation(orientationToRestore);
        }
    }
}
