/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4933
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.car;

import android.app.Activity;
import android.companion.virtual.VirtualDevice;
import android.companion.virtual.VirtualDeviceManager;
import android.content.ContentProviderClient;
import android.content.Context;
import android.content.res.Configuration;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Finds whether YouTube is shown on a car display: a parked app on Android Auto, DriveDeck or another car host.
 * Used by Change form factor to use the automotive layout and to auto-hide the navigation bar on a car display.
 * <p>
 * A parked app is an ordinary activity that the car host launches onto a display of its own, so the
 * activity itself shows where it is. Nothing here relies on a list of allowed packages or hosts:
 * Android Auto reports its projection through the public car connection provider, DriveDeck names its
 * guest display {@code drivedeck-guest-<package>} and its virtual device {@code DriveDeck}, and a host
 * can give the display the car UI mode. The "any external display" detection needs none of these.
 * <p>
 * Car hosts that cannot place another app on the car display yet, such as Gearslip, graphene-auto and
 * MyCar, mirror the phone screen instead, so YouTube stays on the phone display. Android 15 and later
 * tell an app when its window is captured; while a car host is installed, a captured window counts
 * as shown in the car. This only adds hosts: Android Auto never depends on this list.
 */
@SuppressWarnings("unused")
public final class CarDisplay {

    /** androidx.car.app.connection.CarConnection, as Android Auto implements it. */
    private static final String CAR_CONNECTION_URI = "content://androidx.car.app.connection";
    private static final String CAR_CONNECTION_STATE = "CarConnectionState";
    private static final int CONNECTION_TYPE_PROJECTION = 2;

    /** Parts of the names that car hosts give their displays and virtual devices. */
    private static final String[] CAR_HOST_NAMES = {
            "drivedeck", "gearslip", "graphene", "mycar", "gearhead", "android auto", "androidauto", "automotive",
    };

    /**
     * Car hosts that show the phone screen in the car by capturing it.
     * Must be kept in sync with the manifest queries of the car display patch.
     */
    private static final String[] MIRRORING_CAR_HOSTS = {
            "app.johninnl.drivedeck", "app.johninnl.drivedeck.dev",
            "app.seb3thehacker.gearslip", "app.seb3thehacker.gearslip.dev",
            "io.github.psychobarge.grapheneauto",
            "io.github.sirionero.mycar",
    };

    /** If the activity is on a display of a car host. */
    private static volatile boolean onCarScreen;
    /** If the activity is captured while a car host is installed, which shows it in the car. */
    private static volatile boolean mirroredToCar;
    private static volatile boolean onCarDisplay;
    @Nullable
    private static Consumer<Integer> recordingCallback;
    private static WeakReference<WindowManager> recordingWindowManagerRef = new WeakReference<>(null);
    private static WeakReference<Activity> activityRef = new WeakReference<>(null);
    private static int activityDisplayId = Display.INVALID_DISPLAY;

    private CarDisplay() {
    }

    public static boolean isOnCarDisplay() {
        return onCarDisplay;
    }

    private static boolean isEnabled() {
        return Settings.CHANGE_FORM_FACTOR_CAR_DISPLAY.get()
                || Settings.CHANGE_FORM_FACTOR_CAR_DISPLAY_HIDE_NAVIGATION_BAR.get();
    }

