/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3337
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HideStatusBarPatch {

    /**
     * Activities whose status bar is hidden, so the status bar of an activity is hidden once.
     */
    private static final Set<Activity> activities = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Callback when an activity is started. Used to hide the status bar of the other activities,
     * such as the settings, which are created after the main activity.
     * The status bar is not hidden when the activity is created, because hiding it creates the window
     * and a theme the activity sets after that, such as the Morphe settings theme, is not applied.
     */
    private static final Application.ActivityLifecycleCallbacks ACTIVITY_LIFECYCLE_CALLBACKS
            = new Application.ActivityLifecycleCallbacks() {

        public void onActivityStarted(@NonNull Activity activity) {
            try {
                hideStatusBar(activity);
            } catch (Exception ex) {
                Logger.printException(() -> "onActivityStarted failure", ex);
            }
        }

        public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
        public void onActivityResumed(@NonNull Activity a) {}
        public void onActivityPaused(@NonNull Activity a) {}
        public void onActivityStopped(@NonNull Activity a) {}
        public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        public void onActivityDestroyed(@NonNull Activity a) {}
    };

    private static boolean activityLifecycleCallbacksRegistered;

    /**
     * Injection point.
     * <p>
     * Called when the main activity is created.
     */
    public static void initialize(Activity activity) {
        if (!Settings.HIDE_STATUS_BAR.get()) {
            return;
        }

        try {
            if (!activityLifecycleCallbacksRegistered) {
                activityLifecycleCallbacksRegistered = true;
                activity.getApplication().registerActivityLifecycleCallbacks(ACTIVITY_LIFECYCLE_CALLBACKS);
            }
            hideStatusBar(activity);
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    private static void hideStatusBar(Activity activity) {
        if (!activities.add(activity)) {
            return;
        }

        Window window = activity.getWindow();
        hideStatusBar(window);
        addDisplayCutoutInset(window);
    }

    /**
     * Injection point.
     * <p>
     * Called when a Morphe settings submenu is shown, which is a dialog with its own window.
     * The submenu toolbar is laid out below the display cutout by the submenu itself.
     */
    public static void hideStatusBar(Dialog dialog) {
        if (!Settings.HIDE_STATUS_BAR.get()) {
            return;
        }

        try {
            Window window = dialog.getWindow();
            if (window != null) {
                hideStatusBar(window);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "hideStatusBar failure", ex);
        }
    }

    /**
     * The app shows the status bar again after fullscreen and some dialogs,
     * so it is hidden again whenever the window is laid out with it visible.
     * A swipe from the top edge still shows it for a moment.
     */
    private static void hideStatusBar(Window window) {
        View decorView = window.getDecorView();

        hideStatusBar(window, decorView);
        decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            if (isStatusBarVisible(decorView)) {
                hideStatusBar(window, decorView);
            }
        });
    }

    /**
     * Injection point.
     * <p>
     * The status bar background view is sized with the stable status bar inset,
     * so when the status bar is hidden it covers the search bar and channel page top bar.
     */
    public static void hideStatusBarBackground(View view) {
        if (Settings.HIDE_STATUS_BAR.get()) {
            view.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * The top bars are laid out below the status bar using its inset, which is empty when the status bar
     * is hidden, so the top bars are covered by a display cutout at the top, such as a front camera hole.
     * The hidden status bar inset is replaced by the inset of the display cutout, so the top bars
     * are laid out below the cutout. Display cutouts not at the top, such as in landscape, have no top inset.
     * Display cutouts have no API before Android 9.
     */
    private static void addDisplayCutoutInset(Window window) {
        View content = window.findViewById(android.R.id.content);
        if (content == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return;
        }

        content.setOnApplyWindowInsetsListener((view, insets) -> {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets cutout = insets.getInsets(WindowInsets.Type.displayCutout());
                Insets statusBars = insets.getInsets(WindowInsets.Type.statusBars());
                if (!insets.isVisible(WindowInsets.Type.statusBars()) && cutout.top > statusBars.top) {
                    insets = new WindowInsets.Builder(insets)
                            .setInsets(WindowInsets.Type.statusBars(),
                                    Insets.of(statusBars.left, cutout.top, statusBars.right, statusBars.bottom))
                            .build();
                }
            } else {
                // The status bar is a part of the system window insets, which have no top inset when it's hidden.
                DisplayCutout cutout = insets.getDisplayCutout();
                //noinspection deprecation
                if (cutout != null && cutout.getSafeInsetTop() > insets.getSystemWindowInsetTop()) {
                    //noinspection deprecation
                    insets = insets.replaceSystemWindowInsets(insets.getSystemWindowInsetLeft(),
                            cutout.getSafeInsetTop(), insets.getSystemWindowInsetRight(),
                            insets.getSystemWindowInsetBottom());
                }
            }
            return view.onApplyWindowInsets(insets);
        });
    }

    private static boolean isStatusBarVisible(View decorView) {
        WindowInsets insets = decorView.getRootWindowInsets();
        if (insets == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.isVisible(WindowInsets.Type.statusBars());
        }
        //noinspection deprecation
        return (decorView.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
    }

    private static void hideStatusBar(Window window, View decorView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars());
            }
            return;
        }
        //noinspection deprecation
        decorView.setSystemUiVisibility(decorView.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
