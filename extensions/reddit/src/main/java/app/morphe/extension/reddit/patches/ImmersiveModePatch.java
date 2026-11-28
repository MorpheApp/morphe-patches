/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;
import android.view.Window;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class ImmersiveModePatch {

    /**
     * Read once, because the setting requires a restart.
     */
    private static final boolean ENABLED = isPatchIncluded() && Settings.IMMERSIVE_MODE.get();

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static boolean isEnabled() {
        return ENABLED;
    }

    /**
     * Injection point.
     */
    public static void initialize(Application application) {
        if (!ENABLED) {
            return;
        }

        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override
            public void onActivityResumed(@NonNull Activity activity) {
                hideStatusBar(activity.getWindow());
            }

            @Override
            public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {}

            @Override
            public void onActivityStarted(@NonNull Activity activity) {}

            @Override
            public void onActivityPaused(@NonNull Activity activity) {}

            @Override
            public void onActivityStopped(@NonNull Activity activity) {}

            @Override
            public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {}

            @Override
            public void onActivityDestroyed(@NonNull Activity activity) {}
        });
    }

    /**
     * Hides the status bar of an activity or a dialog window.
     */
    public static void hideStatusBar(Window window) {
        if (!ENABLED) {
            return;
        }

        try {
            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
            final int statusBarType = WindowInsetsCompat.Type.statusBars();

            View content = window.findViewById(android.R.id.content);
            if (content != null) {
                ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
                    // Reddit shows all system bars again after it hid them,
                    // such as when a video is rotated back to portrait.
                    // A swipe shows the bar without changing the insets.
                    if (insets.isVisible(statusBarType)) {
                        controller.hide(statusBarType);
                    }

                    // Reddit uses the status bar inset to place its top controls.
                    final int cutoutTop = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top;
                    Insets statusBar = insets.getInsets(statusBarType);
                    if (cutoutTop > statusBar.top) {
                        insets = new WindowInsetsCompat.Builder(insets)
                                .setInsets(statusBarType, Insets.of(statusBar.left, cutoutTop, statusBar.right, statusBar.bottom))
                                .build();
                    }
                    return ViewCompat.onApplyWindowInsets(view, insets);
                });
            }

            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(statusBarType);
        } catch (Exception ex) {
            Logger.printException(() -> "hideStatusBar failure", ex);
        }
    }
}
