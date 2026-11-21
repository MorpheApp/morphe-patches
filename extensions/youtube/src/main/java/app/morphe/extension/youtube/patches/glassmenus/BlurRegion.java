/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5107
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.glassmenus;

import android.graphics.drawable.Drawable;
import android.os.Build;
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
 */
@RequiresApi(Build.VERSION_CODES.S)
final class BlurRegion {

    private static boolean initialized;
    @Nullable
    private static Method getViewRootImpl;
    @Nullable
    private static Method createBackgroundBlurDrawable;

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
