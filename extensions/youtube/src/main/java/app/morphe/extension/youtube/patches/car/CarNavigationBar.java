/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4933
 *
 * Adapted from
 * https://codeberg.org/JohnInNL/nl-morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.car;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Handler;
import android.os.Looper;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.settings.Settings;

/**
 * On a car display, hides the navigation bar after a few seconds and shows it again on a swipe up
 * from the bottom edge.
 * <p>
 * The navigation bar is collapsed instead of made gone: the app gives the pages room for the
 * navigation bar from its laid out height, so only a navigation bar with no height gives that room
 * to the pages.
 */
final class CarNavigationBar {
    private static final long ANIMATION_MILLISECONDS = 200;
    /** Height of the area at the bottom of the window where a swipe up shows the navigation bar. */
    private static final float SWIPE_EDGE_DP = 48;
    /** How far a swipe up from the bottom edge must travel to show the navigation bar. */
    private static final float SWIPE_DISTANCE_DP = 24;

    /** The navigation bar of the latest main activity, which follows the moves to and from the car display. */
    private static WeakReference<CarNavigationBar> currentRef = new WeakReference<>(null);

    private final WeakReference<View> containerRef;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideRunnable = () -> {
        try {
            hide();
        } catch (RuntimeException ex) {
            Logger.printException(() -> "hide failure", ex);
        }
    };
    private final long hideDelayMilliseconds;
    private final float density;

    private boolean hidden;
    private int shownHeight;
    private int originalLayoutHeight;
    @Nullable
    private ValueAnimator animator;

    private boolean swipeCandidate;
    private float swipeStartX;
    private float swipeStartY;

    static void install(View bottomBarContainer) {
        Activity activity = findActivity(bottomBarContainer.getContext());
        if (activity == null || activity.getWindow() == null) {
            Logger.printDebug(() -> "Bottom bar container is not in an activity");
            return;
        }
        Window window = activity.getWindow();
        Window.Callback callback = window.getCallback();
        if (callback == null) {
            Logger.printDebug(() -> "Window has no callback");
            return;
        }

        CarNavigationBar hider = new CarNavigationBar(bottomBarContainer);
        if (callback instanceof TouchObservingWindowCallback observing) {
            observing.hider.cancelHide();
            observing.hider = hider;
        } else {
            window.setCallback(new TouchObservingWindowCallback(callback, window, hider));
        }
        currentRef = new WeakReference<>(hider);
        if (CarDisplay.isOnCarDisplay()) {
            hider.scheduleHide();
        }
    }

    /** The activity moved to or from the car display, and is not recreated. */
    static void onCarDisplayChanged() {
        CarNavigationBar hider = currentRef.get();
        if (hider == null) return;

        if (CarDisplay.isOnCarDisplay()) {
            hider.scheduleHide();
        } else {
            // The navigation bar is the one of the phone again.
            hider.cancelHide();
            hider.show();
        }
    }

    private CarNavigationBar(View container) {
        containerRef = new WeakReference<>(container);
        hideDelayMilliseconds = SeekBarPreference.clampToRange(
                Settings.CHANGE_FORM_FACTOR_CAR_DISPLAY_HIDE_NAVIGATION_BAR_DELAY) * 1000L;
        density = container.getResources().getDisplayMetrics().density;
        ViewGroup.LayoutParams params = container.getLayoutParams();
        originalLayoutHeight = params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height;
    }