    /**
     * Injection point, at the start of the onCreate of the main activity, before the activity
     * requests anything, so the first requests already use the layout of the display.
     */
    public static void onMainActivityCreate(Activity activity) {
        if (!isEnabled()) return;

        try {
            activityRef = new WeakReference<>(activity);
            activityDisplayId = getDisplayId(activity);
            onCarScreen = isCarDisplay(activity);
            mirroredToCar = false;
            watchScreenCapture(activity);
            onCarDisplay = onCarScreen || mirroredToCar;
            Logger.printDebug(() -> "Main activity on display " + activityDisplayId + ", car display: " + onCarScreen
                    + ", mirrored to a car: " + mirroredToCar);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "onMainActivityCreate failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param bottomBarContainer The container of the navigation bar.
     */
    public static void onBottomBarContainerCreated(View bottomBarContainer) {
        if (!isEnabled()) return;

        try {
            // Moving the activity to another display changes its size, so the container is laid out again.
            bottomBarContainer.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                          oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    checkDisplayChanged();
                }
            });

            if (Settings.CHANGE_FORM_FACTOR_CAR_DISPLAY_HIDE_NAVIGATION_BAR.get()) {
                CarNavigationBar.install(bottomBarContainer);
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "onBottomBarContainerCreated failure", ex);
        }
    }

    /**
     * The car host can move an open activity between the phone and the car display.
     */
    private static void checkDisplayChanged() {
        Activity activity = activityRef.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;

        final int displayId = getDisplayId(activity);
        if (displayId == activityDisplayId) return;
        activityDisplayId = displayId;

        onCarScreen = isCarDisplay(activity);
        Logger.printDebug(() -> "Main activity moved to display " + displayId + ", car display: " + onCarScreen);
        update(activity);
    }

    /**
     * The layout comes from the requests the activity made, so with the automotive layout the activity
     * is recreated. Otherwise only the navigation bar follows the display.
     */
    private static void update(Activity activity) {
        final boolean wasOnCarDisplay = onCarDisplay;
        onCarDisplay = onCarScreen || mirroredToCar;
        if (wasOnCarDisplay == onCarDisplay) return;

        if (Settings.CHANGE_FORM_FACTOR_CAR_DISPLAY.get()) {
            Utils.runOnMainThread(() -> {
                if (!activity.isFinishing() && !activity.isDestroyed()) {
                    activity.recreate();
                }
            });
        } else if (Settings.CHANGE_FORM_FACTOR_CAR_DISPLAY_HIDE_NAVIGATION_BAR.get()) {
            CarNavigationBar.onCarDisplayChanged();
        }
    }

    /**
     * Android 15 and later report whether the windows of the app are captured, such as by a car host
     * that mirrors the phone screen. The current state is reported right away, before the first requests.
     */
    private static void watchScreenCapture(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return;
        try {
            WindowManager previous = recordingWindowManagerRef.get();
            if (previous != null && recordingCallback != null) {
                previous.removeScreenRecordingCallback(recordingCallback);
            }
            if (!isCarHostInstalled()) {
                recordingCallback = null;
                return;
            }
            WindowManager windowManager = activity.getWindowManager();
            WeakReference<Activity> owner = new WeakReference<>(activity);
            Consumer<Integer> callback = state -> {
                Activity current = owner.get();
                if (current == null || current != activityRef.get()) return;
                mirroredToCar = state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE;
                Logger.printDebug(() -> "Mirrored to a car: " + mirroredToCar);
                update(current);
            };
            final int state = windowManager.addScreenRecordingCallback(activity.getMainExecutor(), callback);
            recordingCallback = callback;
            recordingWindowManagerRef = new WeakReference<>(windowManager);
            mirroredToCar = state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE;
        } catch (RuntimeException ex) {
            Logger.printException(() -> "watchScreenCapture failure", ex);
        }
    }

    private static boolean isCarHostInstalled() {
        for (String host : MIRRORING_CAR_HOSTS) {
            if (Utils.isPackageEnabled(host)) return true;
        }
        return false;
    }

    @SuppressWarnings("deprecation")
    @Nullable
    private static Display getDisplay(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return activity.getDisplay();
        }
        WindowManager windowManager = activity.getWindowManager();
        return windowManager == null ? null : windowManager.getDefaultDisplay();
    }

    private static int getDisplayId(Activity activity) {
        try {
            Display display = getDisplay(activity);
            return display == null ? Display.INVALID_DISPLAY : display.getDisplayId();
        } catch (RuntimeException ex) {
            Logger.printException(() -> "getDisplayId failure", ex);
            return Display.INVALID_DISPLAY;
        }
    }

    private static boolean isCarDisplay(Activity activity) {
        try {
            Display display = getDisplay(activity);
            if (display == null || display.getDisplayId() == Display.DEFAULT_DISPLAY) return false;
            if (Settings.CHANGE_FORM_FACTOR_CAR_DISPLAY_ANY.get()) return true;

            if (isCarUiMode(activity)) {
                Logger.printDebug(() -> "Car display found by the car UI mode");
                return true;
            }
            String displayName = display.getName();
            if (isCarHostName(displayName)) {
                Logger.printDebug(() -> "Car display found by the display name: " + displayName);
                return true;
            }
            String deviceName = getVirtualDeviceName(activity);
            if (isCarHostName(deviceName)) {
                Logger.printDebug(() -> "Car display found by the virtual device name: " + deviceName);
                return true;
            }
            if (isAndroidAutoProjecting(activity)) {
                Logger.printDebug(() -> "Car display found by the Android Auto projection, display: " + displayName);
                return true;
            }
            Logger.printDebug(() -> "Not a car display: " + displayName + ", virtual device: " + deviceName);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "isCarDisplay failure", ex);
        }
        return false;
    }

    private static boolean isCarUiMode(Context context) {
        Configuration configuration = context.getResources().getConfiguration();
        return (configuration.uiMode & Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_CAR;
    }

    /** Whether a display or virtual device name is one a car host gives. */
    static boolean isCarHostName(@Nullable String name) {
        if (name == null || name.isEmpty()) return false;
        String lowerCaseName = name.toLowerCase(Locale.ROOT);
        for (String carHostName : CAR_HOST_NAMES) {
            if (lowerCaseName.contains(carHostName)) return true;
        }
        return false;
    }

    /** The name of the virtual device the activity is shown on, or null for a display of the phone. */
    @Nullable
    private static String getVirtualDeviceName(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return null;
        try {
            final int deviceId = context.getDeviceId();
            if (deviceId == Context.DEVICE_ID_DEFAULT || deviceId == Context.DEVICE_ID_INVALID) return null;

            VirtualDeviceManager manager = context.getSystemService(VirtualDeviceManager.class);
            if (manager == null) return null;
            List<VirtualDevice> devices = manager.getVirtualDevices();
            for (VirtualDevice device : devices) {
                if (device.getDeviceId() != deviceId) continue;
                String name = device.getName();
                if (name != null) return name;
                return Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                        ? device.getPersistentDeviceId() : null;
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "getVirtualDeviceName failure", ex);
        }
        return null;
    }

    /**
     * Android Auto answers the car connection provider for every app, unless an Android Auto flag
     * limits it. The query is only made for an activity on a display other than the phone display.
     */
    private static boolean isAndroidAutoProjecting(Context context) {
        final Uri uri = Uri.parse(CAR_CONNECTION_URI);
        try (ContentProviderClient client = context.getContentResolver().acquireUnstableContentProviderClient(uri)) {
            if (client == null) return false;
            try (Cursor cursor = client.query(uri,
                    new String[]{CAR_CONNECTION_STATE}, null, null, null)) {
                if (cursor == null || !cursor.moveToFirst()) return false;
                final int column = cursor.getColumnIndex(CAR_CONNECTION_STATE);
                return column >= 0 && cursor.getInt(column) == CONNECTION_TYPE_PROJECTION;
            }
        } catch (Exception ex) {
            // A car host that is not installed, or does not answer this app.
            Logger.printDebug(() -> "Car connection is not available: " + ex);
            return false;
        }
    }
}
