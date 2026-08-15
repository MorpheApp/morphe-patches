/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2332
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.graphics.Rect;
import android.os.Build;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import kotlin.Unit;

/**
 * Moves the fullscreen video away from a camera cutout, so the camera sits in the
 * letterbox bar instead of on top of the picture.
 *
 * <p>A 16:9 video on a wide screen is letterboxed in landscape, which leaves a large empty
 * bar above and below the picture. Moving the video into that existing slack costs no
 * video size: the shift is clamped to the bar, so the picture is never resized or pushed
 * out of view. A screen that is not letterboxed has no slack and is left alone.
 *
 * <p>Only the video is moved. Player controls keep their original layout, so nothing is
 * pushed off screen and the UI stays where the user expects it.
 * {@link View#setTranslationY} is used rather than a margin or padding change because it
 * repositions the video without triggering a re-layout and without resizing it.
 *
 * <p>The view that is moved is the outermost wrapper around the video surface that is still
 * sized to the picture, not the surface itself. Each of those wrappers crops its children,
 * so moving the surface within one of them would cut the picture off rather than move it.
 * See {@link #findMovableVideoView}.
 */
@SuppressWarnings("unused")
public final class FullscreenCutoutPaddingPatch {

    public enum CutoutPaddingMode {
        /**
         * Use the cutout reported by the system, if there is one.
         * Falls back to {@link #MANUAL} when the system reports no cutout, which is the
         * case for under-display cameras such as those on some foldables.
         */
        AUTOMATIC,
        MANUAL,
    }

    public enum CutoutPaddingSide {
        /** Follow the rotation, for a camera in the right half of the screen in portrait. */
        AUTOMATIC_CAMERA_RIGHT,
        /** Follow the rotation, for a camera in the left half of the screen in portrait. */
        AUTOMATIC_CAMERA_LEFT,
        /** Always add space above the video, moving it down. */
        TOP,
        /** Always add space below the video, moving it up. */
        BOTTOM,
    }

    public static final class FullscreenCutoutPaddingAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P;
        }
    }

    public static final class FullscreenCutoutExtraMarginAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.FULLSCREEN_CUTOUT_PADDING.isAvailable()
                    && Settings.FULLSCREEN_CUTOUT_PADDING.get()
                    && Settings.FULLSCREEN_CUTOUT_PADDING_MODE.get() == CutoutPaddingMode.AUTOMATIC;
        }
    }

    /**
     * The view the video is rendered into.
     */
    private static WeakReference<View> videoViewRef = new WeakReference<>(null);

    /**
     * The view a layout listener is currently attached to.
     */
    private static WeakReference<View> listenerAttachedToRef = new WeakReference<>(null);

    /**
     * The view an offset is currently applied to, so it can be cleared again even if a
     * different view is used afterward.
     */
    private static WeakReference<View> offsetAppliedToRef = new WeakReference<>(null);

    private static boolean initialized;

    /**
     * Logged once, so the log is not spammed on every layout pass.
     */
    private static boolean loggedCutoutSupport;

    /**
     * Last logged geometry, so each distinct layout is logged only once.
     */
    private static String loggedGeometry = "";

    /**
     * Injection point. Called at the start of MainActivity onCreate.
     */
    public static void initialize() {
        try {
            if (initialized) {
                return;
            }
            initialized = true;

            // Registered unconditionally so that turning the setting on or off takes
            // effect without restarting the app. update() checks the setting itself.
            PlayerType.getOnChange().addObserver((PlayerType type) -> {
                // The cached view is stale once the player changes, as the app reuses
                // and recreates surfaces between the regular player, Shorts and ads.
                videoViewRef = new WeakReference<>(null);
                update();
                return Unit.INSTANCE;
            });
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    private static void update() {
        try {
            Utils.verifyOnMainThread();
            if (!Settings.FULLSCREEN_CUTOUT_PADDING.get()) {
                clearOffset();
                return;
            }

            Activity activity = Utils.getActivity();
            if (activity == null) {
                return;
            }

            View videoView = getVideoView(activity);
            if (videoView == null) {
                clearOffset();
                return;
            }

            // Only a letterboxed fullscreen video has slack to move into.
            if (PlayerType.getCurrent() != PlayerType.WATCH_WHILE_FULLSCREEN) {
                clearOffset();
                return;
            }

            applyOffset(activity, videoView, calculateOffset(activity, videoView));
        } catch (Exception ex) {
            Logger.printException(() -> "update failure", ex);
        }
    }

    /**
     * @return Vertical offset in pixels. Positive moves the video down.
     */
    private static int calculateOffset(Activity activity, View videoView) {
        // Bounds of the video with this patch's own offset removed, so the result does
        // not depend on the offset currently in effect. Any translation the app itself
        // applies is intentionally left in place.
        final int currentOffset = videoView == offsetAppliedToRef.get()
                ? Math.round(videoView.getTranslationY())
                : 0;
        int[] location = new int[2];
        videoView.getLocationOnScreen(location);
        final int videoLeft = location[0];
        final int videoTop = location[1] - currentOffset;
        final int videoRight = videoLeft + videoView.getWidth();
        final int videoBottom = videoTop + videoView.getHeight();

        // The video is moved within its parent, which crops anything moved outside of it,
        // so the parent bounds are what the offset has to stay inside of.
        View container = videoView.getParent() instanceof View
                ? (View) videoView.getParent()
                : null;
        if (container == null) {
            return 0;
        }
        container.getLocationOnScreen(location);
        final int containerTop = location[1];
        final int containerBottom = containerTop + container.getHeight();

        if (Settings.DEBUG.get()) {
            String geometry = "rotation=" + getRotation(activity)
                    + " landscape=" + Utils.isLandscapeOrientation()
                    + " video=[" + videoTop + ".." + videoBottom + "]"
                    + " " + videoView.getWidth() + "x" + videoView.getHeight()
                    + " " + videoView.getClass().getSimpleName()
                    + "@" + Integer.toHexString(System.identityHashCode(videoView))
                    + " container=" + container.getClass().getSimpleName()
                    + "[" + containerTop + ".." + containerBottom + "]";
            if (!geometry.equals(loggedGeometry)) {
                loggedGeometry = geometry;
                Logger.printDebug(() -> "Geometry: " + geometry);
            }
        }

        // Slack above and below the picture. Staying inside it means the video is
        // repositioned but never resized or cropped.
        final int slackAbove = Math.max(0, videoTop - containerTop);
        final int slackBelow = Math.max(0, containerBottom - videoBottom);
        if (slackAbove == 0 && slackBelow == 0) {
            return 0; // Video fills the container, nowhere to move it.
        }

        Rect cutout = findCutout(activity, videoLeft, videoRight);
        final int requestedOffset;

        if (cutout != null) {
            final boolean intrudesFromTop = cutout.bottom > videoTop
                    && cutout.centerY() < (videoTop + videoBottom) / 2;
            final boolean intrudesFromBottom = cutout.top < videoBottom
                    && cutout.centerY() >= (videoTop + videoBottom) / 2;

            if (!intrudesFromTop && !intrudesFromBottom) {
                return 0; // Already clear of the picture.
            }

            final int margin = Dim.dp(Settings.FULLSCREEN_CUTOUT_EXTRA_MARGIN.get());
            requestedOffset = intrudesFromTop
                    ? cutout.bottom - videoTop + margin     // Move down, away from the top.
                    : -(videoBottom - cutout.top + margin); // Move up, away from the bottom.
        } else {
            final int amount = Dim.dp(Settings.FULLSCREEN_CUTOUT_MANUAL_AMOUNT.get());
            if (amount == 0) {
                return 0;
            }
            Boolean moveDown = resolveMoveDown(activity);
            if (moveDown == null) {
                return 0;
            }
            requestedOffset = moveDown ? amount : -amount;
        }

        return requestedOffset > 0
                ? Math.min(requestedOffset, slackBelow)
                : Math.max(requestedOffset, -slackAbove);
    }

    /**
     * @return Bounds of the cutout intruding furthest into the video, in screen
     *         coordinates, or null if there is nothing to move away from.
     */
    @Nullable
    private static Rect findCutout(Activity activity, int videoLeft, int videoRight) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P
                || Settings.FULLSCREEN_CUTOUT_PADDING_MODE.get() != CutoutPaddingMode.AUTOMATIC) {
            return null;
        }

        View decorView = activity.getWindow().getDecorView();
        WindowInsets insets = decorView.getRootWindowInsets();
        DisplayCutout cutout = insets == null ? null : insets.getDisplayCutout();

        if (!loggedCutoutSupport) {
            loggedCutoutSupport = true;
            final DisplayCutout logCutout = cutout;
            // Under-display cameras are not reported here, in which case the manual
            // distance is used instead.
            Logger.printDebug(() -> "System reported display cutout: " + logCutout);
        }

        if (cutout == null) {
            return null;
        }

        // Cutout bounds are relative to the window, but the video bounds passed in are
        // relative to the screen. These differ in split screen and freeform windows.
        int[] decorLocation = new int[2];
        decorView.getLocationOnScreen(decorLocation);

        Rect furthest = null;
        int furthestIntrusion = 0;
        for (Rect windowBounds : cutout.getBoundingRects()) {
            if (windowBounds.isEmpty()) {
                continue;
            }
            Rect bounds = new Rect(windowBounds);
            bounds.offset(decorLocation[0], decorLocation[1]);

            if (bounds.right <= videoLeft || bounds.left >= videoRight) {
                continue; // Does not overlap the picture horizontally.
            }
            // How far the cutout reaches into the video is what has to be cleared,
            // which is not the same as how tall the cutout is.
            final int intrusion = Math.max(0, bounds.height());
            if (furthest == null || intrusion > furthestIntrusion) {
                furthest = bounds;
                furthestIntrusion = intrusion;
            }
        }
        return furthest;
    }

    /**
     * @return True to move the video down, false to move it up, or null if it should not
     *         be moved at all.
     */
    @Nullable
    private static Boolean resolveMoveDown(Activity activity) {
        // Portrait keeps the camera on a short edge, where it is already outside a
        // letterboxed video. Checked by orientation rather than rotation, because the
        // natural orientation of a tablet or foldable inner panel is often landscape.
        if (!Utils.isLandscapeOrientation()) {
            return null;
        }

        switch (Settings.FULLSCREEN_CUTOUT_MANUAL_SIDE.get()) {
            case TOP:
                return true;
            case BOTTOM:
                return false;
            default:
                break;
        }

        // Turning the device one way puts the right of the screen at the top, and turning
        // it the other way puts the right of the screen at the bottom. The two landscape
        // rotations are always 180 degrees apart, but which values they take depends on
        // the natural orientation of the display: 90 and 270 when it is portrait, 0 and
        // 180 when it is landscape, as on many tablets and foldable inner screens. Testing
        // one of each pair covers both kinds of device.
        final int rotation = getRotation(activity);
        final boolean rotatedOneWay = rotation == Surface.ROTATION_0
                || rotation == Surface.ROTATION_90;

        // Which of the two directions puts the camera above the video is not fixed across
        // devices, because a panel can be mounted at an angle to the natural orientation
        // of its display (see installOrientation). Choosing the opposite camera side
        // corrects a device that differs, and the explicit options above always override.
        final boolean cameraOnRight = Settings.FULLSCREEN_CUTOUT_MANUAL_SIDE.get()
                == CutoutPaddingSide.AUTOMATIC_CAMERA_RIGHT;
        return rotatedOneWay == cameraOnRight;
    }

    /**
     * Sets the vertical position of the video surface.
     *
     * <p>The translation is set outright rather than adjusted by a delta. The app also
     * changes this property on the surface during player transitions, and adjusting by a
     * delta leaves the video stranded in the wrong place whenever that happens. Setting it
     * outright means any later call puts the video back where it belongs, so a reset by the
     * app is corrected on the next layout instead of being permanent.
     */
    private static void applyOffset(Activity activity, View videoView, int offset) {
        View previous = offsetAppliedToRef.get();
        if (previous != null && previous != videoView) {
            previous.setTranslationY(0);
        }

        // Compared against the view rather than the last value set, so that a change made
        // by the app is noticed and undone.
        if (previous == videoView && Math.round(videoView.getTranslationY()) == offset) {
            return;
        }

        Logger.printDebug(() -> "Setting fullscreen video offset: " + offset
                + "px (rotation " + getRotation(activity) + ")");
        videoView.setTranslationY(offset);
        offsetAppliedToRef = new WeakReference<>(videoView);
    }

    /**
     * Moves the video back to its original position.
     */
    private static void clearOffset() {
        View view = offsetAppliedToRef.get();
        if (view != null && Math.round(view.getTranslationY()) != 0) {
            Logger.printDebug(() -> "Clearing fullscreen video offset");
            view.setTranslationY(0);
        }
        offsetAppliedToRef = new WeakReference<>(null);
    }

    /**
     * @return The view the video is rendered into, or null if it cannot be found.
     */
    @Nullable
    private static View getVideoView(Activity activity) {
        View cached = videoViewRef.get();
        if (cached != null && cached.isAttachedToWindow() && cached.isShown()) {
            return cached;
        }

        View contentRoot = activity.findViewById(android.R.id.content);
        if (!(contentRoot instanceof ViewGroup)) {
            return null;
        }

        // The video is rendered into a SurfaceView or TextureView. Others can be present
        // for thumbnails and ads, so the largest currently visible one is used. The cache
        // is dropped whenever the player type changes, so a surface picked during a
        // transition does not stay in use.
        View surface = findLargestVideoSurface((ViewGroup) contentRoot, null);
        View found = surface == null ? null : findMovableVideoView(surface);
        videoViewRef = new WeakReference<>(found);

        // Reapply after the video is resized, which covers rotating the device and
        // folding or unfolding it.
        if (found != null) {
            View previous = listenerAttachedToRef.get();
            if (found != previous) {
                listenerAttachedToRef = new WeakReference<>(found);
                if (previous != null) {
                    previous.removeOnLayoutChangeListener(layoutListener);
                }
                found.addOnLayoutChangeListener(layoutListener);
            }
        }
        return found;
    }

    private static final View.OnLayoutChangeListener layoutListener =
            (v, l, t, r, b, oldL, oldT, oldR, oldB) -> update();

    @Nullable
    private static View findLargestVideoSurface(ViewGroup parent, @Nullable View initialLargest) {
        View largest = initialLargest;
        for (int i = 0, count = parent.getChildCount(); i < count; i++) {
            View child = parent.getChildAt(i);
            if (child instanceof SurfaceView || child instanceof TextureView) {
                final int childWidth = child.getWidth();
                final int childHeight = child.getHeight();
                if (childWidth > 0 && childHeight > 0 && (largest == null
                        || childWidth * childHeight > largest.getWidth() * largest.getHeight())
                        && child.isShown()) {
                    largest = child;
                }
            } else if (child instanceof ViewGroup) {
                largest = findLargestVideoSurface((ViewGroup) child, largest);
            }
        }
        return largest;
    }

    /**
     * The video surface sits inside wrappers that are sized to the picture rather than to
     * the screen, and each of them crops its children. Moving the surface itself only
     * crops it, so the view that has to move is the outermost wrapper still sized to the
     * picture. Its parent is sized to the screen, which is where the room to move comes
     * from.
     *
     * @return The view to move, which is the given surface if it has no such wrapper.
     */
    private static View findMovableVideoView(View surface) {
        View movable = surface;
        while (movable.getParent() instanceof ViewGroup parent) {
            if (parent.getHeight() != movable.getHeight()
                    || parent.getWidth() != movable.getWidth()) {
                break; // Parent is larger than the picture, so this is where to stop.
            }
            movable = parent;
        }
        return movable;
    }

    private static int getRotation(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Display display = activity.getDisplay();
            if (display != null) {
                return display.getRotation();
            }
        }
        return activity.getWindowManager().getDefaultDisplay().getRotation();
    }
}
