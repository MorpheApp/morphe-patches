/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2616
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowMetrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.patches.VideoFormat.FormatInterface;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;

/**
 * Maps the playing video onto the full display with a compositor transform.
 * <p>
 * YouTube's watch player ({@code PlayerView}) is a 16:9 {@code ViewGroup}. The native
 * renderer letterboxes inside the surface when that view is already fullscreen, so
 * resizing the surface cannot stretch. Scale/translate the content rectangle onto the
 * current window instead.
 * <p>
 * Stretch fills the screen for any source aspect ratio by distorting.
 * Zoom covers the screen uniformly (crops). Default leaves YouTube letterboxing,
 * which the vertical alignment can move to the top or bottom of the window.
 */
@SuppressWarnings("unused")
public class FullscreenVideoScalePatch {

    /**
     * Added to {@code PlayerView}, whose video view field has an obfuscated name.
     */
    public interface PlayerViewInterface {
        // Method is added during patching.
        @Nullable
        View patch_getVideoView();
    }

    public enum VideoScaleMode {
        DEFAULT("morphe_fullscreen_video_scale_fit"),
        STRETCH("morphe_fullscreen_video_scale_stretch"),
        ZOOM("morphe_fullscreen_video_scale_zoom");

        public final String iconBaseName;

        VideoScaleMode(String iconBaseName) {
            this.iconBaseName = iconBaseName;
        }
    }

    /**
     * Vertical position of the video in the fullscreen window, if the video is not scaled.
     */
    public enum VideoVerticalAlignment {
        TOP("morphe_fullscreen_video_alignment_top", -1),
        CENTER("morphe_fullscreen_video_alignment_center", 0),
        BOTTOM("morphe_fullscreen_video_alignment_bottom", 1);

        public final String iconBaseName;

        /**
         * Vertical direction to move the video from the center.
         */
        final int direction;

        VideoVerticalAlignment(String iconBaseName, int direction) {
            this.iconBaseName = iconBaseName;
            this.direction = direction;
        }
    }

