/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3337
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class HideStatusBarPatch {

    /**
     * Injection point.
     * <p>
     * The app shows the status bar again after fullscreen and some dialogs,
     * so it is hidden again whenever the window is laid out with it visible.
     * A swipe from the top edge still shows it for a moment.
     */
    public static void initialize(Activity activity) {
        if (!Settings.HIDE_STATUS_BAR.get()) {
            return;
        }

        try {
            Window window = activity.getWindow();
            View decorView = window.getDecorView();

            hideStatusBar(window, decorView);
            decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                if (isStatusBarVisible(decorView)) {
                    hideStatusBar(window, decorView);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
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
