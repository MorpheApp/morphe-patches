/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.view.ScaleGestureDetector;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableFullscreenGesturesPatch {

    /**
     * Injection point.
     */
    public static boolean disableFullscreenGestures(String nextGestureType) {
        Logger.printDebug(() -> "The next player gesture will be: " + nextGestureType);
        return ("MAXIMIZED_PULLED_UP".equals(nextGestureType) &&
                Settings.DISABLE_FULLSCREEN_PULLED_UP_GESTURE.get()) ||
                ("MAXIMIZED_TO_FULLSCREEN_SLIDING".equals(nextGestureType)
                        && Settings.DISABLE_FULLSCREEN_SLIDING_GESTURE.get()) ||
                ("FULLSCREEN_DRAGGED_DOWN".equals(nextGestureType)
                        && Settings.DISABLE_FULLSCREEN_DRAGGED_DOWN_GESTURE.get());
    }

    /**
     * Injection point. Pre-21.36 zoom-flag override.
     */
    public static boolean disableBrokenFullscreenZoomFlag(boolean original) {
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean disableZoomGesture() {
        return Settings.DISABLE_FULLSCREEN_ZOOM_GESTURE.get();
    }

    /**
     * 21.36+ PlayerView is a 16:9 box. Native pinch scales inside that box (Nx HUD)
     * and never eats the window letterbox. Scale the player view while pinched.
     * At scale 1 this does not write scaleX/Y so Fullscreen video scale can own
     * the view at rest.
     */
    private static WeakReference<View> playerViewRef = new WeakReference<>(null);
    private static WeakReference<View> overlayRef = new WeakReference<>(null);
    private static volatile float pinchScale = 1f;
    private static boolean pinchOwningView;
    private static final float MIN_PINCH_SCALE = 1f;
    private static final float MAX_PINCH_SCALE = 8f;

    @Nullable
    private static ViewTreeObserver.OnPreDrawListener preDrawListener;
    private static WeakReference<View> preDrawHostRef = new WeakReference<>(null);

    /**
     * Injection point. {@code YouTubePlayerOverlaysLayout} constructor.
     */
    public static void attachPlayerOverlay(View overlay) {
        if (overlay == null) {
            return;
        }
        overlayRef = new WeakReference<>(overlay);
    }

    /**
     * Injection point. {@code YouTubePlayerViewNotForReflection.onLayout}.
     */
    public static void onPlayerViewLayout(View playerView) {
        if (playerView == null) {
            return;
        }
        playerViewRef = new WeakReference<>(playerView);
        if (playerView.getHeight() > playerView.getWidth() + 8) {
            pinchScale = MIN_PINCH_SCALE;
        }
        applyPinchTransform(playerView);
    }

    /**
     * Current pinch factor (>= 1). Fullscreen video scale reads this so Stretch/Zoom
     * stay applied while pinched.
     */
    public static float getPinchScale() {
        return pinchScale < MIN_PINCH_SCALE ? MIN_PINCH_SCALE : pinchScale;
    }

    /**
     * Injection point. {@code ScaleGestureDetector.OnScaleGestureListener.onScale}.
     */
    public static void onPinchScale(ScaleGestureDetector detector) {
        if (detector == null || disableZoomGesture()) {
            return;
        }
        pinchScale *= detector.getScaleFactor();
        if (pinchScale < MIN_PINCH_SCALE) {
            pinchScale = MIN_PINCH_SCALE;
        } else if (pinchScale > MAX_PINCH_SCALE) {
            pinchScale = MAX_PINCH_SCALE;
        }
        Logger.printDebug(() -> "pinch scale=" + pinchScale);
        applyPinchTransform(resolvePlayerView());
    }

    /**
     * Injection point. {@code onScaleEnd}.
     */
    public static void onPinchScaleEnd(ScaleGestureDetector detector) {
        applyPinchTransform(resolvePlayerView());
    }

    private static void applyPinchTransform(@Nullable View preferred) {
        if (fullscreenScaleOwnsTransform()) {
            pinchOwningView = false;
            detachPreDraw();
            FullscreenVideoScalePatch.applyScale();
            return;
        }
        View view = preferred != null ? preferred : resolvePlayerView();
        if (pinchScale <= MIN_PINCH_SCALE + 0.01f) {
            if (pinchOwningView && view != null) {
                applyScaleToViewAndSurfaces(view, 1f);
            }
            pinchOwningView = false;
            detachPreDraw();
            return;
        }
        if (view == null) {
            Logger.printDebug(() -> "pinch: no player view, scale=" + pinchScale);
            return;
        }
        pinchOwningView = true;
        disableClipping(view);
        applyScaleToViewAndSurfaces(view, pinchScale);
        attachPreDraw(view);
    }

    private static boolean fullscreenScaleOwnsTransform() {
        FullscreenVideoScalePatch.VideoScaleMode mode = Settings.FULLSCREEN_VIDEO_SCALE.get();
        return mode == FullscreenVideoScalePatch.VideoScaleMode.STRETCH
                || mode == FullscreenVideoScalePatch.VideoScaleMode.ZOOM;
    }

    private static void applyScaleToViewAndSurfaces(View view, float scale) {
        setViewScale(view, scale);
        if (view instanceof ViewGroup group) {
            for (int i = 0, n = group.getChildCount(); i < n; i++) {
                View child = group.getChildAt(i);
                if (child instanceof SurfaceView || child instanceof TextureView) {
                    setViewScale(child, scale);
                }
            }
        }
    }

    private static void setViewScale(View view, float scale) {
        final int w = view.getWidth();
        final int h = view.getHeight();
        if (w > 0 && h > 0) {
            view.setPivotX(w / 2f);
            view.setPivotY(h / 2f);
        }
        view.setClipBounds(null);
        view.setScaleX(scale);
        view.setScaleY(scale);
    }

    private static void attachPreDraw(@Nullable View host) {
        if (host == null) {
            host = resolvePlayerView();
        }
        if (host == null) {
            return;
        }
        View currentHost = preDrawHostRef.get();
        if (preDrawListener != null && currentHost == host) {
            return;
        }
        detachPreDraw();
        ViewTreeObserver observer = host.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            return;
        }
        preDrawListener = () -> {
            try {
                applyPinchTransform(resolvePlayerView());
            } catch (Exception ex) {
                Logger.printException(() -> "pinch pre-draw failure", ex);
            }
            return true;
        };
        observer.addOnPreDrawListener(preDrawListener);
        preDrawHostRef = new WeakReference<>(host);
    }

    private static void detachPreDraw() {
        ViewTreeObserver.OnPreDrawListener listener = preDrawListener;
        View host = preDrawHostRef.get();
        preDrawListener = null;
        preDrawHostRef = new WeakReference<>(null);
        if (listener == null || host == null) {
            return;
        }
        ViewTreeObserver observer = host.getViewTreeObserver();
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(listener);
        }
    }

    @Nullable
    private static View resolvePlayerView() {
        View cached = playerViewRef.get();
        if (cached != null && cached.isAttachedToWindow() && cached.getWidth() > 0) {
            return cached;
        }
        Activity activity = Utils.getActivity();
        if (activity != null) {
            Window window = activity.getWindow();
            if (window != null) {
                View found = findPlayerView(window.getDecorView(), 0);
                if (found != null) {
                    playerViewRef = new WeakReference<>(found);
                    return found;
                }
            }
        }
        View overlay = overlayRef.get();
        if (overlay != null) {
            View found = findPlayerView(overlay.getRootView(), 0);
            if (found != null) {
                playerViewRef = new WeakReference<>(found);
                return found;
            }
        }
        return cached;
    }

    @Nullable
    private static View findPlayerView(View view, int depth) {
        if (depth > 18 || view == null) {
            return null;
        }
        String name = view.getClass().getName();
        if (name.endsWith("YouTubePlayerViewNotForReflection")
                || name.endsWith("player.ui.PlayerView")) {
            return view;
        }
        if (!(view instanceof ViewGroup group)) {
            return null;
        }
        for (int i = 0, n = group.getChildCount(); i < n; i++) {
            View found = findPlayerView(group.getChildAt(i), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static void disableClipping(View view) {
        View current = view;
        for (int i = 0; i < 16 && current != null; i++) {
            current.setClipToOutline(false);
            current.setClipBounds(null);
            if (current instanceof ViewGroup group) {
                group.setClipChildren(false);
                group.setClipToPadding(false);
            }
            if (!(current.getParent() instanceof View parent)) {
                break;
            }
            current = parent;
        }
    }
}
