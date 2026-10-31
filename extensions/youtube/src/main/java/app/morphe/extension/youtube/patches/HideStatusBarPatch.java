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
import android.content.res.Configuration;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.view.DisplayCutout;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings({"unused", "deprecation"})
public final class HideStatusBarPatch {

    /**
     * The settings are read once, so the edge to edge top bar and what follows it stay the same
     * until the app restarts, also if a setting is changed and the restart is postponed.
     */
    private static final boolean HIDE_STATUS_BAR = Settings.HIDE_STATUS_BAR.get();

    private static final boolean FIX_STATUS_BAR_LAYOUT = Settings.FIX_STATUS_BAR_LAYOUT.get();

    /**
     * If the edge to edge top bar feature flag is turned off for the whole session even if it is
     * not read yet: with the status bar layout fix, and the status bar hidden or the window laid out
     * below the status bar at a previous app start.
     */
    private static final boolean EDGE_TO_EDGE_TOP_BAR_TURNED_OFF = FIX_STATUS_BAR_LAYOUT
            && (HIDE_STATUS_BAR || Settings.WINDOW_BELOW_STATUS_BAR.get());

    /**
     * If the window drawn edge to edge ended above the system navigation buttons at a previous app
     * start, see {@link #watchWindowPosition(Activity)}.
     */
    private static final boolean WINDOW_ABOVE_NAVIGATION_BAR = Settings.WINDOW_ABOVE_NAVIGATION_BAR.get();

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
     * Main activities whose window position is watched once they are started.
     */
    private static final Set<Activity> windowPositionActivities = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Callback when an activity is started. The window of the main activity is not created
     * when it is initialized, so its position is watched once it is started.
     */
    private static final Application.ActivityLifecycleCallbacks WINDOW_POSITION_LIFECYCLE_CALLBACKS
            = new Application.ActivityLifecycleCallbacks() {

        public void onActivityStarted(@NonNull Activity activity) {
            try {
                if (windowPositionActivities.remove(activity)
                        && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    watchWindowPosition(activity);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onActivityStarted window position failure", ex);
            }
        }

        public void onActivityCreated(@NonNull Activity a, @Nullable Bundle b) {}
        public void onActivityResumed(@NonNull Activity a) {}
        public void onActivityPaused(@NonNull Activity a) {}
        public void onActivityStopped(@NonNull Activity a) {}
        public void onActivitySaveInstanceState(@NonNull Activity a, @NonNull Bundle b) {}
        public void onActivityDestroyed(@NonNull Activity a) {}
    };

    private static boolean windowPositionLifecycleCallbacksRegistered;

    /**
     * If a video is watched in the player that fills the screen in portrait, not in fullscreen.
     * The display cutout inset is then left out, so the video is laid out at the top of the screen
     * instead of below the cutout. It is added back in the miniplayer, in fullscreen and without
     * a video.
     */
    private static volatile boolean isWatchingVideo;

    /**
     * Value of the edge to edge top bar feature flag used by the whole app, or null if not read yet.
     */
    @Nullable
    private static volatile Boolean edgeToEdgeTopBar;

    /**
     * Value of the edge to edge top bar feature flag first handed to the app, with or without
     * the status bar layout fix, or null if not read yet. Kept for the whole session, so what
     * depends on it never changes while the app runs.
     */
    @Nullable
    private static volatile Boolean edgeToEdgeTopBarUsed;

    /**
     * Changed during patching since this class is also
     * used by {@link NavigationBarPatch}.
     */
    private static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * @return If the window is drawn behind the system bars, or null if the app did not read
     *         the edge to edge top bar feature flag yet and its value is not known.
     */
    @Nullable
    static Boolean isEdgeToEdgeTopBar() {
        Boolean edgeToEdge = edgeToEdgeTopBarUsed;
        if (edgeToEdge == null && isPatchIncluded() && EDGE_TO_EDGE_TOP_BAR_TURNED_OFF) {
            return false;
        }
        return edgeToEdge;
    }

    /**
     * @return If the window is not drawn behind the system navigation buttons even with the edge
     *         to edge top bar, as found at a previous app start.
     */
    static boolean isWindowAboveNavigationBar() {
        return isPatchIncluded() && WINDOW_ABOVE_NAVIGATION_BAR;
    }

    /**
     * Injection point.
     * <p>
     * The flag draws the window behind the status bar and gives the top bars the status bar inset.
     * It is read by many classes, and on some devices they do not get the same value. The window
     * is then laid out below the status bar while the top bars still make room for it, or the
     * other way around (https://github.com/MorpheApp/morphe-patches/issues/2969).
     * The first value read is used everywhere, so the app keeps its own value but uses it consistently.
     * <p>
     * With the status bar hidden, the edge to edge layout leaves an empty bar of the height of
     * the status bar above the player, so the flag is turned off and the top bars are laid out
     * with {@link #setSystemWindowInsetTop(Rect, View)}.
     * <p>
     * The flag is also turned off if the window was laid out below the status bar,
     * see {@link #watchWindowPosition(Activity)}.
     */
    public static boolean useEdgeToEdgeTopBar(boolean original) {
        if (!FIX_STATUS_BAR_LAYOUT) {
            if (edgeToEdgeTopBarUsed == null) {
                edgeToEdgeTopBarUsed = original;
            }
            return original;
        }

        Boolean value = edgeToEdgeTopBar;
        if (value == null) {
            synchronized (HideStatusBarPatch.class) {
                value = edgeToEdgeTopBar;
                if (value == null) {
                    final boolean used = original && !EDGE_TO_EDGE_TOP_BAR_TURNED_OFF;
                    value = used;
                    edgeToEdgeTopBar = value;
                    Logger.printDebug(() -> "Edge to edge top bar: " + used + " (original: " + original
                            + ", hide status bar: " + HIDE_STATUS_BAR
                            + ", window below status bar: " + Settings.WINDOW_BELOW_STATUS_BAR.get() + ")");
                }
            }
        }
        if (edgeToEdgeTopBarUsed == null) {
            edgeToEdgeTopBarUsed = value;
        }
        return value;
    }

    /**
     * Injection point.
     * <p>
     * The status bar color that follows the scrolling top bar only exists with the edge to edge
     * top bar. Without it the status bar view is never set up, and the first scroll of the
     * top bar crashes the app (https://github.com/MorpheApp/morphe-patches/issues/3805).
     * This flag can be read before the edge to edge top bar flag, which is then turned off later
     * if the status bar is hidden.
     */
    public static boolean useStatusBarScrollColor(boolean original) {
        if (!FIX_STATUS_BAR_LAYOUT) {
            return original;
        }

        Boolean edgeToEdge = edgeToEdgeTopBar;
        if (edgeToEdge == null) {
            edgeToEdge = !EDGE_TO_EDGE_TOP_BAR_TURNED_OFF;
        }
        return original && edgeToEdge;
    }

    /**
     * Injection point.
     * <p>
     * Called when the main activity is created.
     */
    public static void initialize(Activity activity) {
        // The window position is watched once the activity is started, see watchWindowPosition().
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                if (!windowPositionLifecycleCallbacksRegistered) {
                    windowPositionLifecycleCallbacksRegistered = true;
                    activity.getApplication().registerActivityLifecycleCallbacks(WINDOW_POSITION_LIFECYCLE_CALLBACKS);
                }
                windowPositionActivities.add(activity);
            } catch (Exception ex) {
                Logger.printException(() -> "initialize window position failure", ex);
            }
        }

