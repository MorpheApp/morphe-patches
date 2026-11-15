package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Hides the app toolbar shown over the Shorts player (back, messages, search and menu buttons)
 * and the system status bar, but only while the Shorts player is open.
 */
@SuppressWarnings("unused")
public final class HideShortsToolbarPatch {

    private static final boolean HIDE_SHORTS_TOOLBAR = Settings.HIDE_SHORTS_TOOLBAR.get();

    /**
     * The "Hide status bar" setting already hides it everywhere.
     */
    private static final boolean HIDE_SHORTS_STATUS_BAR = Settings.HIDE_SHORTS_STATUS_BAR.get()
            && !Settings.HIDE_STATUS_BAR.get();

    private static WeakReference<Activity> activityRef = new WeakReference<>(null);

    private static boolean initialized;

    /**
     * If the app toolbar was hidden by this patch and must be restored when Shorts close.
     */
    private static boolean toolbarHidden;

    /**
     * Hides the toolbar and status bar again while Shorts are open. The app shows them again
     * after dialogs, the keyboard, and returning to the app.
     */
    @Nullable
    private static ViewTreeObserver.OnGlobalLayoutListener layoutListener;

    /**
     * Injection point.
     */
    public static void initialize(Activity activity) {
        if (!HIDE_SHORTS_TOOLBAR && !HIDE_SHORTS_STATUS_BAR) {
            return;
        }

        try {
            activityRef = new WeakReference<>(activity);

            if (initialized) {
                return;
            }
            initialized = true;

            ShortsPlayerState.getOnChange().addObserver((Boolean isOpen) -> updateShortsUi());

            activity.getApplication().registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityResumed(@NonNull Activity resumed) {
                    if (resumed == activityRef.get()) {
                        updateShortsUi();
                    }
                }

                @Override
                public void onActivityCreated(@NonNull Activity created, @Nullable Bundle bundle) {}
                @Override
                public void onActivityStarted(@NonNull Activity started) {}
                @Override
                public void onActivityPaused(@NonNull Activity paused) {}
                @Override
                public void onActivityStopped(@NonNull Activity stopped) {}
                @Override
                public void onActivitySaveInstanceState(@NonNull Activity saved, @NonNull Bundle bundle) {}
                @Override
                public void onActivityDestroyed(@NonNull Activity destroyed) {}
            });
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * The toolbar over the Shorts player is the same app toolbar used by other screens,
     * so it is only hidden while Shorts are open.
     */
    @Nullable
    private static View findAppToolbar(View decorView) {
        final int id = ResourceUtils.getIdIdentifier("appbar_layout");
        return id == 0 ? null : decorView.findViewById(id);
    }

    private static void applyShortsUi(Window window, View decorView) {
        if (HIDE_SHORTS_STATUS_BAR && isStatusBarVisible(decorView)) {
            setStatusBarHidden(window, decorView, true);
        }

        if (HIDE_SHORTS_TOOLBAR) {
            View toolbar = findAppToolbar(decorView);
            if (toolbar != null && toolbar.getVisibility() != View.GONE) {
                toolbar.setVisibility(View.GONE);
                toolbarHidden = true;
            }
        }
    }

    private static void updateShortsUi() {
        try {
            Activity activity = activityRef.get();
            if (activity == null) {
                return;
            }

            Window window = activity.getWindow();
            View decorView = window.getDecorView();
            final boolean shortsOpen = ShortsPlayerState.isOpen();
            Logger.printDebug(() -> "Shorts open: " + shortsOpen);

            if (shortsOpen) {
                applyShortsUi(window, decorView);

                if (layoutListener == null) {
                    layoutListener = () -> {
                        if (ShortsPlayerState.isOpen()) {
                            applyShortsUi(window, decorView);
                        }
                    };
                    decorView.getViewTreeObserver().addOnGlobalLayoutListener(layoutListener);
                }
                return;
            }

            if (layoutListener != null) {
                decorView.getViewTreeObserver().removeOnGlobalLayoutListener(layoutListener);
                layoutListener = null;
            }

            if (HIDE_SHORTS_STATUS_BAR) {
                setStatusBarHidden(window, decorView, false);
            }

            if (toolbarHidden) {
                toolbarHidden = false;
                View toolbar = findAppToolbar(decorView);
                if (toolbar != null) {
                    toolbar.setVisibility(View.VISIBLE);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateShortsUi failure", ex);
        }
    }

    private static boolean isStatusBarVisible(View decorView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = decorView.getRootWindowInsets();
            return insets == null || insets.isVisible(WindowInsets.Type.statusBars());
        }

        //noinspection deprecation
        return (decorView.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0;
    }

    @SuppressWarnings("deprecation")
    private static void setStatusBarHidden(Window window, View decorView, boolean hide) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController controller = window.getInsetsController();
            if (controller == null) {
                return;
            }

            if (hide) {
                controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars());
            } else {
                controller.show(WindowInsets.Type.statusBars());
            }
            return;
        }

        final int flags = View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
        final int current = decorView.getSystemUiVisibility();
        decorView.setSystemUiVisibility(hide ? (current | flags) : (current & ~flags));
    }
}
