/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2431
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.os.Build;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public final class ForceFullscreenLandscapePatch {

    private static final String FULLSCREEN =
            PlayerType.WATCH_WHILE_FULLSCREEN.name();
    private static final String SLIDING_MAXIMIZED_FULLSCREEN =
            PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN.name();

    /**
     * Activity the orientation is currently forced for, or null if nothing is forced.
     * The Activity is tracked because it can be recreated while fullscreen is not active.
     */
    @Nullable
    private static WeakReference<Activity> forcedActivityRef;

    /**
     * Orientation the Activity requested before fullscreen was forced to landscape.
     */
    private static int orientationToRestore;

    private ForceFullscreenLandscapePatch() {
    }

    /**
     * Phones and other small screens already open fullscreen in landscape,
     * and forcing the orientation fights with the app and endlessly rotates the screen.
     * <p>
     * YouTube opts out of the large-screen orientation restrictions on Android 16.
     * Android 17 and later remove that opt-out for apps targeting Android 17 or later,
     * so the fullscreen player stays in portrait
     * (<a href="https://github.com/MorpheApp/morphe-patches/issues/3145">...</a>).
     */
    private static boolean canForceOrientation() {
        return Utils.isTablet() && (Build.VERSION.SDK_INT <= Build.VERSION_CODES.BAKLAVA
                || Utils.getContext().getApplicationInfo().targetSdkVersion <= Build.VERSION_CODES.BAKLAVA);
    }

    public static final class LargeScreenAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return canForceOrientation();
        }
    }

    /**
     * Injection point.
     */
    public static void onPlayerTypeChanged(@Nullable Enum<?> playerType) {
        try {
            if (playerType == null) return;

            String name = playerType.name();
            // The player slides between maximized and fullscreen in both directions,
            // and the gesture can still be cancelled. Wait until the player settles.
            if (SLIDING_MAXIMIZED_FULLSCREEN.equals(name)) return;

            // Cannot use PlayerType.getCurrent() because this hook can run
            // before the player type hook of the same method updates it.
            boolean isFullscreen = FULLSCREEN.equals(name);

            if (isFullscreen && Settings.FORCE_FULLSCREEN_LANDSCAPE.get() && canForceOrientation()) {
                Activity activity = Utils.getActivity();
                if (activity == null) {
                    Logger.printDebug(() -> "Cannot force landscape orientation (activity is null)");
                    return;
                }
                if (forcedActivityRef != null && forcedActivityRef.get() == activity) return;

                orientationToRestore = activity.getRequestedOrientation();
                forcedActivityRef = new WeakReference<>(activity);

                Logger.printDebug(() -> "Forcing landscape orientation for fullscreen");
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
            } else if (forcedActivityRef != null) {
                Activity activity = forcedActivityRef.get();
                forcedActivityRef = null;
                if (activity == null) return;

                Logger.printDebug(() -> "Restoring orientation after fullscreen");
                activity.setRequestedOrientation(orientationToRestore);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onPlayerTypeChanged failure", ex);
        }
    }
}
