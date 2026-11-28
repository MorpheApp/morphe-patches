package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;

import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class HideMediaViewerStatusBarPatch {
    private static WeakReference<Activity> viewer = new WeakReference<>(null);
    private static boolean overlayVisible;

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static void onViewerResumed(Activity activity) {
        if (!Settings.HIDE_MEDIA_VIEWER_STATUS_BAR.get() || viewer.get() == activity
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }

        try {
            Window window = activity.getWindow();
            View content = activity.findViewById(android.R.id.content);
            // Keep the viewer's padding constant during and after the system fade.
            content.setOnApplyWindowInsetsListener((view, insets) ->
                    view.onApplyWindowInsets(withStableStatusBarInsets(insets)));
            content.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override
                public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> animations) {
                    return withStableStatusBarInsets(insets);
                }
            });
            content.requestApplyInsets();

            // Android's default behavior fades the bar in place.
            WindowCompat.getInsetsController(window, window.getDecorView())
                    .setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_DEFAULT);
            viewer = new WeakReference<>(activity);
            overlayVisible = true;
        } catch (Exception ex) {
            Logger.printException(() -> "onViewerResumed failure", ex);
        }
    }

    private static WindowInsets withStableStatusBarInsets(WindowInsets insets) {
        final int statusBars = WindowInsets.Type.statusBars();
        Insets stable = insets.getInsetsIgnoringVisibility(statusBars);
        return stable.equals(insets.getInsets(statusBars))
                ? insets
                : new WindowInsets.Builder(insets).setInsets(statusBars, stable).build();
    }

    public static void onOverlayVisibilityChanged(boolean visible) {
        if (!Settings.HIDE_MEDIA_VIEWER_STATUS_BAR.get()) {
            return;
        }

        try {
            Activity activity = viewer.get();
            if (activity == null || overlayVisible == visible) {
                return;
            }

            Window window = activity.getWindow();
            View decor = window.getDecorView();
            overlayVisible = visible;
            // Apply the window change after Compose has finished drawing the viewer.
            decor.post(() -> {
                try {
                    if (activity != viewer.get() || activity.isFinishing() || overlayVisible != visible) {
                        return;
                    }
                    WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(window, decor);
                    final int statusBars = WindowInsetsCompat.Type.statusBars();
                    if (visible) {
                        controller.show(statusBars);
                    } else {
                        controller.hide(statusBars);
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "applyOverlayVisibility failure", ex);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onOverlayVisibilityChanged failure", ex);
        }
    }
}