        if (!HIDE_STATUS_BAR) {
            return;
        }

        try {
            if (!activityLifecycleCallbacksRegistered) {
                activityLifecycleCallbacksRegistered = true;
                activity.getApplication().registerActivityLifecycleCallbacks(ACTIVITY_LIFECYCLE_CALLBACKS);

                // The display cutout inset depends on the player, so it is applied again
                // when the player fills the screen or leaves it.
                PlayerType.getOnChange().addObserver((PlayerType type) -> {
                    final boolean watchingVideo = type == PlayerType.WATCH_WHILE_MAXIMIZED;
                    if (watchingVideo != isWatchingVideo) {
                        isWatchingVideo = watchingVideo;
                        for (Activity hiddenStatusBarActivity : activities) {
                            hiddenStatusBarActivity.getWindow().getDecorView().requestApplyInsets();
                        }
                    }
                });
            }
            hideStatusBar(activity);
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * Some devices keep the window out of the status bar area, for example Samsung devices with
     * the app not allowed to use the camera cutout area. The edge to edge top bar still makes room
     * for the status bar above the top bars, which is then drawn as a second status bar below
     * the real one and covers the top bars (https://github.com/MorpheApp/morphe-patches/issues/2969).
     * <p>
     * The flag is read before the window is laid out, so the position is saved and the flag is
     * turned off from the next app start. Only the full screen window in portrait with the status bar
     * shown is checked, where the status bar is above the window.
     * <p>
     * The same way, a window drawn edge to edge is expected to end behind the system navigation
     * buttons, which the navigation bar covering them relies on. Some devices and Android versions
     * keep it above them, and the room for them is then added twice, see
     * {@link NavigationBarPatch#useTranslucentNavigationBar(boolean)}. The bottom of the window is
     * saved, and the flags of the translucent navigation bar are turned off from the next app start.
     */
    @RequiresApi(api = Build.VERSION_CODES.R)
    private static void watchWindowPosition(Activity activity) {
        View decorView = activity.getWindow().getDecorView();
        // Width, height and status bar visibility of the window last checked,
        // so the position is only read when they change.
        final int[] lastSize = {-1, -1, -1};
        final int[] location = new int[2];

        decorView.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            try {
                WindowInsets insets = decorView.getRootWindowInsets();
                // The keyboard can resize the window on some devices.
                if (insets == null || insets.isVisible(WindowInsets.Type.ime())
                        || activity.isInMultiWindowMode() || activity.isInPictureInPictureMode()
                        || activity.getResources().getConfiguration().orientation
                        != Configuration.ORIENTATION_PORTRAIT) {
                    return;
                }

                final int width = decorView.getWidth();
                final int height = decorView.getHeight();
                final boolean statusBarVisible = insets.isVisible(WindowInsets.Type.statusBars());
                final int statusBar = statusBarVisible ? 1 : 0;
                if ((width == lastSize[0] && height == lastSize[1] && statusBar == lastSize[2])
                        || width == 0 || height == 0) {
                    return;
                }
                lastSize[0] = width;
                lastSize[1] = height;
                lastSize[2] = statusBar;

                decorView.getLocationOnScreen(location);
                final int windowTop = location[1];
                final int windowBottom = windowTop + height;
                final int displayHeight = activity.getWindowManager().getMaximumWindowMetrics().getBounds().height();
                Logger.printDebug(() -> "Window top: " + windowTop + " bottom: " + windowBottom
                        + " display height: " + displayHeight
                        + " status bar inset: " + insets.getInsets(WindowInsets.Type.statusBars()).top
                        + " stable: " + insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars()).top
                        + " cutout: " + insets.getInsets(WindowInsets.Type.displayCutout()).top
                        + " navigation bar inset: " + insets.getInsets(WindowInsets.Type.navigationBars()).bottom);

                // Without the edge to edge top bar the app itself can lay the window out below
                // the status bar (it uses the default display cutout mode on Android 14 and lower)
                // and above the system navigation buttons, so only a window that was drawn edge to edge
                // tells that the device keeps it there. Otherwise the flags would stay off for good
                // after one app start without them.
                final boolean edgeToEdge = Boolean.TRUE.equals(edgeToEdgeTopBarUsed);

                // With the status bar hidden, the window can be laid out below the display cutout instead.
                if (FIX_STATUS_BAR_LAYOUT && statusBarVisible) {
                    final boolean belowStatusBar = windowTop > 0;
                    if ((!belowStatusBar || edgeToEdge)
                            && belowStatusBar != Settings.WINDOW_BELOW_STATUS_BAR.get()) {
                        Logger.printDebug(() -> "Window below status bar: " + belowStatusBar
                                + ", applied from the next app start");
                        Settings.WINDOW_BELOW_STATUS_BAR.save(belowStatusBar);
                    }
                }

                // Only with the system navigation buttons at the bottom of the screen. The navigation bar
                // covering them can change how the window is laid out, so only a window laid out with it
                // tells that the device keeps the window above them.
                if (edgeToEdge && insets.isVisible(WindowInsets.Type.navigationBars())
                        && insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom > 0) {
                    final boolean aboveNavigationBar = windowBottom < displayHeight;
                    if ((!aboveNavigationBar || NavigationBarPatch.isNavigationBarCoveringSystemButtons())
                            && aboveNavigationBar != Settings.WINDOW_ABOVE_NAVIGATION_BAR.get()) {
                        Logger.printDebug(() -> "Window above navigation bar: " + aboveNavigationBar
                                + ", applied from the next app start");
                        Settings.WINDOW_ABOVE_NAVIGATION_BAR.save(aboveNavigationBar);
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "watchWindowPosition failure", ex);
            }
        });
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
        if (!HIDE_STATUS_BAR) {
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
        if (HIDE_STATUS_BAR) {
            view.setVisibility(View.INVISIBLE);
        }
    }

    /**
     * Injection point.
     * <p>
     * Without the edge to edge top bar, the app lays out the top bars with the system window insets
     * of a stable layout, which keep the height of the status bar when it is hidden and leave
     * an empty bar above the top bars.
     * The top inset is replaced by the inset of a display cutout at the top, so the top bars
     * are still laid out below a front camera hole, the same as with the status bar inset.
     */
    public static void setSystemWindowInsetTop(Rect systemWindowInsets, @Nullable View view) {
        if (view == null || !HIDE_STATUS_BAR) {
            return;
        }

        try {
            View decorView = view.getRootView();
            WindowInsets insets = decorView.getRootWindowInsets();
            if (insets == null || isStatusBarVisible(decorView)) {
                return;
            }

            systemWindowInsets.top = isWatchingVideo ? 0 : getDisplayCutoutInsetTop(insets);
        } catch (Exception ex) {
            Logger.printException(() -> "setSystemWindowInsetTop failure", ex);
        }
    }

    private static int getDisplayCutoutInsetTop(WindowInsets insets) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return insets.getInsets(WindowInsets.Type.displayCutout()).top;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            DisplayCutout cutout = insets.getDisplayCutout();
            return cutout == null ? 0 : cutout.getSafeInsetTop();
        }
        // Display cutouts have no API before Android 9.
        return 0;
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
                if (cutout != null && cutout.getSafeInsetTop() > insets.getSystemWindowInsetTop()) {
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
        decorView.setSystemUiVisibility(decorView.getSystemUiVisibility()
                | View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }
}
