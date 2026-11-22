/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.KeyboardShortcutGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.SearchEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

/**
 * Sees every touch of a window before the views do, and can keep touches from them.
 * Everything else goes to the callback the window had before.
 */
final class WindowTouchInterceptor implements Window.Callback {

    interface TouchHandler {
        /**
         * @return True to keep the event from the views of the window.
         */
        boolean onWindowTouchEvent(WindowTouchInterceptor source, MotionEvent event);
    }

    private final Window.Callback wrapped;
    private final TouchHandler handler;

    private WindowTouchInterceptor(Window.Callback wrapped, TouchHandler handler) {
        this.wrapped = wrapped;
        this.handler = handler;
    }

    /**
     * @return The interceptor that is now the callback of the window.
     */
    static WindowTouchInterceptor install(Window window, TouchHandler handler) {
        final WindowTouchInterceptor interceptor = new WindowTouchInterceptor(window.getCallback(), handler);
        window.setCallback(interceptor);
        return interceptor;
    }

    /**
     * Sends an event to the views of the window without asking the handler.
     */
    void dispatchToViews(MotionEvent event) {
        wrapped.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (handler.onWindowTouchEvent(this, event)) {
            return true;
        }
        return wrapped.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        return wrapped.dispatchKeyEvent(event);
    }

    @Override
    public boolean dispatchKeyShortcutEvent(KeyEvent event) {
        return wrapped.dispatchKeyShortcutEvent(event);
    }

    @Override
    public boolean dispatchTrackballEvent(MotionEvent event) {
        return wrapped.dispatchTrackballEvent(event);
    }

    @Override
    public boolean dispatchGenericMotionEvent(MotionEvent event) {
        return wrapped.dispatchGenericMotionEvent(event);
    }

    @Override
    public boolean dispatchPopulateAccessibilityEvent(AccessibilityEvent event) {
        return wrapped.dispatchPopulateAccessibilityEvent(event);
    }

    @Nullable
    @Override
    public View onCreatePanelView(int featureId) {
        return wrapped.onCreatePanelView(featureId);
    }

    @Override
    public boolean onCreatePanelMenu(int featureId, @NonNull Menu menu) {
        return wrapped.onCreatePanelMenu(featureId, menu);
    }

    @Override
    public boolean onPreparePanel(int featureId, @Nullable View view, @NonNull Menu menu) {
        return wrapped.onPreparePanel(featureId, view, menu);
    }

    @Override
    public boolean onMenuOpened(int featureId, @NonNull Menu menu) {
        return wrapped.onMenuOpened(featureId, menu);
    }

    @Override
    public boolean onMenuItemSelected(int featureId, @NonNull MenuItem item) {
        return wrapped.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onWindowAttributesChanged(WindowManager.LayoutParams attributes) {
        wrapped.onWindowAttributesChanged(attributes);
    }

    @Override
    public void onContentChanged() {
        wrapped.onContentChanged();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        wrapped.onWindowFocusChanged(hasFocus);
    }

    @Override
    public void onAttachedToWindow() {
        wrapped.onAttachedToWindow();
    }

    @Override
    public void onDetachedFromWindow() {
        wrapped.onDetachedFromWindow();
    }

    @Override
    public void onPanelClosed(int featureId, @NonNull Menu menu) {
        wrapped.onPanelClosed(featureId, menu);
    }

    @Override
    public boolean onSearchRequested() {
        return wrapped.onSearchRequested();
    }

    @Override
    public boolean onSearchRequested(SearchEvent searchEvent) {
        return wrapped.onSearchRequested(searchEvent);
    }

    @Nullable
    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback) {
        return wrapped.onWindowStartingActionMode(callback);
    }

    @Nullable
    @Override
    public ActionMode onWindowStartingActionMode(ActionMode.Callback callback, int type) {
        return wrapped.onWindowStartingActionMode(callback, type);
    }

    @Override
    public void onActionModeStarted(ActionMode mode) {
        wrapped.onActionModeStarted(mode);
    }

    @Override
    public void onActionModeFinished(ActionMode mode) {
        wrapped.onActionModeFinished(mode);
    }

    @Override
    public void onProvideKeyboardShortcuts(List<KeyboardShortcutGroup> data, @Nullable Menu menu, int deviceId) {
        wrapped.onProvideKeyboardShortcuts(data, menu, deviceId);
    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {
        wrapped.onPointerCaptureChanged(hasCapture);
    }
}
