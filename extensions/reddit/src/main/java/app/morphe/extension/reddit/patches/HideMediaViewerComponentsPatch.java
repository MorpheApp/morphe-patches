/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;

import androidx.annotation.RequiresApi;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class HideMediaViewerComponentsPatch {

    /**
     * The media viewer activity the status bar is hidden in.
     */
    private static WeakReference<Activity> mediaViewerRef = new WeakReference<>(null);

    /**
     * If the overlay of the media viewer is visible, and the status bar is shown with it.
     */
    private static boolean overlayVisible;

    /**
     * If Reddit has hidden all system bars itself, such as for a video in landscape.
     */
    private static boolean systemBarsHidden;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return If hiding the media viewer overlay was included during patching.
     *         It requires a newer app version than the rest of this patch.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isHideOverlayIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return If hiding the status bar with the media viewer overlay was included during patching.
     *         It requires a newer app version than hiding the overlay.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isHideStatusBarIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return If the media viewer shows the "See the conversation" button. Newer versions show it
     *         in a dock below the media.
     */
    public static boolean showJoinConversationButton(boolean original) {
        return original && !Settings.HIDE_JOIN_CONVERSATION_BUTTON.get();
    }

    /**
     * Injection point.
     *
     * @return If a media viewer page skips the status bar padding. Images and videos skip it,
     *         but without the dock they would then be centered too high.
     */
    public static boolean skipStatusBarPadding(boolean original) {
        return original && !Settings.HIDE_JOIN_CONVERSATION_BUTTON.get();
    }

    /**
     * Injection point.
     * Called when the media viewer shows a post.
     *
     * @return True to start with the title, buttons and video controls hidden.
     */
    public static boolean hideOverlay() {
        return Settings.HIDE_MEDIA_VIEWER_OVERLAY.get();
    }

    private static boolean hideStatusBar() {
        return Settings.HIDE_MEDIA_VIEWER_STATUS_BAR.get() && !ImmersiveModePatch.isEnabled();
    }

    /**
     * Injection point.
     * Called when the media viewer activity resumes. The app versions with this hook
     * require Android 12L or newer.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public static void onMediaViewerResumed(Activity activity) {
        if (!hideStatusBar() || mediaViewerRef.get() == activity) {
            return;
        }

        try {
            mediaViewerRef = new WeakReference<>(activity);
            overlayVisible = true;
            systemBarsHidden = false;

            View content = activity.findViewById(android.R.id.content);
            content.setOnApplyWindowInsetsListener((view, insets) -> {
                WindowInsets rootInsets = view.getRootWindowInsets();
                final boolean hidden = rootInsets != null
                        && !rootInsets.isVisible(WindowInsets.Type.navigationBars());
                if (systemBarsHidden && !hidden) {
                    // Reddit shows all system bars again, such as when a video is rotated back
                    // to portrait.
                    updateStatusBar(activity.getWindow());
                }
                systemBarsHidden = hidden;

                return view.onApplyWindowInsets(withStableStatusBarInsets(insets));
            });
            // Keep the padding of the media viewer for the status bar while it fades.
            content.setWindowInsetsAnimationCallback(new WindowInsetsAnimation.Callback(
                    WindowInsetsAnimation.Callback.DISPATCH_MODE_CONTINUE_ON_SUBTREE) {
                @Override
                public WindowInsets onProgress(WindowInsets insets, List<WindowInsetsAnimation> animations) {
                    return withStableStatusBarInsets(insets);
                }
            });
            content.requestApplyInsets();
        } catch (Exception ex) {
            Logger.printException(() -> "onMediaViewerResumed failure", ex);
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private static WindowInsets withStableStatusBarInsets(WindowInsets insets) {
        final int statusBars = WindowInsets.Type.statusBars();
        Insets stable = insets.getInsetsIgnoringVisibility(statusBars);
        return stable.equals(insets.getInsets(statusBars))
                ? insets
                : new WindowInsets.Builder(insets).setInsets(statusBars, stable).build();
    }

    /**
     * Injection point.
     * Called when the media viewer draws the overlay of the current post.
     */
    @RequiresApi(Build.VERSION_CODES.S)
    public static void onOverlayVisibilityChanged(boolean visible) {
        if (!hideStatusBar() || overlayVisible == visible) {
            return;
        }

        try {
            overlayVisible = visible;
            Activity activity = mediaViewerRef.get();
            if (activity == null || systemBarsHidden) {
                return;
            }

            Window window = activity.getWindow();
            // Change the window after Compose has finished drawing the media viewer.
            window.getDecorView().post(() -> {
                if (activity == mediaViewerRef.get() && !activity.isFinishing()) {
                    updateStatusBar(window);
                }
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onOverlayVisibilityChanged failure", ex);
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private static void updateStatusBar(Window window) {
        try {
            WindowInsetsController controller = window.getInsetsController();
            // Reddit sets another behavior when it hides all system bars for a video in landscape.
            // The default behavior fades the bar in place.
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_DEFAULT);
            if (overlayVisible) {
                controller.show(WindowInsets.Type.statusBars());
            } else {
                controller.hide(WindowInsets.Type.statusBars());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateStatusBar failure", ex);
        }
    }
}
