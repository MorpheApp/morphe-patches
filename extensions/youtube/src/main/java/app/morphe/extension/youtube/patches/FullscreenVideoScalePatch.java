/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2616
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowMetrics;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import kotlin.Unit;

/**
 * Maps the playing video onto the full display with a compositor transform.
 * <p>
 * YouTube's watch player ({@code PlayerView}) is a 16:9 {@code ViewGroup}. The native
 * renderer letterboxes inside the surface when that view is already fullscreen, so
 * resizing the surface cannot stretch. Scale/translate the content rectangle onto the
 * current window instead.
 * <p>
 * Stretch fills the screen for any source aspect ratio by distorting.
 * Zoom covers the screen uniformly (crops). Default leaves YouTube letterboxing.
 */
@SuppressWarnings("unused")
public class FullscreenVideoScalePatch {

    public enum VideoScaleMode {
        DEFAULT("morphe_fullscreen_video_scale_fit", "morphe_fullscreen_video_scale_entry_default"),
        STRETCH("morphe_fullscreen_video_scale_stretch", "morphe_fullscreen_video_scale_entry_stretch"),
        ZOOM("morphe_fullscreen_video_scale_zoom", "morphe_fullscreen_video_scale_entry_zoom");

        public final String iconBaseName;
        public final String toastMessageKey;

        VideoScaleMode(String iconBaseName, String iconResourceName) {
            this.iconBaseName = iconBaseName;
            this.toastMessageKey = iconResourceName;
        }
    }

    /**
     * Matches YouTube's hardcoded PlayerView aspect ({@code 1.777f}).
     */
    private static final float DEFAULT_VIDEO_ASPECT = 16f / 9f;

    private static WeakReference<View> overlayRef = new WeakReference<>(null);
    private static WeakReference<View> playerViewRef = new WeakReference<>(null);
    private static WeakReference<View> scaledViewRef = new WeakReference<>(null);

    private static volatile float videoAspectRatio = DEFAULT_VIDEO_ASPECT;

    @Nullable
    private static ViewTreeObserver.OnPreDrawListener preDrawListener;
    private static WeakReference<View> preDrawHostRef = new WeakReference<>(null);
    private static boolean preDrawAttached;

    static {
        PlayerType.getOnChange().addObserver((PlayerType type) -> {
            applyScale();
            return Unit.INSTANCE;
        });
    }

    /**
     * Injection point. Called with {@code YouTubePlayerOverlaysLayout}.
     */
    public static void attachPlayerOverlay(View overlay) {
        try {
            if (overlay == null) {
                return;
            }
            View previous = overlayRef.get();
            if (previous != overlay) {
                overlayRef = new WeakReference<>(overlay);
                overlay.addOnLayoutChangeListener(
                        (v, l, t, r, b, ol, ot, or, ob) -> applyScale()
                );
            }
            applyScale();
        } catch (Exception ex) {
            Logger.printException(() -> "attachPlayerOverlay failure", ex);
        }
    }

    /**
     * Injection point. Called at the end of {@code YouTubePlayerViewNotForReflection}
     * / 16:9 {@code PlayerView} {@code onLayout}.
     */
    public static void onPlayerViewLayout(View playerView) {
        try {
            if (playerView == null) {
                return;
            }
            playerViewRef = new WeakReference<>(playerView);
            applyScaleOnMainThread();
        } catch (Exception ex) {
            Logger.printException(() -> "onPlayerViewLayout failure", ex);
        }
    }

    /**
     * Records the encoded video size so Stretch/Zoom can map non-16:9 content.
     */
    public static void setVideoSize(int width, int height) {
        if (width > 0 && height > 0) {
            videoAspectRatio = width / (float) height;
        }
    }

    public static void applyScale() {
        Utils.runOnMainThread(FullscreenVideoScalePatch::applyScaleOnMainThread);
    }

    private static void applyScaleOnMainThread() {
        try {
            View target = resolvePlayerView();

            if (!shouldScale() || Settings.FULLSCREEN_VIDEO_SCALE.get() == VideoScaleMode.DEFAULT) {
                detachPreDraw();
                restoreDefaultTransform(target);
                return;
            }

            if (target == null) {
                Logger.printDebug(() -> "Stretch: no player view found");
                return;
            }

            View previous = scaledViewRef.get();
            if (previous != null && previous != target) {
                resetViewTransform(previous);
            }
            scaledViewRef = new WeakReference<>(target);

            // Scale the player view itself so the SurfaceView hardware layer follows
            // the parent transform. Scaling only the SurfaceView is ignored on some OEMs.
            disableClipping(target);
            mapContentToDisplay(target);
            attachPreDraw(target);
        } catch (Exception ex) {
            Logger.printException(() -> "applyScale failure", ex);
        }
    }

