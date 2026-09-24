/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2911
 * https://github.com/MorpheApp/morphe-patches/pull/2928
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.MINIMAL_BAR;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.MiniplayerType.MINIMAL_BAR_2;
import static app.morphe.extension.youtube.patches.MiniplayerPatch.getCurrentMiniplayerType;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.ViewAnimations;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import kotlin.Unit;

/**
 * Rebuilds the legacy 'Minimal' miniplayer, which YouTube dropped the code for in 21.29.
 * <p>
 * Its layout {@code floaty_bar_controls} is still inflated as the miniplayer container, so the
 * player is reshaped into a bar and those original views are unhidden and driven from here.
 */
@SuppressWarnings("unused")
public final class MinimalMiniplayerPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface MiniplayerBoundsController {
        // Method is added during patching.
        void patch_setBounds(Rect bounds);
    }

    private static final int MODERN_MINIPLAYER_OVERLAY_ACTION_BUTTON = ResourceUtils.getIdentifier(
            ResourceType.ID, "modern_miniplayer_overlay_action_button");
    private static final int MODERN_MINIPLAYER_CLOSE = ResourceUtils.getIdentifier(
            ResourceType.ID, "modern_miniplayer_close");
    private static final int MODERN_MINIPLAYER_EXPAND = ResourceUtils.getIdentifier(
            ResourceType.ID, "modern_miniplayer_expand");
    private static final String ACCESSIBILITY_MINIPLAYER_VIEW_STRING = ResourceUtils.getString(
            "accessibility_miniplayer_view");

    // Not read from the settings a second time, or a type changed mid-session would leave
    // this and the type the rest of the class reads disagreeing.
    private static final boolean ENABLED = getCurrentMiniplayerType() == MINIMAL_BAR
            || getCurrentMiniplayerType() == MINIMAL_BAR_2;
    private static final boolean HIDE_TITLE =
            getCurrentMiniplayerType() == MINIMAL_BAR_2
                    && Settings.MINIPLAYER_HIDE_TITLE.get();

    /**
     * YouTube's own {@code floaty_bar_height}.
     */
    private static final int BAR_HEIGHT = Dim.dp(56);

    /**
     * Detached from the edges, so the rounded corners the miniplayer already has are visible.
     */
    private static final int BAR_MARGIN = Dim.dp8;

    /**
     * Type 2 draws the bar on the video, which is dimmed but never light, so the app colors
     * cannot be used. These are what YouTube puts on its own player overlays.
     */
    private static final int OVERLAY_SCRIM_COLOR = Color.argb(80, 0, 0, 0);
    private static final int OVERLAY_BUTTON_COLOR = Color.argb(80, 255, 255, 255);
    private static final int OVERLAY_DARK_BUTTON_COLOR = Color.argb(120, 0, 0, 0);
    private static final int OVERLAY_TEXT_SHADOW_COLOR = Color.argb(180, 0, 0, 0);
    private static final int OVERLAY_PRIMARY_COLOR = Color.WHITE;
    private static final int OVERLAY_SECONDARY_COLOR = Color.argb(180, 255, 255, 255);

    /**
     * Derived from the height, so the thumbnail keeps 16:9 through every frame of the morph.
     */
    private static int videoWidthFor(int height) {
        return Math.round(height * 16f / 9f);
    }

    private static final long TICK_MILLIS = 500;

    /**
     * Content description YouTube puts on the action button while the video is playing.
     */
    private static final int PAUSE_DESCRIPTION = ResourceUtils.
            getStringIdentifier("accessibility_pause");

    private static final long MORPH_MILLIS = 220;

    /**
     * Reused, because the bounds hooks run for every frame of a drag.
     */
    private static final Rect originalBounds = new Rect();
    private static final Rect barBounds = new Rect();

    /**
     * The last bounds YouTube itself asked for, used to keep the bar at the resting position.
     */
    private static final Rect lastBounds = new Rect();

    /**
     * The bounds the player is actually using, ours or YouTube's.
     */
    private static final Rect currentBounds = new Rect();

    private static final Rect clipBounds = new Rect();
    private static final Rect dockedBounds = new Rect();
    private static final int[] windowLocation = new int[2];

    private static final Rect morphFrom = new Rect();
    private static final Rect morphTo = new Rect();
    private static final Rect morphCurrent = new Rect();

    private static WeakReference<ViewGroup> controlsRef = new WeakReference<>(null);
    private static WeakReference<View> barContainerRef = new WeakReference<>(null);
    private static WeakReference<TextView> titleRef = new WeakReference<>(null);
    private static WeakReference<TextView> subtitleRef = new WeakReference<>(null);
    private static WeakReference<ImageView> playPauseRef = new WeakReference<>(null);
    private static WeakReference<View> modernActionButtonRef = new WeakReference<>(null);
    private static WeakReference<View> modernCloseButtonRef = new WeakReference<>(null);
    private static WeakReference<View> modernExpandButtonRef = new WeakReference<>(null);
    private static WeakReference<View> watchPlayerRef = new WeakReference<>(null);
    private static WeakReference<View> skipAdRef = new WeakReference<>(null);
    private static WeakReference<View> navigationBarRef = new WeakReference<>(null);
    private static WeakReference<MiniplayerBoundsController> boundsControllerRef = new WeakReference<>(null);

    private static boolean listenersInstalled;
    private static boolean ticking;
    private static boolean playing;
    private static boolean morphing;
    private static float contentAlpha = 1f;

    /**
     * Whether the bar container is currently moved in front of the player, and what it has to
     * go back to. Putting it back matters more than moving it: left in front it also takes the
     * taps meant for the expanded player.
     */
    private static boolean barDrawsOverPlayer;
    @Nullable
    private static Drawable originalBarBackground;
    private static int barIndexInParent;

    /**
     * Set while pushing bounds of our own, which come back through the hook that reads them.
     */
    private static boolean applyingBounds;
    private static ValueAnimator morphAnimator;

    /**
     * Whether the player currently holds the bar shape. The player type cannot answer this,
     * it already reports the new state by the time the change is delivered.
     */
    private static boolean barShapeApplied;

    /**
     * Do not set it to 1.00f, otherwise it will cause artifacts (e.g: with the controls fading).
     */
    private static final float maxDragProgress = 0.95f;

    /**
     * Injection point.
     * <p>
     * The legacy bar layout, looked up by YouTube itself and then left unused.
     */
    public static void setLegacyControls(ViewGroup controlsLayout) {
        if (!ENABLED) return;

        try {
            controlsRef = new WeakReference<>(controlsLayout);
            barContainerRef = new WeakReference<>(
                    controlsLayout.getParent() instanceof View barContainer
                            ? barContainer
                            : null
            );

            watchPlayerRef = new WeakReference<>(null);
            skipAdRef = new WeakReference<>(null);
            navigationBarRef = new WeakReference<>(null);

            // A new watch page brings its own views, so what the last ones were left in
            // does not carry over.
            barDrawsOverPlayer = false;
            originalBarBackground = null;

            TextView title = Utils.getChildViewByResourceName(controlsLayout, "floaty_title");
            titleRef = new WeakReference<>(title);

            TextView subtitle = Utils.getChildViewByResourceName(controlsLayout, "floaty_subtitle_text");
            if (title != null && HIDE_TITLE) {
                title.setVisibility(View.GONE);
            }
            
            subtitleRef = new WeakReference<>(subtitle);

            ImageView playPause = Utils.getChildViewByResourceName(controlsLayout, "floaty_play_pause_button");
            playPauseRef = new WeakReference<>(playPause);
            if (playPause != null) {
                playPause.setOnClickListener(v ->
                        clickModernButton(modernActionButtonRef, "play/pause"));
                holdTouch(playPause);
                styleButton(playPause);
                // The morph YouTube animates its own play button with is a 48dp drawable and
                // the plain icons are 24dp, so both are scaled into the same box.
                playPause.setScaleType(ImageView.ScaleType.FIT_CENTER);
                playPause.setPadding(Dim.dp12, Dim.dp12, Dim.dp12, Dim.dp12);
            }

            ImageView close = Utils.getChildViewByResourceName(controlsLayout, "floaty_close_button");
            if (close != null) {
                close.setOnClickListener(v -> closeBar());
                holdTouch(close);
                setIcon(close, "yt_outline_experimental_x_vd_theme_24", "yt_outline_x_black_24");
                styleButton(close);
            }

            View subtitleBar = Utils.getChildViewByResourceName(controlsLayout, "floaty_subtitle_bar");
            if (subtitleBar != null) {
                subtitleBar.setVisibility(HIDE_TITLE ? View.GONE : View.VISIBLE);
            }

            if (getCurrentMiniplayerType() == MINIMAL_BAR) {
                startAfterVideo(title);
                startAfterVideo(subtitleBar);
            } else {
                // The contents sit on the video rather than beside it, so it is dimmed, and
                // they take the overlay colors. The buttons alone have a backdrop of their own.
                controlsLayout.setBackgroundColor(HIDE_TITLE ? Color.TRANSPARENT : OVERLAY_SCRIM_COLOR);
                setOverlayTextColor(title, OVERLAY_PRIMARY_COLOR);
                setOverlayTextColor(subtitle, OVERLAY_SECONDARY_COLOR);
                setIconColor(playPause);
                setIconColor(close);
            }

            // Nothing drives this anymore and it would only draw an empty track.
            View progressBar = Utils.getChildViewByResourceName(controlsLayout, "progress_bar");
            if (progressBar != null) {
                progressBar.setVisibility(View.GONE);
            }

            setBarGestures(controlsLayout);

            installListeners();
            updateBar();

            Logger.printDebug(() -> "Legacy miniplayer controls attached");
        } catch (Exception ex) {
            Logger.printException(() -> "setLegacyControls failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * YouTube unconditionally hides the legacy bar contents.
     */
    public static int getLegacyControlsVisibility(int original) {
        // Any other shape and these would sit across the whole screen.
        if (ENABLED && inBarMode()) {
            // YouTube sets its own alpha on these right before, which the morph owns while it
            // runs. Left alone, the contents are drawn fully opaque for a frame.
            setContentAlpha(morphing ? contentAlpha : 1f);

            return View.VISIBLE;
        }

        return original;
    }

    /**
     * Injection point.
     * <p>
     * Called for every miniplayer bounds change, including each frame of a drag.
     */
    public static Rect getMiniplayerBounds(int left, int top, int right, int bottom) {
        originalBounds.set(left, top, right, bottom);

        return getMinimalBarBounds(originalBounds);
    }

    /**
     * Injection point.
     * <p>
     * The argument belongs to YouTube and is never modified in place.
     */
    public static Rect getMinimalBarBounds(Rect original) {
        try {
            if (!ENABLED) {
                return original;
            }

            if (applyingBounds) {
                // Ours, YouTube is only being told where the player is. Recording it as the
                // resting bounds would leave the next collapse with nothing to animate.
                currentBounds.set(original);
                return original;
            }

            if (morphing) {
                // YouTube settles its own corner miniplayer after reporting minimized, which is
                // after the morph began. Let through, that shape is drawn for a frame.
                return currentBounds;
            }

            Rect docked = fullWidthSpan(original);
            lastBounds.set(docked);
            PlayerType getCurrent = PlayerType.getCurrent();
            if (getCurrent == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED) {
                barBoundsFor(dockedBounds);

                final int maxDragTop = barBounds.top > 0 ? barBounds.top : dockedBounds.top;
                final float progress = maxDragTop > 0
                        ? Math.min(maxDragProgress, Math.max(0.0f, (float) original.top / maxDragTop))
                        : maxDragProgress;

                final int currentLeft = interpolate(dockedBounds.left, barBounds.left, progress);
                final int currentTop = interpolate(dockedBounds.top, barBounds.top, progress);
                final int currentRight = interpolate(dockedBounds.right, barBounds.right, progress);
                final int currentBottom = interpolate(dockedBounds.bottom, barBounds.bottom, progress);

                currentBounds.set(new Rect(currentLeft, currentTop, currentRight, currentBottom));

                return currentBounds;
            }
            if (getCurrent == PlayerType.WATCH_WHILE_MINIMIZED) {
                barBoundsFor(docked);
                currentBounds.set(barBounds);
                barShapeApplied = true;
                return barBounds;
            }

            currentBounds.set(docked);
            return docked;
        } catch (Exception ex) {
            Logger.printException(() -> "getMinimalBarBounds failure", ex);
        }

        return original;
    }

    /**
     * Prevents the video from anchoring into one of the display corners by spanning the
     * bounds to full display width, making the transition to miniplayer smoother.
     */
    private static Rect fullWidthSpan(Rect original) {
        dockedBounds.set(0, original.top, getWidthPixels(), original.bottom);

        return dockedBounds;
    }

    private static void barBoundsFor(Rect resting) {
        final int bottom = barBottomFor(resting) - BAR_MARGIN;

        barBounds.set(BAR_MARGIN, bottom - BAR_HEIGHT, getWidthPixels() - BAR_MARGIN, bottom);
    }

    /**
     * Not {@link Dim#getScreenWidth()}, which measures the display. A bar spans
     * the window, and the two differ in split screen and on foldables.
     */
    private static int getWidthPixels() {
        return Dim.getMetrics().widthPixels;
    }

    /**
     * A floating miniplayer keeps a margin to the navigation bar, a docked bar sits against it.
     * While the navigation bar is away, YouTube's own resting edge is already the right one.
     */
    private static int barBottomFor(Rect resting) {
        View navigationBar = navigationBar();
        if (navigationBar == null || !navigationBar.isShown()) return resting.bottom;

        navigationBar.getLocationInWindow(windowLocation);

        // Where it rests, not where it is. YouTube slides it away while the feed scrolls, and
        // a position taken mid-slide leaves the bar underneath it once it comes back.
        final int top = windowLocation[1] - Math.round(navigationBar.getTranslationY());

        // A rail beside the content says nothing about where the bar ends.
        if (navigationBar.getWidth() < navigationBar.getHeight()) return resting.bottom;

        // Docked against it even when YouTube rests lower. After a recreated activity its
        // resting bounds are still the ones from before the system insets moved everything up.
        return top;
    }

    /**
     * Injection point.
     * <p>
     * The rect the video is scaled into, which for a bar is a cropped slice.
     */
    public static void applyVideoRect(Rect videoRect) {
        try {
            if (!ENABLED) {
                return;
            }

            if (getCurrentMiniplayerType() == MINIMAL_BAR) {
                final int videoWidth = videoWidthFor(currentBounds.height());

                videoRect.set(currentBounds);
                if (Utils.isRightToLeftLocale()) {
                    videoRect.left = videoRect.right - videoWidth;
                } else {
                    videoRect.right = videoRect.left + videoWidth;
                }
            } else {
                // Type 2 spans the bar with the video. YouTube fits anything that is not 16:9
                // inside the bar instead, and the miniplayer has no background of its own, so
                // the feed shows through beside it. The overflow is clipped away again.
                final float videoWidth = videoRect.width();
                final float videoHeight = videoRect.height();
                final float videoAspectRatio =
                        (videoWidth > 0 && videoHeight > 0)
                                ? videoWidth / videoHeight
                                : 16f / 9f;

                final float barWidth = currentBounds.width();
                final float barHeight = currentBounds.height();
                final float barAspectRatio = barWidth / barHeight;

                videoRect.set(currentBounds);

                if (videoAspectRatio < barAspectRatio) {
                    // Video is narrower than the bar
                    final int targetHeight = Math.round(barWidth / videoAspectRatio);
                    final int overflowY = Math.max(0, (int) (targetHeight - barHeight)) / 2;

                    videoRect.top -= overflowY;
                    videoRect.bottom += overflowY;
                } else {
                    // Video is wider than the bar
                    final int targetWidth = Math.round(barHeight * videoAspectRatio);
                    final int overflowX = Math.max(0, (int) (targetWidth - barWidth)) / 2;

                    videoRect.left -= overflowX;
                    videoRect.right += overflowX;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "applyVideoRect failure", ex);
        }
    }

    private static boolean inBarMode() {
        return morphing || barShapeApplied;
    }

    /**
     * Injection point.
     */
    public static void setBoundsController(MiniplayerBoundsController controller) {
        if (ENABLED) {
            boundsControllerRef = new WeakReference<>(controller);
        }
    }

    /**
     * Animates whatever shape the player was left in into the bar. A slow collapse settles the
     * bounds while the player still reports itself as sliding, so nothing else sets them again.
     */
    private static void applyBarShape() {
        try {
            cancelMorph();

            if (lastBounds.isEmpty()) return;

            MiniplayerBoundsController controller = boundsControllerRef.get();
            if (controller == null) {
                Logger.printDebug(() -> "No miniplayer bounds controller");
                return;
            }

            morphFrom.set(currentBounds.isEmpty() ? lastBounds : currentBounds);
            barBoundsFor(lastBounds);
            morphTo.set(barBounds);
            barShapeApplied = true;
            showControls(true);

            if (morphFrom.equals(morphTo)) {
                setBounds(controller, morphTo);
                updateVideoClip();
                return;
            }

            setContentAlpha(0f);
            runMorph(true, () -> setContentAlpha(1f));
        } catch (Exception ex) {
            morphing = false;
            Logger.printException(() -> "applyBarShape failure", ex);
        }
    }

    private static void setBounds(MiniplayerBoundsController controller, Rect bounds) {
        applyingBounds = true;
        try {
            controller.patch_setBounds(bounds);
        } finally {
            applyingBounds = false;
        }

        currentBounds.set(bounds);
    }

    private static void runMorph(boolean fadeInContent, Runnable onEnd) {
        morphing = true;

        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        animator.setDuration(MORPH_MILLIS);
        animator.setInterpolator(new DecelerateInterpolator());
        animator.addUpdateListener(value ->
                onMorphFrame((float) value.getAnimatedValue(), fadeInContent));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                morphing = false;
                morphAnimator = null;
                currentBounds.set(morphTo);
                updateVideoClip();
                onEnd.run();
            }
        });

        morphAnimator = animator;
        animator.start();
    }

    private static void onMorphFrame(float fraction, boolean fadeInContent) {
        MiniplayerBoundsController controller = boundsControllerRef.get();
        if (controller == null) {
            cancelMorph();
            return;
        }

        morphCurrent.set(
                interpolate(morphFrom.left, morphTo.left, fraction),
                interpolate(morphFrom.top, morphTo.top, fraction),
                interpolate(morphFrom.right, morphTo.right, fraction),
                interpolate(morphFrom.bottom, morphTo.bottom, fraction)
        );

        setBounds(controller, morphCurrent);
        updateVideoClip();

        if (fadeInContent) {
            // The text would otherwise sit on top of the video, still wide at this point.
            setContentAlpha(Math.max(0f, (fraction - 0.4f) / 0.6f));
        }
    }

    private static void cancelMorph() {
        ValueAnimator animator = morphAnimator;
        morphAnimator = null;
        morphing = false;

        if (animator != null) {
            // Cancelling would otherwise report the morph as finished.
            animator.removeAllListeners();
            animator.cancel();
        }
    }

    private static int interpolate(int from, int to, float fraction) {
        return Math.round(from + (to - from) * fraction);
    }

    private static void setContentAlpha(float alpha) {
        contentAlpha = alpha;

        ViewGroup controls = controlsRef.get();
        if (controls != null) {
            controls.setAlpha(alpha);
        }
    }

    /**
     * Called from {@link MiniplayerPatch} for each modern overlay button that is created.
     */
    static void setModernOverlayButton(View button) {
        if (!ENABLED) return;

        try {
            final int id = button.getId();
            if (id == MODERN_MINIPLAYER_OVERLAY_ACTION_BUTTON) {
                modernActionButtonRef = new WeakReference<>(button);
            } else if (id == MODERN_MINIPLAYER_CLOSE) {
                modernCloseButtonRef = new WeakReference<>(button);
            } else if (id == MODERN_MINIPLAYER_EXPAND) {
                // Kept in place, it is the only handle on expanding the player.
                modernExpandButtonRef = new WeakReference<>(button);
                return;
            } else {
                return;
            }

            // The bar has its own buttons, and these would otherwise draw over the video box.
            // Clicks are still forwarded to them after they leave the hierarchy.
            Utils.hideViewByRemovingFromParentUnderCondition(true, button);
        } catch (Exception ex) {
            Logger.printException(() -> "setModernOverlayButton failure", ex);
        }
    }

    /**
     * YouTube reads a gesture against the shape of its own miniplayer, which the bar is not, so
     * the bar answers for itself: a tap or a drag up expands, a drag down closes.
     */
    @SuppressLint("ClickableViewAccessibility")
    private static void setBarGestures(ViewGroup controlsLayout) {
        final int slop = Dim.dp20;

        controlsLayout.setClickable(true);
        controlsLayout.setContentDescription(ACCESSIBILITY_MINIPLAYER_VIEW_STRING);
        controlsLayout.setOnClickListener(v -> expandPlayer());

        controlsLayout.setOnTouchListener(new View.OnTouchListener() {
            float downX;
            float downY;

            @Override
            public boolean onTouch(View view, MotionEvent event) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = event.getRawX();
                        downY = event.getRawY();
                        keepTouchFromParent(view);
                        return true;

                    case MotionEvent.ACTION_UP:
                        final float draggedUp = downY - event.getRawY();

                        if (draggedUp > slop) {
                            expandPlayer();
                        } else if (-draggedUp > slop) {
                            closeBar();
                        } else if (Math.abs(event.getRawX() - downX) < slop) {
                            // Goes through the click, so accessibility services see it.
                            view.performClick();
                        }
                        return true;

                    default:
                        // Held for the whole gesture, the watch layout reads the moves as a pan.
                        return true;
                }
            }
        });
    }

    /**
     * The close button hides the miniplayer outright, so the bar sees itself out first.
     */
    private static void closeBar() {
        if (!barShapeApplied || boundsControllerRef.get() == null) {
            clickModernButton(modernCloseButtonRef, "close");
            return;
        }

        cancelMorph();
        morphFrom.set(currentBounds);
        morphTo.set(currentBounds);
        // Clear of the screen, not one bar height down. That only reaches the navigation bar,
        // which the bar then sits behind until YouTube is done closing after the click below.
        morphTo.offset(0, Dim.getScreenHeight() - currentBounds.top);

        runMorph(false, () -> clickModernButton(modernCloseButtonRef, "close"));
    }

    private static void expandPlayer() {
        if (clickModernButton(modernExpandButtonRef, "expand")) return;

        ViewGroup controls = controlsRef.get();
        if (controls != null && controls.getParent() instanceof View floatyBar) {
            floatyBar.performClick();
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private static void holdTouch(View button) {
        button.setOnTouchListener((view, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                keepTouchFromParent(view);
            }

            return false;
        });
    }

    /**
     * Otherwise the watch layout intercepts the gesture and acts on it as well.
     */
    private static void keepTouchFromParent(View view) {
        ViewParent parent = view.getParent();
        if (parent != null) {
            parent.requestDisallowInterceptTouchEvent(true);
        }
    }

    private static boolean clickModernButton(WeakReference<View> buttonRef, String description) {
        View button = buttonRef.get();
        if (button == null) {
            Logger.printDebug(() -> "No modern miniplayer button for: " + description);
            return false;
        }

        return button.performClick();
    }

    private static void installListeners() {
        if (listenersInstalled) return;
        listenersInstalled = true;

        PlayerType.getOnChange().addObserver((PlayerType type) -> {
            final boolean minimized = type == PlayerType.WATCH_WHILE_MINIMIZED;

            Utils.runOnMainThreadNowOrLater(() -> {
                startTicking(minimized);

                if (minimized) {
                    refreshContents();
                    applyBarShape();
                } else {
                    barShapeApplied = false;

                    // A dismissal is the bar sliding itself away, anything else grows the
                    // player back and has to be uncovered, or it stays clipped.
                    if (type != PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED) {
                        cancelMorph();
                    }

                    if (!morphing) {
                        // Done here as well, YouTube does not always run its own pass in time.
                        showControls(false);
                    }
                }

                updateVideoClip();
            });
            return Unit.INSTANCE;
        });
    }

    private static void updateBar() {
        final boolean minimized = PlayerType.getCurrent() == PlayerType.WATCH_WHILE_MINIMIZED;

        updateVideoClip();
        startTicking(minimized);
        refreshContents();
        showControls(minimized);
        if (minimized && !morphing) {
            setContentAlpha(1f);
        }
    }

    /**
     * The skip button is in the player overlay, so an ad cannot be skipped while it is clipped.
     */
    private static boolean isSkipAdShown() {
        View skipAd = skipAdRef.get();
        if (skipAd == null) {
            skipAd = findInWindow("modern_miniplayer_skip_ad_button");
            if (skipAd == null) return false;

            skipAdRef = new WeakReference<>(skipAd);
        }

        return skipAd.isShown();
    }

    /**
     * For the views outside the bar layout, which the bar only reaches through the window.
     */
    @Nullable
    private static View findInWindow(String name) {
        ViewGroup controls = controlsRef.get();
        if (controls == null) return null;

        return Utils.getChildViewByResourceName(controls.getRootView(), name);
    }

    @Nullable
    private static View navigationBar() {
        View navigationBar = navigationBarRef.get();
        if (navigationBar != null) return navigationBar;

        navigationBar = findInWindow("bottom_bar_container");
        if (navigationBar != null) {
            navigationBarRef = new WeakReference<>(navigationBar);
            navigationBar.removeOnLayoutChangeListener(navigationBarLayoutListener);
            navigationBar.addOnLayoutChangeListener(navigationBarLayoutListener);
        }

        return navigationBar;
    }

    /**
     * Without the translucent navigation bar, a recreated activity lays the navigation bar out
     * before the system insets move it up, and a bar placed against that stays underneath it.
     */
    private static final View.OnLayoutChangeListener navigationBarLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
                if (top != oldTop || bottom != oldBottom) {
                    // Not from inside the layout pass the change is reported from.
                    Utils.runOnMainThread(MinimalMiniplayerPatch::reapplyBarBounds);
                }
            };

    private static void reapplyBarBounds() {
        if (!barShapeApplied || morphing || lastBounds.isEmpty()) return;

        MiniplayerBoundsController controller = boundsControllerRef.get();
        if (controller == null) return;

        barBoundsFor(lastBounds);
        if (barBounds.equals(currentBounds)) return;

        setBounds(controller, barBounds);
        updateVideoClip();
    }

    private static void startTicking(boolean start) {
        if (ticking == start) return;
        ticking = start;

        if (start) {
            tick();
        }
    }

    /**
     * Nothing announces a new video to the bar, so its contents are kept in step by polling.
     */
    private static void tick() {
        if (!ticking) return;

        updateText();
        updateVideoClip();

        Utils.runOnMainThreadDelayed(MinimalMiniplayerPatch::tick, TICK_MILLIS);
    }

    /**
     * Injection point.
     * <p>
     * Called by YouTube whenever it changes the miniplayer action button icon.
     */
    public static void setPlaybackIcon(int contentDescriptionId) {
        if (ENABLED) {
            setPlaying(contentDescriptionId == PAUSE_DESCRIPTION);
        }
    }

    private static void setPlaying(boolean isPlaying) {
        if (playing == isPlaying) return;

        playing = isPlaying;
        updatePlayPauseIcon(true);
    }

    /**
     * The video is drawn over the start of the bar, so the text begins after it.
     */
    private static void startAfterVideo(View view) {
        if (view == null) return;

        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params instanceof ViewGroup.MarginLayoutParams marginParams) {
            marginParams.setMarginStart(videoWidthFor(BAR_HEIGHT));
            view.setLayoutParams(marginParams);
        }
    }

    /**
     * The player draws outside the bar, so it is clipped down to the box the video belongs in.
     * The box is taken from the shape the player currently has.
     */
    private static void updateVideoClip() {
        View watchPlayer = watchPlayer();
        if (watchPlayer == null) return;

        // Not from showControls(), which YouTube bypasses when it puts the bar up itself.
        // Called for every bounds change and tick, and does nothing once the order is set.
        if (getCurrentMiniplayerType() == MINIMAL_BAR_2) {
            setBarDrawsOverPlayer(inBarMode());
        }

        final int height = currentBounds.height();
        final int width = currentBounds.width();

        if (getCurrentMiniplayerType() == MINIMAL_BAR) {
            final int videoWidth = videoWidthFor(height);

            // Only a bar is far wider than the video it holds. Going by the state instead would
            // uncover the video for as long as the player still holds the bar shape. Ads are
            // uncovered as well, their skip button is part of the overlay this hides.
            if (!inBarMode() || height <= 0 || width <= videoWidth || isSkipAdShown()) {
                watchPlayer.setClipBounds(null);
                return;
            }

            clipBounds.set(0, 0, videoWidth, height);
            if (Utils.isRightToLeftLocale()) {
                clipBounds.offset(width - videoWidth, 0);
            }
        } else {
            // Type 2 keeps the video as the background of the bar, and YouTube lays it out at
            // the full bar width in 16:9, far taller than the bar. Uncut it hangs over the
            // feed and the navigation bar.
            if (!inBarMode() || height <= 0) {
                watchPlayer.setClipBounds(null);
                return;
            }

            clipBounds.set(0, 0, width, height);
        }

        watchPlayer.setClipBounds(clipBounds);
    }

    /**
     * Type 2 draws the bar on the video, and the player holds a {@code SurfaceView} that punches
     * a hole through whatever was drawn before it, so the bar has to come after the player in the
     * watch layout. Elevation is no use, this layout draws its children through its own
     * {@code drawChild} and does not order them by it.
     * <p>
     * The order is put back when the bar goes away. It also decides who gets a touch first, and
     * a bar left in front swallows the taps that should reach the expanded player.
     */
    private static void setBarDrawsOverPlayer(boolean over) {
        if (barDrawsOverPlayer == over) return;

        View barContainer = barContainerRef.get();
        if (barContainer != null && barContainer.getParent() instanceof ViewGroup parent) {
            if (over) {
                barIndexInParent = parent.indexOfChild(barContainer);

                originalBarBackground = barContainer.getBackground();
                // The video is the background, the controls on top of it already dim it.
                barContainer.setBackground(null);
                parent.bringChildToFront(barContainer);
            } else {
                barContainer.setBackground(originalBarBackground);
                originalBarBackground = null;

                final int childCount = parent.getChildCount();
                if (parent.indexOfChild(barContainer) == childCount - 1) {
                    // Moving everything the bar jumped over back to the front, in order, leaves
                    // the bar itself at the index it started from.
                    for (int i = barIndexInParent; i < childCount - 1; i++) {
                        parent.bringChildToFront(parent.getChildAt(barIndexInParent));
                    }
                }
            }
        }

        barDrawsOverPlayer = over;
    }

    @Nullable
    private static View watchPlayer() {
        View watchPlayer = watchPlayerRef.get();
        if (watchPlayer != null) return watchPlayer;

        if (controlsRef.get() == null) return null;

        watchPlayer = findInWindow("watch_player");
        if (watchPlayer == null) {
            Logger.printDebug(() -> "Could not find the player view");
            return null;
        }

        watchPlayerRef = new WeakReference<>(watchPlayer);

        return watchPlayer;
    }

    private static void showControls(boolean show) {
        ViewGroup controls = controlsRef.get();
        if (controls == null) return;

        controls.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private static void refreshContents() {
        updateText();
        updatePlayPauseIcon(false);
    }

    private static void updateText() {
        setText(titleRef.get(), VideoInformation.getVideoTitle());

        TextView subtitle = subtitleRef.get();
        if (setText(subtitle, VideoInformation.getChannelName())) {
            subtitle.setVisibility(View.VISIBLE);
        }
    }

    private static boolean setText(TextView view, String text) {
        if (view == null) return false;

        if (!text.contentEquals(view.getText())) {
            view.setText(text);
        }

        return true;
    }

    /**
     * @param morph Whether the state changed while the bar was up, which is the only time
     *              animating the icon makes sense.
     */
    private static void updatePlayPauseIcon(boolean morph) {
        ImageView playPause = playPauseRef.get();
        if (playPause == null) return;

        if (morph && startIconMorph(playPause)) return;

        if (playing) {
            setIcon(playPause, "yt_fill_experimental_pause_vd_theme_24",
                    "yt_fill_pause_vd_theme_24");
        } else {
            setIcon(playPause, "yt_fill_experimental_play_vd_theme_24",
                    "yt_fill_play_arrow_vd_theme_24");
        }
    }

    /**
     * The touch feedback YouTube puts on these buttons is rectangular, which a round button
     * cannot use, so the background is replaced rather than kept.
     */
    private static void styleButton(View button) {
        final boolean overVideo = getCurrentMiniplayerType() == MINIMAL_BAR_2;

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        // Beside the video this follows the theme, so a custom app color carries into the bar.
        // On the video it is a translucent scrim instead, which the video shows through.
        // Without text the video is not dimmed, and a light circle is lost on a light video.
        circle.setColor(overVideo
                ? (HIDE_TITLE ? OVERLAY_DARK_BUTTON_COLOR : OVERLAY_BUTTON_COLOR)
                : Utils.adjustColorBrightness(ThemeUtils.getAppBackgroundColor(), 0.9f, 1.25f));

        // 48dp is the touch target, 40dp the button, which also keeps the circles apart.
        Drawable inset = new InsetDrawable(circle, Dim.dp4);
        Drawable ripple = new InsetDrawable(new ShapeDrawable(new OvalShape()), Dim.dp4);

        final int foreground = overVideo
                ? OVERLAY_PRIMARY_COLOR
                : ThemeUtils.getAppForegroundColor();
        RippleDrawable background = new RippleDrawable(
                ColorStateList.valueOf(Color.argb(60, Color.red(foreground),
                        Color.green(foreground), Color.blue(foreground))),
                inset,
                ripple);
        // Layers nest their padding, which would inset the mask a second time and draw the
        // ripple smaller than the circle it belongs to.
        background.setPaddingMode(LayerDrawable.PADDING_MODE_STACK);

        button.setBackground(background);

        ViewAnimations.applyPressEffect(button);
    }

    /**
     * The app ships the drawable its own player uses to morph between the two icons.
     */
    private static boolean startIconMorph(ImageView view) {
        final String name = playing
                ? "player_play_pause_vector_transition"
                : "player_pause_play_vector_transition";

        Drawable drawable = getDrawable(view, name + "_delhi", name);

        if (!(drawable instanceof AnimatedVectorDrawable morph)) return false;

        view.setImageDrawable(morph);
        morph.start();

        return true;
    }

    private static void setOverlayTextColor(@Nullable TextView view, int color) {
        if (view != null) {
            view.setTextColor(color);
            // The dimming alone is too light for white text on a light video.
            view.setShadowLayer(Dim.dp4, 0, 0, OVERLAY_TEXT_SHADOW_COLOR);
        }
    }

    /**
     * The bar layout tints its icons with the app text color, which is dark in a light theme.
     */
    private static void setIconColor(@Nullable ImageView view) {
        if (view != null) {
            view.setImageTintList(ColorStateList.valueOf(OVERLAY_PRIMARY_COLOR));
        }
    }

    /**
     * The bar layout still points at the thin icon set, so the bold one is used when the app has it.
     */
    private static void setIcon(ImageView view, String boldName, String legacyName) {
        Drawable drawable = getDrawable(view, boldName, legacyName);
        if (drawable != null) {
            view.setImageDrawable(drawable);
        }
    }

    /**
     * From the context of the view it goes on. Some are filled with a YouTube theme attribute,
     * and the extension context has no theme once an app language is set.
     */
    @Nullable
    private static Drawable getDrawable(View view, String name, String fallbackName) {
        int identifier = ResourceUtils.getDrawableIdentifier(name);
        if (identifier == 0) {
            identifier = ResourceUtils.getDrawableIdentifier(fallbackName);
        }

        if (identifier == 0) {
            Logger.printException(() -> "Could not find drawable: " + fallbackName);
            return null;
        }

        return view.getContext().getDrawable(identifier);
    }
}
