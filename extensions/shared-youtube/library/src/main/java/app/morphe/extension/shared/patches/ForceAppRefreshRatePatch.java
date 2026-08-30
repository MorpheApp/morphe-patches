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
import android.view.View;
import android.view.Window;
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

    public static String DEFAULT_REFRESH_RATE_VALUE = "DEFAULT";

    @Nullable
    @GuardedBy("ForceAppRefreshRatePatch.class")
    private static Integer preferredDisplayModeId;

    @Nullable
    @GuardedBy("ForceAppRefreshRatePatch.class")
    private static Float preferredRefreshRate;

    @Nullable
    @GuardedBy("ForceAppRefreshRatePatch.class")
    private static String[] availableRefreshRates;

    @Nullable
    public static Float getPreferredRefreshRate() {
        synchronized (ForceAppRefreshRatePatch.class) {
            return preferredRefreshRate;
        }
    }

    @Nullable
    public static String[] getAvailableRefreshRates() {
        synchronized (ForceAppRefreshRatePatch.class) {
            return availableRefreshRates;
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
                String refreshString = SharedYouTubeSettings.APP_REFRESH_RATE.get();
                final boolean isDefault = refreshString.equals(DEFAULT_REFRESH_RATE_VALUE);

                if (preferredDisplayModeId == null) {
                    Display display = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                            ? activity.getDisplay()
                            : activity.getWindowManager().getDefaultDisplay();
                    if (display == null) {
                        Logger.printDebug(() -> "No Display available; cannot set preferred mode");
                        preferredDisplayModeId = -1;
                        preferredRefreshRate = -1f;
                        return;
                    }

                    Display.Mode[] supportedModes = display.getSupportedModes();
                    if (supportedModes == null || supportedModes.length == 0) {
                        Logger.printDebug(() -> "No supported display modes reported");
                        return;
                    }

                    Display.Mode currentMode = display.getMode();

                    // Detect and store available refresh rates for the current resolution.
                    availableRefreshRates = Arrays.stream(supportedModes)
                            .filter(mode -> mode.getPhysicalWidth() == currentMode.getPhysicalWidth() &&
                                    mode.getPhysicalHeight() == currentMode.getPhysicalHeight())
                            .map(mode -> String.valueOf(Math.round(mode.getRefreshRate())))
                            .distinct()
                            .sorted(Comparator.comparingInt(Integer::parseInt))
                            .toArray(String[]::new);

                    if (isDefault) {
                        preferredDisplayModeId = -1;
                        preferredRefreshRate = -1f;
                        return;
                    }

                    final int targetRefreshRate;
                    try {
                        targetRefreshRate = Integer.parseInt(refreshString);
                    } catch (Exception ex) {
                        Logger.printException(() -> "Invalid refresh rate", ex);
                        SharedYouTubeSettings.APP_REFRESH_RATE.resetToDefault();
                        setActivityRefreshRate(activity);
                        return;
                    }

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

                if (isDefault || preferredDisplayModeId < 0) {
                    return;
                }

                Window window = activity.getWindow();
                window.getDecorView().post(() -> {
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.preferredDisplayModeId = preferredDisplayModeId;
                    window.setAttributes(params);
                });
            } catch (Exception ex) {
                Logger.printException(() -> "setActivityRefreshRate failure", ex);
            }
        }
    }
}