    /**
     * Scale {@code view} so the letterboxed video content fills the current window.
     * Does not change layout bounds (that would make the native player letterbox again).
     */
    private static void mapContentToDisplay(View view) {
        int[] display = getDisplaySize();
        final int displayW = display[0];
        final int displayH = display[1];
        final int viewW = view.getWidth();
        final int viewH = view.getHeight();
        if (displayW <= 0 || displayH <= 0 || viewW <= 0 || viewH <= 0) {
            return;
        }

        final float videoAR = getVideoAspectRatio(view, viewW, viewH);
        final float viewAR = viewW / (float) viewH;

        float contentW;
        float contentH;
        if (videoAR >= viewAR) {
            contentW = viewW;
            contentH = viewW / videoAR;
        } else {
            contentH = viewH;
            contentW = viewH * videoAR;
        }
        if (contentW < 1f || contentH < 1f) {
            return;
        }

        final float contentLeft = (viewW - contentW) / 2f;
        final float contentTop = (viewH - contentH) / 2f;

        // Location must ignore the transform we apply; clear it first (still before draw).
        view.setTranslationX(0f);
        view.setTranslationY(0f);
        view.setScaleX(1f);
        view.setScaleY(1f);
        int[] loc = new int[2];
        view.getLocationOnScreen(loc);

        VideoScaleMode mode = Settings.FULLSCREEN_VIDEO_SCALE.get();
        if (mode == VideoScaleMode.ZOOM) {
            final float scale = Math.max(displayW / contentW, displayH / contentH);
            view.setPivotX(contentLeft + contentW / 2f);
            view.setPivotY(contentTop + contentH / 2f);
            view.setScaleX(scale);
            view.setScaleY(scale);
            final float contentCenterX = loc[0] + contentLeft + contentW / 2f;
            final float contentCenterY = loc[1] + contentTop + contentH / 2f;
            view.setTranslationX(displayW / 2f - contentCenterX);
            view.setTranslationY(displayH / 2f - contentCenterY);
            return;
        }

        view.setPivotX(contentLeft);
        view.setPivotY(contentTop);
        view.setScaleX(displayW / contentW);
        view.setScaleY(displayH / contentH);
        view.setTranslationX(-(loc[0] + contentLeft));
        view.setTranslationY(-(loc[1] + contentTop));
    }

    private static float getVideoAspectRatio(View view, int viewW, int viewH) {
        View child = findDirectVideoSurface(view);
        if (child != null && child != view && child.getWidth() > 8 && child.getHeight() > 8) {
            float childAR = child.getWidth() / (float) child.getHeight();
            float parentAR = viewW / (float) viewH;
            if (Math.abs(childAR - parentAR) > 0.02f) {
                return childAR;
            }
        }
        float recorded = videoAspectRatio;
        if (recorded > 0.15f && recorded < 8f) {
            return recorded;
        }
        return DEFAULT_VIDEO_ASPECT;
    }

