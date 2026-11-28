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
    private static volatile boolean enabled;

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void initialize(Application application) {
        if (!Settings.IMMERSIVE_MODE.get()) {
            return;
        }

        try {
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
            enabled = true;
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    public static void hideStatusBar(Window window) {
        if (!enabled) {
            return;
        }

        try {
            View content = window.findViewById(android.R.id.content);
            if (content != null) {
                ViewCompat.setOnApplyWindowInsetsListener(content, (view, insets) -> {
                    final int statusBarType = WindowInsetsCompat.Type.statusBars();
                    final int cutoutTop = insets.getInsets(WindowInsetsCompat.Type.displayCutout()).top;
                    Insets statusBar = insets.getInsets(statusBarType);
                    // Reddit uses the status bar inset to position its top controls.
                    if (cutoutTop > statusBar.top) {
                        Insets safeStatusBar = Insets.of(statusBar.left, cutoutTop, statusBar.right, statusBar.bottom);
                        insets = new WindowInsetsCompat.Builder(insets)
                                .setInsets(statusBarType, safeStatusBar)
                                .build();
                    }
                    return ViewCompat.onApplyWindowInsets(view, insets);
                });
            }

            WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, window.getDecorView());
            controller.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            controller.hide(WindowInsetsCompat.Type.statusBars());
        } catch (Exception ex) {
            Logger.printException(() -> "hideStatusBar failure", ex);
        }
    }
}
