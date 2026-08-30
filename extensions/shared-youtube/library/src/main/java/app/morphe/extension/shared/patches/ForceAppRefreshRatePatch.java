/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.app.Activity;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.Comparator;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings({"deprecation", "unused"})
public final class ForceAppRefreshRatePatch {

    public enum RefreshRate {
        FPS_DEFAULT(-1),
        FPS_30(30),
        FPS_60(60),
        FPS_120(120);

        public final int fps;

        RefreshRate(int fps) {
            this.fps = fps;
        }
    }

    @Nullable
    @GuardedBy("ForceRefreshRatePatch.class")
    private static Integer preferredDisplayModeId;

    @Nullable
    @GuardedBy("ForceRefreshRatePatch.class")
    private static Float preferredRefreshRate;

    @Nullable
    public static Float getPreferredRefreshRate() {
        synchronized (ForceAppRefreshRatePatch.class) {
            return preferredRefreshRate;
        }
    }

    /**
     * @return If this patch was included during patching.
     */
    private static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static float getSurfaceRefreshRate(float original) {
        final Float override = getPreferredRefreshRate();
        if (override == null) {
            return original;
        }
        return override;
    }

    /**
     * Injection point.
     */
    public static void setActivityRefreshRate(Activity activity) {
        if (!isPatchIncluded()) {
            return;
        }

        if (!Utils.isContextSet()) {
            Logger.printInfo(() -> "Cannot set refresh rate, context is null: " + activity);
            return;
        }

        synchronized (ForceAppRefreshRatePatch.class) {
            try {
                RefreshRate rate = SharedYouTubeSettings.REFRESH_RATE.get();
                if (rate == RefreshRate.FPS_DEFAULT) {
                    return;
                }

                if (preferredDisplayModeId == null) {
                    Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? activity.getDisplay()
                            : activity.getWindowManager().getDefaultDisplay();
                    if (display == null) {
                        Logger.printDebug(() -> "No Display available; cannot set preferred mode");
                        return;
                    }

                    Display.Mode[] supportedModes = display.getSupportedModes();
                    if (supportedModes == null || supportedModes.length == 0) {
                        Logger.printDebug(() -> "No supported display modes reported");
                        return;
                    }

                    Display.Mode currentMode = display.getMode();
                    final int targetRefreshRate = rate.fps;

                    // Find the highest refresh rate for the current resolution that does not exceed the target.
                    Display.Mode bestMode = Arrays.stream(supportedModes)
                            .filter(mode ->
                                    mode.getPhysicalWidth() == currentMode.getPhysicalWidth() &&
                                            mode.getPhysicalHeight() == currentMode.getPhysicalHeight())
                            .filter(mode -> Math.round(mode.getRefreshRate()) <= targetRefreshRate)
                            .max(Comparator.comparingDouble(Display.Mode::getRefreshRate))
                            .orElse(null);

                    if (bestMode == null) {
                        // Should never happen.
                        Logger.printDebug(() -> "Could not find any suitable display modes");
                        preferredDisplayModeId = -1;
                        preferredRefreshRate = -1f;
                        return;
                    }

                    preferredDisplayModeId = bestMode.getModeId();
                    preferredRefreshRate = bestMode.getRefreshRate();
                    Logger.printDebug(() -> "Forcing display mode: "
                            + bestMode.getPhysicalWidth() + "x" + bestMode.getPhysicalHeight()
                            + " " + Math.round(preferredRefreshRate) + "Hz");
                }

                if (preferredDisplayModeId < 0) {
                    return; // No suitable mode to use.
                }

                WindowManager.LayoutParams params = activity.getWindow().getAttributes();
                params.preferredDisplayModeId = preferredDisplayModeId;
                activity.getWindow().setAttributes(params);
            } catch (Exception ex) {
                Logger.printException(() -> "setActivityRefreshRate failure", ex);
            }
        }
    }
}