    private static void disableClipping(View view) {
        View current = view;
        for (int i = 0; i < 16 && current != null; i++) {
            current.setClipToOutline(false);
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

    private static int[] getDisplaySize() {
        int[] size = new int[]{0, 0};
        Activity activity = Utils.getActivity();

        if (activity != null) {
            Window window = activity.getWindow();
            if (window != null) {
                View decor = window.getDecorView();
                if (decor.getWidth() > 0 && decor.getHeight() > 0) {
                    size[0] = decor.getWidth();
                    size[1] = decor.getHeight();
                    return size;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    WindowMetrics metrics = activity.getWindowManager().getCurrentWindowMetrics();
                    size[0] = metrics.getBounds().width();
                    size[1] = metrics.getBounds().height();
                    return size;
                }
                DisplayMetrics displayMetrics = activity.getResources().getDisplayMetrics();
                size[0] = displayMetrics.widthPixels;
                size[1] = displayMetrics.heightPixels;
                return size;
            }
        }

        View playerView = playerViewRef.get();
        if (playerView != null) {
            View root = playerView.getRootView();
            size[0] = root.getWidth();
            size[1] = root.getHeight();
            return size;
        }
        View overlay = overlayRef.get();
        if (overlay != null) {
            View root = overlay.getRootView();
            size[0] = root.getWidth();
            size[1] = root.getHeight();
        }
        return size;
    }

    private static void restoreDefaultTransform(@Nullable View playerView) {
        resetViewTransform(playerView);
        View previous = scaledViewRef.get();
        if (previous != playerView) {
            resetViewTransform(previous);
        }
        scaledViewRef = new WeakReference<>(null);
    }

    private static void resetViewTransform(@Nullable View view) {
        if (view == null) {
            return;
        }
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(view.getHeight() / 2f);
        view.setScaleX(1);
        view.setScaleY(1);
        view.setTranslationX(0);
        view.setTranslationY(0);
    }

    private static void attachPreDraw(View host) {
        View preDrawHost = preDrawHostRef.get();
        if (preDrawAttached && preDrawHost == host) {
            return;
        }
        detachPreDraw();
        ViewTreeObserver observer = host.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) {
            return;
        }

        preDrawListener = () -> {
            try {
                if (!shouldScale() || Settings.FULLSCREEN_VIDEO_SCALE.get() == VideoScaleMode.DEFAULT) {
                    return true;
                }
                View target = scaledViewRef.get();
                if (target == null || !target.isAttachedToWindow()) {
                    target = resolvePlayerView();
                    if (target != null) {
                        scaledViewRef = new WeakReference<>(target);
                    }
                }
                if (target != null) {
                    disableClipping(target);
                    mapContentToDisplay(target);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "stretch pre-draw failure", ex);
            }
            return true;
        };
        observer.addOnPreDrawListener(preDrawListener);
        preDrawHostRef = new WeakReference<>(null);
        preDrawAttached = true;
    }

    private static void detachPreDraw() {
        if (!preDrawAttached) {
            return;
        }
        ViewTreeObserver.OnPreDrawListener listener = preDrawListener;
        View preDrawHost = preDrawHostRef.get();
        preDrawListener = null;
        preDrawHostRef = new WeakReference<>(null);
        preDrawAttached = false;
        if (listener == null || preDrawHost == null) {
            return;
        }
        ViewTreeObserver observer = preDrawHost.getViewTreeObserver();
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(listener);
        }
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean shouldScale() {
        PlayerType type = PlayerType.getCurrent();
        if (type == PlayerType.WATCH_WHILE_FULLSCREEN
                || type == PlayerType.WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN) {
            return true;
        }
        return type == PlayerType.WATCH_WHILE_MAXIMIZED && Utils.isLandscapeOrientation();
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
        return null;
    }

    @Nullable
    private static View findPlayerView(View view, int depth) {
        if (depth > 16 || view == null) {
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

        for (int i = 0, getChildCount = group.getChildCount(); i < getChildCount; i++) {
            View found = findPlayerView(group.getChildAt(i), depth + 1);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    @Nullable
    private static View findDirectVideoSurface(@Nullable View container) {
        if (isUsableVideoView(container)) {
            return container;
        }

        if (container instanceof ViewGroup group) {
            for (int i = 0, childCount = group.getChildCount(); i < childCount; i++) {
                View child = group.getChildAt(i);
                if (isUsableVideoView(child)) {
                    return child;
                }
            }
        }
        return findVideoViewField(container);
    }

    @Nullable
    private static View findVideoViewField(@Nullable View playerView) {
        if (playerView == null) {
            return null;
        }

        Class<?> cls = playerView.getClass();
        for (int depth = 0; depth < 8 && cls != null && cls != ViewGroup.class && cls != View.class; depth++) {
            for (Field field : cls.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                if (!View.class.isAssignableFrom(field.getType())) {
                    continue;
                }

                try {
                    field.setAccessible(true);
                    Object value = field.get(playerView);
                    if (value instanceof View candidate && candidate != playerView
                            && isUsableVideoView(candidate)) {
                        return candidate;
                    }
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Ignoring exception", ex); // Ignore exception?
                    // Keep looking.
                }
            }
            cls = cls.getSuperclass();
        }

        return null;
    }

    private static boolean isUsableVideoView(@Nullable View view) {
        return view instanceof TextureView || view instanceof SurfaceView;
    }
}
