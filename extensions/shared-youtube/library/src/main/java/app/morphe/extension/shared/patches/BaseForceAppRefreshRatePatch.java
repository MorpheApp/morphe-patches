/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2695
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import android.app.Activity;
import android.content.Context;
import android.os.Build;
import android.view.Display;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings({"deprecation", "unused"})
public final class BaseForceAppRefreshRatePatch {

    public enum ForceRefreshType {
        ALWAYS,
        PORTRAIT,
        LANDSCAPE,
        PORTRAIT_LANDSCAPE
    }

    public static class ForceRefreshRatePlayerOnly implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return !SharedYouTubeSettings.APP_REFRESH_RATE.isSetToDefault();
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(SharedYouTubeSettings.APP_REFRESH_RATE);
        }
    }

    public static String DEFAULT_REFRESH_RATE_VALUE = "DEFAULT";

    private static final List<WeakReference<Window>> trackedWindows = new ArrayList<>();
    @Nullable
    private static Integer preferredDisplayModeId;
    @Nullable
    private static Float preferredRefreshRate;
    @Nullable
    private static String[] availableRefreshRates;

    private static boolean isPlayerActive;
    private static boolean isPlayerInPortrait;

    @Nullable
    public static Float getPreferredRefreshRate() {
        Utils.verifyOnMainThread();
        if (!shouldOverrideRefreshRate()) {
            return null;
        }
        return preferredRefreshRate;
    }

    @Nullable
    public static String[] getAvailableRefreshRates() {
        Utils.verifyOnMainThread();
        return availableRefreshRates;
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
    public static float getRefreshRateOverride(float original) {
        final Float override = getPreferredRefreshRate();
        if (override != null && override > 0) {
            return override;
        }
        return original;
    }

    /**
     * Injection point.
     */
    public static void setActivityRefreshRate(Activity activity) {
        setWindowRefreshRate(activity, activity.getWindow());
    }

    public static void setWindowRefreshRate(Context context, @Nullable Window window) {
        if (!isPatchIncluded() || window == null) {
            return;
        }

        Utils.verifyOnMainThread();

        // Track the window and clean up collected references.
        boolean alreadyTracked = false;
        Iterator<WeakReference<Window>> iterator = trackedWindows.iterator();
        while (iterator.hasNext()) {
            Window tracked = iterator.next().get();
            if (tracked == null) {
                iterator.remove();
            } else if (tracked == window) {
                alreadyTracked = true;
            }
        }

        if (!alreadyTracked) {
            trackedWindows.add(new WeakReference<>(window));
        }

        try {
            String refreshString = SharedYouTubeSettings.APP_REFRESH_RATE.get();
            final boolean isDefault = refreshString.equals(DEFAULT_REFRESH_RATE_VALUE);

            if (preferredDisplayModeId == null || preferredRefreshRate == null) {
                Display display;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    display = context.getDisplay();
                } else {
                    WindowManager windowManager = (WindowManager) context
                            .getSystemService(Context.WINDOW_SERVICE);
                    display = windowManager.getDefaultDisplay();
                }

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
                    setWindowRefreshRate(context, window);
                    return;
                }

                // Find the highest refresh rate for the current resolution that does not exceed the target.
                Display.Mode bestMode = Arrays.stream(supportedModes)
                        .filter(mode ->
                                mode.getPhysicalWidth() == currentMode.getPhysicalWidth()
                                        && mode.getPhysicalHeight() == currentMode.getPhysicalHeight())
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

            applyRefreshRateToWindow(window);
        } catch (Exception ex) {
            Logger.printException(() -> "setWindowRefreshRate failure", ex);
        }
    }

    private static boolean shouldOverrideRefreshRate() {
        ForceRefreshType type = SharedYouTubeSettings.APP_REFRESH_RATE_TYPE.get();
        if (type == ForceRefreshType.ALWAYS) {
            return true;
        }
        if (!isPlayerActive) {
            return false;
        }
        return switch (type) {
            case PORTRAIT -> isPlayerInPortrait;
            case LANDSCAPE -> !isPlayerInPortrait;
            case PORTRAIT_LANDSCAPE -> true;
            default -> throw new IllegalStateException();
        };
    }

    private static void applyRefreshRateToWindow(Window window) {
        final boolean shouldOverride = shouldOverrideRefreshRate();

        window.getDecorView().post(() -> {
            WindowManager.LayoutParams params = window.getAttributes();
            int modeId = 0;
            float refreshRate = 0f;

            if (shouldOverride && preferredDisplayModeId != null && preferredDisplayModeId > 0) {
                modeId = preferredDisplayModeId;

                if (preferredRefreshRate != null) {
                    refreshRate = preferredRefreshRate;
                }
            }

            params.preferredDisplayModeId = modeId;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                params.preferredRefreshRate = refreshRate;
            }
            window.setAttributes(params);
        });
    }

    public static void videoPlayerIsActive(boolean overrideActive, boolean isPortrait) {
        Utils.verifyOnMainThread();

        isPlayerActive = overrideActive;
        isPlayerInPortrait = isPortrait;

        Iterator<WeakReference<Window>> iterator = trackedWindows.iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().get();
            if (window == null) {
                iterator.remove();
            } else {
                applyRefreshRateToWindow(window);
            }
        }
    }
}