    /**
     * Stretch and Zoom leave no empty space to align the video in.
     */
    public static final class VideoAlignmentAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.FULLSCREEN_VIDEO_SCALE.get() == VideoScaleMode.DEFAULT;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.FULLSCREEN_VIDEO_SCALE);
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

    /**
     * Clip flags disabled while scaled, restored when leaving fullscreen.
     */
    @Nullable
    private static List<SavedClipState> savedClipStates;

    static {
        PlayerType.getOnChange().addObserver((PlayerType type) -> applyScale());
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
     * Injection point.
     * Records the encoded video size so Stretch/Zoom can map non-16:9 content.
     */
    public static List<FormatInterface> setVideoAspectRatio(@NonNull String videoId, @NonNull List<FormatInterface> adaptiveFormats) {
        if (ShortsPlayerState.isOpen()) {
            Logger.printDebug(() -> "Ignoring shorts video aspect ratio, videoId: " + videoId);
            return adaptiveFormats;
        }

        for (FormatInterface format : adaptiveFormats) {
            String mimeType = format.patch_getMimeType();
            if (mimeType != null && mimeType.contains("video")) {
                final int width = format.patch_getWidth();
                final int height = format.patch_getHeight();
                if (width > 16 && width < 8192 && height > 16 && height < 8192) {
                    final float aspectRation = width / (float) height;
                    videoAspectRatio = aspectRation;
                    Logger.printDebug(() -> "Video aspect ratio: " + aspectRation + ", videoId: " + videoId);
                    break;
                }
            }
        }
        return adaptiveFormats;
    }

    public static void applyScale() {
        CaptionViewportPatch.onPresentationChanged();
        Utils.runOnMainThread(FullscreenVideoScalePatch::applyScaleOnMainThread);
    }

    private static void applyScaleOnMainThread() {
        try {
            View target = resolvePlayerView();

            if (!shouldApplyTransform()) {
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
     * Scale or move {@code view} so the letterboxed video content fills or aligns to the current window.
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

        if (!(view.getParent() instanceof View parent)) {
            return;
        }
        // The parent location does not include the transform applied to the view itself.
        int[] location = new int[2];
        parent.getLocationInWindow(location);
        // The content is centered in the view.
        final float centerX = location[0] + view.getLeft() - parent.getScrollX() + viewW / 2f;
        final float centerY = location[1] + view.getTop() - parent.getScrollY() + viewH / 2f;

        float scaleX = DisableFullscreenGesturesPatch.getPinchScale();
        float scaleY = scaleX;
        float translationX = displayW / 2f - centerX;
        float translationY = displayH / 2f - centerY;

        switch (Settings.FULLSCREEN_VIDEO_SCALE.get()) {
            case DEFAULT -> {
                translationX = 0;
                translationY = Settings.FULLSCREEN_VIDEO_ALIGNMENT.get().direction
                        * getAlignmentSpace(view, centerY, displayH, videoAR, scaleY);
            }
            case STRETCH -> {
                scaleX *= displayW / contentW;
                scaleY *= displayH / contentH;
            }
            case ZOOM -> {
                final float fillScale = Math.max(displayW / contentW, displayH / contentH);
                scaleX *= fillScale;
                scaleY *= fillScale;
            }
        }

        view.setPivotX(viewW / 2f);
        view.setPivotY(viewH / 2f);
        view.setScaleX(scaleX);
        view.setScaleY(scaleY);
        view.setTranslationX(translationX);
        view.setTranslationY(translationY);
    }

    /**
     * Uses the smaller of the space above and below the video. An open panel, such as the comments
     * in portrait, makes the app move the video up, and the video must not move under the panel.
     *
     * @param viewCenterY Center of the player view in the window.
     */
    private static float getAlignmentSpace(View playerView, float viewCenterY, int displayH,
                                           float videoAR, float scale) {
        float contentCenterY = viewCenterY;
        float contentH = Math.min(playerView.getHeight(), playerView.getWidth() / videoAR);

        View videoView = playerView instanceof PlayerViewInterface player
                ? player.patch_getVideoView()
                : null;
        // The app lays out the video view inside the player view.
        if (videoView != null && videoView != playerView && videoView.getHeight() > 0
                && playerView instanceof ViewGroup group) {
            Rect bounds = new Rect(0, 0, videoView.getWidth(), videoView.getHeight());
            group.offsetDescendantRectToMyCoords(videoView, bounds);
            contentCenterY += (bounds.exactCenterY() - playerView.getHeight() / 2f) * scale;
            contentH = Math.min(bounds.height(), bounds.width() / videoAR);
        }

        final float halfHeight = contentH * scale / 2f;
        final float spaceAbove = contentCenterY - halfHeight;
        final float spaceBelow = displayH - contentCenterY - halfHeight;
        return Math.max(0, Math.min(spaceAbove, spaceBelow));
    }

    /**
     * Whether the fitted video has meaningful room to move vertically in the current window.
     * Uses the same bounds as the alignment transform, including pinch zoom and open panels.
     */
    public static boolean hasVerticalAlignmentSpace() {
        View playerView = resolvePlayerView();
        if (playerView == null || playerView.getWidth() <= 0 || playerView.getHeight() <= 0
                || !(playerView.getParent() instanceof View parent)) {
            return false;
        }

        final int displayH = getDisplaySize()[1];
        if (displayH <= 0) {
            return false;
        }

        int[] location = new int[2];
        parent.getLocationInWindow(location);
        final float centerY = location[1] + playerView.getTop()
                - parent.getScrollY() + playerView.getHeight() / 2f;
        final float videoAR = getVideoAspectRatio(playerView,
                playerView.getWidth(), playerView.getHeight());
        final float pinchScale = DisableFullscreenGesturesPatch.getPinchScale();
        final float freeSpace = getAlignmentSpace(playerView, centerY, displayH,
                videoAR, pinchScale);
        final float minimumSpace = 8f * playerView.getResources().getDisplayMetrics().density;
        return freeSpace > minimumSpace;
    }

    private static float getVideoAspectRatio(View view, int viewW, int viewH) {
        // The video view is sized to the video when the renderer does not letterbox the video itself.
        View child = view instanceof PlayerViewInterface player
                ? player.patch_getVideoView()
                : null;
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

    /**
     * Snapshot clip flags on the first call so restore can put back YouTube's values
     * instead of leaving {@code clipChildren=false} on the watch-page ancestors.
     */
    private static void disableClipping(View view) {
        List<SavedClipState> saved = savedClipStates;
        final boolean snapshot = saved == null;
        if (saved == null) {
            saved = new ArrayList<>();
            savedClipStates = saved;
        }
        View current = view;
        for (int i = 0; i < 16 && current != null; i++) {
            if (snapshot) {
                saved.add(new SavedClipState(current));
            }
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

    private static void restoreClipping() {
        List<SavedClipState> saved = savedClipStates;
        savedClipStates = null;
        if (saved == null) {
            return;
        }
        for (int i = saved.size() - 1; i >= 0; i--) {
            saved.get(i).restore();
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
        restoreClipping();
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
                if (!shouldApplyTransform()) {
                    View target = scaledViewRef.get();
                    if (target == null) {
                        target = resolvePlayerView();
                    }
                    detachPreDraw();
                    restoreDefaultTransform(target);
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
        preDrawHostRef = new WeakReference<>(host);
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

    /**
     * Stretch, Zoom and alignment map the player onto the full window, which is only valid in
     * true fullscreen.
     * <p>
     * Landscape {@link PlayerType#WATCH_WHILE_MAXIMIZED} is the tablet / foldable
     * inner-screen watch page (player in a pane), not fullscreen. Phone landscape
     * already reports {@link PlayerType#WATCH_WHILE_FULLSCREEN}.
     * <p>
     * {@link PlayerType#WATCH_WHILE_SLIDING_MAXIMIZED_FULLSCREEN} is used for both
     * enter and exit. Scaling during it would keep the full-window transform while
     * the player is already shrinking back into the watch page.
     *
     * @see <a href="https://github.com/MorpheApp/morphe-patches/issues/2652">#2652</a>
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean shouldApplyTransform() {
        return PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN
                && (Settings.FULLSCREEN_VIDEO_SCALE.get() != VideoScaleMode.DEFAULT
                || Settings.FULLSCREEN_VIDEO_ALIGNMENT.get() != VideoVerticalAlignment.CENTER);
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

    private static final class SavedClipState {
        final WeakReference<View> viewRef;
        final boolean clipToOutline;
        final boolean clipChildren;
        final boolean clipToPadding;
        final boolean isGroup;

        SavedClipState(View view) {
            viewRef = new WeakReference<>(view);
            clipToOutline = view.getClipToOutline();
            isGroup = view instanceof ViewGroup;
            if (view instanceof ViewGroup group) {
                clipChildren = group.getClipChildren();
                clipToPadding = group.getClipToPadding();
            } else {
                clipChildren = false;
                clipToPadding = false;
            }
        }

        void restore() {
            View view = viewRef.get();
            if (view == null) {
                return;
            }
            view.setClipToOutline(clipToOutline);
            if (isGroup && view instanceof ViewGroup group) {
                group.setClipChildren(clipChildren);
                group.setClipToPadding(clipToPadding);
            }
        }
    }
}