    @Nullable
    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper wrapper) {
            if (context instanceof Activity activity) return activity;
            context = wrapper.getBaseContext();
        }
        return null;
    }

    private void scheduleHide() {
        handler.removeCallbacks(hideRunnable);
        handler.postDelayed(hideRunnable, hideDelayMilliseconds);
    }

    private void cancelHide() {
        handler.removeCallbacks(hideRunnable);
    }

    private void hide() {
        View container = containerRef.get();
        if (container == null || hidden || !CarDisplay.isOnCarDisplay()) return;

        final int height = container.getHeight();
        if (container.getVisibility() != View.VISIBLE || height <= 0) {
            // Not laid out yet, or hidden by the app such as in the player.
            scheduleHide();
            return;
        }
        ViewGroup.LayoutParams params = container.getLayoutParams();
        if (params == null) return;
        if (params.height != 0) originalLayoutHeight = params.height;

        shownHeight = height;
        hidden = true;
        animateHeight(container, height, 0, null);
    }

    private void show() {
        View container = containerRef.get();
        if (container == null || !hidden) return;

        hidden = false;
        animateHeight(container, 0, shownHeight, () -> {
            ViewGroup.LayoutParams params = container.getLayoutParams();
            if (params != null && !hidden) {
                params.height = originalLayoutHeight;
                container.setLayoutParams(params);
            }
        });
    }

    private void animateHeight(View container, int from, int to, @Nullable Runnable onEnd) {
        if (animator != null) animator.cancel();

        ValueAnimator heightAnimator = ValueAnimator.ofInt(from, to);
        heightAnimator.setDuration(ANIMATION_MILLISECONDS);
        heightAnimator.addUpdateListener(animation -> {
            ViewGroup.LayoutParams params = container.getLayoutParams();
            if (params == null) return;
            params.height = (int) animation.getAnimatedValue();
            container.setLayoutParams(params);
        });
        heightAnimator.addListener(new AnimatorListenerAdapter() {
            private boolean canceled;

            @Override
            public void onAnimationCancel(Animator animation) {
                canceled = true;
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator == animation) animator = null;
                if (!canceled && onEnd != null) onEnd.run();
            }
        });
        animator = heightAnimator;
        heightAnimator.start();
    }

    /** Observes the touches of the window, without consuming them. */
    void onTouchEvent(MotionEvent event, Window window) {
        try {
            if (!CarDisplay.isOnCarDisplay()) return;
            View container = containerRef.get();
            if (container == null) return;

            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    swipeCandidate = false;
                    if (hidden) {
                        final float edge = SWIPE_EDGE_DP * density;
                        if (event.getY() >= window.getDecorView().getHeight() - edge) {
                            swipeCandidate = true;
                            swipeStartX = event.getX();
                            swipeStartY = event.getY();
                        }
                    } else if (isInside(container, event)) {
                        // Using the navigation bar keeps it.
                        scheduleHide();
                    }
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (!swipeCandidate) return;
                    final float up = swipeStartY - event.getY();
                    final float sideways = Math.abs(event.getX() - swipeStartX);
                    if (isSwipeUp(up, sideways, density)) {
                        swipeCandidate = false;
                        show();
                        scheduleHide();
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    swipeCandidate = false;
                    if (!hidden && isInside(container, event)) scheduleHide();
                }
                default -> {
                }
            }
        } catch (RuntimeException ex) {
            Logger.printException(() -> "onTouchEvent failure", ex);
        }
    }

    /** Whether a movement from the bottom edge is a swipe up, in pixels. */
    static boolean isSwipeUp(float up, float sideways, float density) {
        return up >= SWIPE_DISTANCE_DP * density && up > sideways;
    }

    private static boolean isInside(View view, MotionEvent event) {
        int[] location = new int[2];
        view.getLocationInWindow(location);
        final float x = event.getX();
        final float y = event.getY();
        return x >= location[0] && x < location[0] + view.getWidth()
                && y >= location[1] && y < location[1] + view.getHeight();
    }

    /**
     * Passes every call to the original callback of the window, which can already be a wrapper of the
     * activity such as the one of AppCompat, and shows the touches to the navigation bar first.
     */
    private static final class TouchObservingWindowCallback implements Window.Callback {
        private final Window.Callback original;
        private final WeakReference<Window> windowRef;
        private CarNavigationBar hider;

        TouchObservingWindowCallback(Window.Callback original, Window window, CarNavigationBar hider) {
            this.original = original;
            this.windowRef = new WeakReference<>(window);
            this.hider = hider;
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            Window window = windowRef.get();
            if (window != null) hider.onTouchEvent(event, window);
            return original.dispatchTouchEvent(event);
        }

        @Override public boolean dispatchKeyEvent(KeyEvent event) { return original.dispatchKeyEvent(event); }
        @Override public boolean dispatchKeyShortcutEvent(KeyEvent event) { return original.dispatchKeyShortcutEvent(event); }
        @Override public boolean dispatchTrackballEvent(MotionEvent event) { return original.dispatchTrackballEvent(event); }
        @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return original.dispatchGenericMotionEvent(event); }
        @Override public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) { return original.dispatchPopulateAccessibilityEvent(event); }
        @Override public View onCreatePanelView(int featureId) { return original.onCreatePanelView(featureId); }
        @Override public boolean onCreatePanelMenu(int featureId, Menu menu) { return original.onCreatePanelMenu(featureId, menu); }
        @Override public boolean onPreparePanel(int featureId, View view, Menu menu) { return original.onPreparePanel(featureId, view, menu); }
        @Override public boolean onMenuOpened(int featureId, Menu menu) { return original.onMenuOpened(featureId, menu); }
        @Override public boolean onMenuItemSelected(int featureId, MenuItem item) { return original.onMenuItemSelected(featureId, item); }
        @Override public void onWindowAttributesChanged(WindowManager.LayoutParams attrs) { original.onWindowAttributesChanged(attrs); }
        @Override public void onContentChanged() { original.onContentChanged(); }
        @Override public void onWindowFocusChanged(boolean hasFocus) { original.onWindowFocusChanged(hasFocus); }
        @Override public void onAttachedToWindow() { original.onAttachedToWindow(); }
        @Override public void onDetachedFromWindow() { original.onDetachedFromWindow(); }
        @Override public void onPanelClosed(int featureId, Menu menu) { original.onPanelClosed(featureId, menu); }
        @Override public boolean onSearchRequested() { return original.onSearchRequested(); }
        @Override public boolean onSearchRequested(SearchEvent searchEvent) { return original.onSearchRequested(searchEvent); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) { return original.onWindowStartingActionMode(callback); }
        @Override public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) { return original.onWindowStartingActionMode(callback, type); }
        @Override public void onActionModeStarted(ActionMode mode) { original.onActionModeStarted(mode); }
        @Override public void onActionModeFinished(ActionMode mode) { original.onActionModeFinished(mode); }
        @Override public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, Menu menu, int deviceId) { original.onProvideKeyboardShortcuts(data, menu, deviceId); }
        @Override public void onPointerCaptureChanged(boolean hasCapture) { original.onPointerCaptureChanged(hasCapture); }
    }
}
