/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches.glassmenus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

import java.lang.reflect.Method;

import app.morphe.extension.shared.Logger;

/**
 * Blurs the windows behind a window only within the bounds of a view, with rounded corners.
 * <p>
 * This is the background blur drawable of Android 12 and later, which the system uses for the window background
 * blur ({@code Window.setBackgroundBlurRadius}) and for the blur of its own panels. A window background blur blurs
 * behind the whole window, and a menu window is as large as the screen, so the drawable is used directly: drawn as
 * the background of the menu, it blurs only behind the menu, and follows it while it moves. The drawable is created
 * by the window of the view ({@code ViewRootImpl.createBackgroundBlurDrawable()}), which is not a public API, so if
 * it is not available, the menu has no blur and is nearly opaque instead.
 * <p>
 * Samsung devices report the cross-window blur of Android as off, but blur behind this drawable with the window blur
 * of One UI, which is what {@code View.semSetBlurInfo} of Samsung uses.
 */
@RequiresApi(Build.VERSION_CODES.S)
final class BlurRegion {

    private static final String SAMSUNG_CORE_RUNE = "com.samsung.android.rune.CoreRune";
    private static final String CROSS_WINDOW_BLUR_LISTENERS = "android.view.CrossWindowBlurListeners";

    @Nullable
    private static Boolean lastLoggedBlurEnabled;

    private static boolean initialized;
    @Nullable
    private static Method getViewRootImpl;
    @Nullable
    private static Method createBackgroundBlurDrawable;
    private static boolean samsungWindowBlurSupported;
    @Nullable
    private static Boolean crossWindowBlurSupported;

    @SuppressLint({"DiscouragedPrivateApi", "BlockedPrivateApi", "PrivateApi"})
    @SuppressWarnings("JavaReflectionMemberAccess")
    private static void initialize() {
        if (initialized) return;
        initialized = true;
        try {
            HiddenApiBypass.addHiddenApiExemptions(
                    "Landroid/view/View;->getViewRootImpl",
                    "Landroid/view/ViewRootImpl;->createBackgroundBlurDrawable",
                    "Lcom/android/internal/graphics/drawable/BackgroundBlurDrawable;"
            );
            getViewRootImpl = View.class.getDeclaredMethod("getViewRootImpl");
             Class<?> viewRootClass = Class.forName("android.view.ViewRootImpl");
            createBackgroundBlurDrawable = viewRootClass.getDeclaredMethod("createBackgroundBlurDrawable");
        } catch (Throwable ex) {
            Logger.printException(() -> "Background blur is not available", ex);
            getViewRootImpl = null;
            createBackgroundBlurDrawable = null;
        }

        try {
            Class<?> coreRune = Class.forName(SAMSUNG_CORE_RUNE);
            HiddenApiBypass.addHiddenApiExemptions("L" + SAMSUNG_CORE_RUNE.replace('.', '/') + ";");
            samsungWindowBlurSupported = coreRune.getField("FW_WINDOW_BLUR_SUPPORTED").getBoolean(null);
        } catch (ClassNotFoundException ex) {
            Logger.printDebug(() -> "Not a Samsung device");
        } catch (Throwable ex) {
            Logger.printException(() -> "Samsung window blur support is not known", ex);
        }

        try {
            HiddenApiBypass.addHiddenApiExemptions("L" + CROSS_WINDOW_BLUR_LISTENERS.replace('.', '/') + ";");
            crossWindowBlurSupported = Class.forName(CROSS_WINDOW_BLUR_LISTENERS)
                    .getField("CROSS_WINDOW_BLUR_SUPPORTED").getBoolean(null);
        } catch (Throwable ex) {
            Logger.printDebug(() -> "Cross-window blur support is not known: " + ex);
        }
    }

    /**
     * @return If the device is a Samsung device that blurs behind the drawable with its own window blur.
     */
    static boolean isSamsungWindowBlurSupported() {
        initialize();
        return samsungWindowBlurSupported;
    }

    /**
     * Logs the cross-window blur state when it changes, with the reasons Android may have turned it off.
     * Android does not report tunneled video playback, but if all the reasons here are fine and the blur is off
     * only while a video plays, that is the cause.
     */
    static void logBlurEnabled(Context context, boolean enabled) {
        if (!Logger.isDebugEnabled()) return;
        if (lastLoggedBlurEnabled != null && lastLoggedBlurEnabled == enabled) return;
        lastLoggedBlurEnabled = enabled;

        initialize();
        final String supported = crossWindowBlurSupported == null
                ? "unknown" : String.valueOf(crossWindowBlurSupported);
        final boolean turnedOffInSettings = Settings.Global.getInt(context.getContentResolver(),
                "disable_window_blurs", 0) == 1;
        PowerManager power = context.getSystemService(PowerManager.class);
        final String powerState = power == null
                ? "unknown"
                : "battery saver: " + power.isPowerSaveMode() + ", thermal status: " + power.getCurrentThermalStatus();

        final String state = "Window blur " + (enabled ? "on" : "off")
                + ", supported: " + supported
                + ", turned off in developer options: " + turnedOffInSettings
                + ", " + powerState;
        Logger.printDebug(() -> state);
    }

    /**
     * @param view A view attached to the window the blur is drawn in.
     * @return A drawable that blurs behind the window within its bounds, or null if it is not available.
     */
    @Nullable
    static Drawable create(View view) {
        initialize();
        if (getViewRootImpl == null || createBackgroundBlurDrawable == null) return null;
        try {
            Object viewRoot = getViewRootImpl.invoke(view);
            if (viewRoot == null) return null;
            return (Drawable) createBackgroundBlurDrawable.invoke(viewRoot);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not create the background blur", ex);
            return null;
        }
    }

    static void setBlurRadius(Drawable blur, int radius) {
        try {
            blur.getClass().getMethod("setBlurRadius", int.class).invoke(blur, radius);
        } catch (Throwable ex) {
            Logger.printException(() -> "setBlurRadius failure", ex);
        }
    }

    static void setCornerRadius(Drawable blur, float top, float bottom) {
        try {
            blur.getClass().getMethod("setCornerRadius", float.class, float.class, float.class, float.class)
                    .invoke(blur, top, top, bottom, bottom);
        } catch (NoSuchMethodException ex) {
            try {
                // Only one radius for all corners. The bottom of a sheet is at the bottom of the screen.
                blur.getClass().getMethod("setCornerRadius", float.class).invoke(blur, top);
            } catch (Throwable ex2) {
                Logger.printException(() -> "setCornerRadius failure", ex2);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "setCornerRadius failure", ex);
        }
    }

    private BlurRegion() {
    }
}
