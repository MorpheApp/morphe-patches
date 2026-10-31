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
import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.AnimatedVectorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Build;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.ViewAnimations;
import app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.videoplayer.PlayPauseIcons;
import app.morphe.extension.youtube.videoplayer.PlayerIcons;

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
    private static int videoFormatWidth = 0;
    private static int videoFormatHeight = 0;

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
    private static final Rect barSpan = new Rect();
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
     * A copy of the bar background, drawn beside the video while the player slides.
     */
    @Nullable
    private static Drawable barFill;

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
     * Whether the bar was dragged to the top of the screen. Kept while the player stays
     * minimized, so a change of the window keeps it there, and dropped once it is expanded.
     */
    private static boolean barAtTop;

    /**
     * Do not set it to 1.00f, otherwise it will cause artifacts (e.g: with the controls fading).
     */
    private static final float maxDragProgress = 0.95f;

    /**
     * The last title and channel shown, kept while a new video has not loaded its own yet.
     */
    private static String videoTitle = "";
    private static String channelName = "";

    /**
     * The title last set to the title view. The view can show another text, as its title is
     * replaced by {@link RestoreOriginalTitlesPatch} if enabled (original or DeArrow title),
     * so it is set only when it changes.
     */
    @Nullable
    private static String shownTitle;

    /**
     * Injection point.
     * <p>
     * The legacy bar layout, looked up by YouTube itself and then left unused.
     */
    @SuppressLint("ClickableViewAccessibility")
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
            shownTitle = null;

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
                setIcon(close, "morphe_player_close",
                        "yt_outline_experimental_x_vd_theme_24", "yt_outline_x_black_24");
                styleButton(close);
            }

            View subtitleBar = Utils.getChildViewByResourceName(controlsLayout, "floaty_subtitle_bar");
            if (subtitleBar != null) {
                subtitleBar.setVisibility(HIDE_TITLE ? View.GONE : View.VISIBLE);
            }

            if (getCurrentMiniplayerType() == MINIMAL_BAR) {
                startAfterVideo(title);
                startAfterVideo(subtitleBar);

                // Taken again for each new layout, as the theme may have changed.
                if (barFill != null) {
                    barFill.setBounds(0, 0, 0, 0);
                    barFill = null;
                }
                View barContainer = barContainerRef.get();
                Drawable barBackground = barContainer != null ? barContainer.getBackground() : null;
                if (barBackground != null && barBackground.getConstantState() != null) {
                    barFill = barBackground.getConstantState().newDrawable();
                }
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

            // YouTube reads a gesture against the shape of its own miniplayer, which the bar is not,
            // so the bar answers for itself: a tap expands, a drag moves the bar between the bottom
            // and the top of the screen, and a drag down from the bottom closes it.
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

                            if (-draggedUp > slop && !barAtTop) {
                                closeBar();
                            } else if (Math.abs(draggedUp) > slop) {
                                // Already at the top, a drag up has nowhere to go. Nor while the bar
                                // is animating, which can be the bar leaving before it is closed.
                                final boolean toTop = draggedUp > 0;
                                if (toTop == barAtTop || morphing || !barShapeApplied
                                        || boundsControllerRef.get() == null || lastBounds.isEmpty()) {
                                    return true;
                                }

                                barAtTop = toTop;
                                barBoundsFor(lastBounds, toTop);
                                morphFrom.set(currentBounds);
                                morphTo.set(barBounds);
                                runMorph(false, () -> {});
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

            listenToWindowLayout(controlsLayout);

            if (!listenersInstalled) {
                listenersInstalled = true;

                PlayerType.getOnChange().addObserver((PlayerType type) -> {
                    final boolean minimized = type == PlayerType.WATCH_WHILE_MINIMIZED;

                    Utils.runOnMainThreadNowOrLater(() -> {
                        startTicking(minimized);

                        if (minimized) {
                            refreshContents();

                            // Animates whatever shape the player was left in into the bar. A slow
                            // collapse settles the bounds while the player still reports itself as
                            // sliding, so nothing else sets them again.
                            try {
                                cancelMorph();

                                if (!lastBounds.isEmpty()) {
                                    MiniplayerBoundsController controller = boundsControllerRef.get();
                                    if (controller == null) {
                                        Logger.printDebug(() -> "No miniplayer bounds controller");
                                    } else {
                                        morphFrom.set(currentBounds.isEmpty() ? lastBounds : currentBounds);
                                        barBoundsFor(lastBounds, barAtTop);
                                        morphTo.set(barBounds);
                                        barShapeApplied = true;
                                        showControls(true);

                                        if (morphFrom.equals(morphTo)) {
                                            setBounds(controller, morphTo);
                                            updateVideoClip();
                                        } else {
                                            setContentAlpha(0f);
                                            runMorph(true, () -> setContentAlpha(1f));
                                        }
                                    }
                                }
                            } catch (Exception ex) {
                                morphing = false;
                                Logger.printException(() -> "Apply bar shape failure", ex);
                            }
                        } else {
                            barShapeApplied = false;
                            // Still needed while the player grows from the top.
                            if (type != PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED) {
                                barAtTop = false;
                            }

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
                });
            }

            final boolean minimized = PlayerType.getCurrent() == PlayerType.WATCH_WHILE_MINIMIZED;

            updateVideoClip();
            startTicking(minimized);
            refreshContents();
            showControls(minimized);
            if (minimized && !morphing) {
                setContentAlpha(1f);
            }

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

        if (!ENABLED) {
            return originalBounds;
        }

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
                // Keeps a miniplayer other than the bar above the keyboard and the system
                // navigation buttons, which YouTube rests it behind when the layout that holds it
                // is not shrunk to leave them out.
                if (applyingBounds) {
                    // Ours, already moved.
                    return original;
                }

                if (PlayerType.getCurrent() != PlayerType.WATCH_WHILE_MINIMIZED) {
                    // Growing into the full player, which is laid out behind them on purpose.
                    restingBounds.setEmpty();
                    return original;
                }

                View playerLayout = otherPlayerLayout();
                if (playerLayout != null) {
                    listenToWindowLayout(playerLayout);
                }

                restingBounds.set(original);
                return aboveWindowBottom(original, playerLayout);
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

            // Prevents the video from anchoring into one of the display corners by spanning the
            // bounds to full display width, making the transition to miniplayer smoother.
            updateBarSpan();
            dockedBounds.set(barSpan.left, original.top, barSpan.right, original.bottom);
            lastBounds.set(dockedBounds);
            PlayerType getCurrent = PlayerType.getCurrent();
            if (getCurrent == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED) {
                barBoundsFor(dockedBounds, false);

                final int maxDragTop = barBounds.top > 0 ? barBounds.top : dockedBounds.top;
                final float progress = maxDragTop > 0
                        ? Math.min(maxDragProgress, Math.max(0.0f, (float) original.top / maxDragTop))
                        : maxDragProgress;

                // YouTube slides from where it rests at the bottom, which is what the progress
                // is measured against, but a bar moved to the top grows from there.
                if (barAtTop) {
                    barBoundsFor(dockedBounds, true);
                }

                final int currentLeft = interpolate(dockedBounds.left, barBounds.left, progress);
                final int currentTop = interpolate(dockedBounds.top, barBounds.top, progress);
                final int currentRight = interpolate(dockedBounds.right, barBounds.right, progress);
                final int currentBottom = interpolate(dockedBounds.bottom, barBounds.bottom, progress);

                currentBounds.set(new Rect(currentLeft, currentTop, currentRight, currentBottom));

                return currentBounds;
            }
            
            // While the player is up, this is only asked when the window changes, such as the
            // keyboard closing or a rotation. What YouTube gets back is where the next collapse
            // ends, so it is the bar for the new window, as it would be had nothing changed.
            final boolean minimized = getCurrent == PlayerType.WATCH_WHILE_MINIMIZED;
            if (minimized || getCurrent.isMaximizedOrFullscreen()) {
                barBoundsFor(dockedBounds, barAtTop);
                currentBounds.set(barBounds);
                if (minimized) {
                    barShapeApplied = true;
                }
                return barBounds;
            }

            currentBounds.set(dockedBounds);
            return dockedBounds;
        } catch (Exception ex) {
            Logger.printException(() -> "getMinimalBarBounds failure", ex);
        }

        return original;
    }

    /**
     * @param atTop Whether the bar rests at the top of the layout instead of the bottom.
     */
    private static void barBoundsFor(Rect resting, boolean atTop) {
        View playerLayout = barPlayerLayout();

        // A floating miniplayer keeps a margin to the navigation bar, a docked bar sits against it.
        // While the navigation bar is away, YouTube's own resting edge is already the right one.
        int dockedBottom = resting.bottom;

        View navigationBar = navigationBarRef.get();
        if (navigationBar == null) {
            navigationBar = findInWindow("bottom_bar_container");
            if (navigationBar != null) {
                navigationBarRef = new WeakReference<>(navigationBar);
                navigationBar.removeOnLayoutChangeListener(navigationBarLayoutListener);
                navigationBar.addOnLayoutChangeListener(navigationBarLayoutListener);
            }
        }

        // A rail beside the content says nothing about where the bar ends.
        if (navigationBar != null && navigationBar.isShown()
                && navigationBar.getWidth() >= navigationBar.getHeight()) {
            navigationBar.getLocationInWindow(windowLocation);

            // Where it rests, not where it is. YouTube slides it away while the feed scrolls, and
            // a position taken mid-slide leaves the bar underneath it once it comes back.
            final int top = windowLocation[1] - Math.round(navigationBar.getTranslationY());

            // The bounds are relative to the layout that holds the player, not to the window. That
            // layout can start below the status bar, which would otherwise move the bar down by its
            // height and put it under the navigation bar.
            int origin = 0;
            if (playerLayout != null) {
                playerLayout.getLocationInWindow(windowLocation);
                origin = windowLocation[1];
            }

            // Docked against it even when YouTube rests lower. After a recreated activity its
            // resting bounds are still the ones from before the system insets moved everything up.
            dockedBottom = top - origin;
        }

        final int bottom = Math.min(dockedBottom, windowBottomFor(playerLayout)) - BAR_MARGIN;

        updateBarSpan();
        barBounds.set(barSpan.left + BAR_MARGIN, bottom - BAR_HEIGHT,
                barSpan.right - BAR_MARGIN, bottom);

        if (atTop) {
            // Below the status bar and the display cutout.
            barBounds.offsetTo(barBounds.left, barSpan.top + BAR_MARGIN);
        }
    }

    /**
     * Where a bar can span, relative to the layout that holds the player: the sides, and the top
     * edge a bar moved up rests below. Not the display metrics, which measure the whole display.
     * In landscape the window leaves out the display cutout, such as a front camera hole, and the
     * system navigation buttons can sit beside the content, so a bar as wide as the display is cut
     * off there. The window also differs from the display in split screen and on foldables.
     */
    @SuppressWarnings("deprecation")
    private static void updateBarSpan() {
        barSpan.set(0, 0, Dim.getMetrics().widthPixels, 0);

        try {
            View watchPlayer = watchPlayer();
            if (watchPlayer == null || !(watchPlayer.getParent() instanceof View parent)) return;

            final int parentWidth = parent.getWidth();
            View root = parent.getRootView();
            WindowInsets insets = parent.getRootWindowInsets();
            if (parentWidth <= 0 || root == null || insets == null) return;

            int insetLeft;
            int insetTop;
            int insetRight;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemInsets = insets.getInsets(
                        WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                insetLeft = systemInsets.left;
                insetTop = systemInsets.top;
                insetRight = systemInsets.right;
            } else {
                insetLeft = insets.getSystemWindowInsetLeft();
                insetTop = insets.getSystemWindowInsetTop();
                insetRight = insets.getSystemWindowInsetRight();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    DisplayCutout cutout = insets.getDisplayCutout();
                    if (cutout != null) {
                        insetLeft = Math.max(insetLeft, cutout.getSafeInsetLeft());
                        insetTop = Math.max(insetTop, cutout.getSafeInsetTop());
                        insetRight = Math.max(insetRight, cutout.getSafeInsetRight());
                    }
                }
            }

            // The insets are relative to the window, the bounds to the layout that holds the player.
            parent.getLocationInWindow(windowLocation);
            final int left = Math.max(0, insetLeft - windowLocation[0]);
            final int right = Math.min(parentWidth, root.getWidth() - insetRight - windowLocation[0]);

            if (right - left > 2 * BAR_MARGIN) {
                barSpan.set(left, Math.max(0, insetTop - windowLocation[1]), right, 0);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateBarSpan failure", ex);
        }
    }

    /**
     * The lowest a miniplayer can rest in the layout that holds it, above the keyboard and the
     * system navigation buttons. That layout is no longer shrunk to leave them out when the
     * Navigation bar patch removes the keyboard margin of the page, or draws the window behind
     * the system navigation buttons for the translucent navigation bar, and YouTube then rests
     * the player behind them. Measured against them directly, so nothing changes when the layout
     * already ends above them.
     * <p>
     * The keyboard is always measured where the window insets have it. With the feature flags of
     * the translucent navigation bar left to the account, YouTube shrinks that layout by the
     * keyboard height less the system navigation buttons, so it still ends behind the keyboard.
     *
     * @param playerLayout The layout that holds the player, which the bounds are relative to.
     */
    @SuppressWarnings("deprecation")
    private static int windowBottomFor(@Nullable View playerLayout) {
        if (playerLayout == null) return Integer.MAX_VALUE;

        final boolean keyboardMarginRemoved = NavigationBarPatch.isKeyboardMarginRemoved();
        final boolean translucentNavigationBar = NavigationBarPatch.isTranslucentNavigationBarForced();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R
                && !keyboardMarginRemoved && !translucentNavigationBar) {
            return Integer.MAX_VALUE;
        }

        WindowInsets insets = playerLayout.getRootWindowInsets();
        if (insets == null) return Integer.MAX_VALUE;

        final int insetBottom;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            insetBottom = insets.getInsets(WindowInsets.Type.ime()
                    | (translucentNavigationBar ? WindowInsets.Type.navigationBars() : 0)).bottom;
        } else {
            // The translucent navigation bar is only used from Android 12, so this is the keyboard.
            insetBottom = insets.getSystemWindowInsetBottom();
        }
        if (insetBottom <= 0) return Integer.MAX_VALUE;

        // The insets are relative to the window, the bounds to the layout that holds the player.
        playerLayout.getLocationInWindow(windowLocation);

        return playerLayout.getRootView().getHeight() - insetBottom - windowLocation[1];
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
                // Always at the left edge. In a right-to-left layout YouTube mirrors the rect
                // itself, which already puts the video at the end of the bar.
                videoRect.set(currentBounds);
                videoRect.right = videoRect.left + videoWidthFor(currentBounds.height());
            } else {
                // Type 2 spans the bar with the video. YouTube fits anything that is not 16:9
                // inside the bar instead, and the miniplayer has no background of its own, so
                // the feed shows through beside it. The overflow is clipped away again.
                final float currentVideoRatio = (videoFormatWidth > 0 && videoFormatHeight > 0)
                        ? (float) videoFormatWidth / videoFormatHeight
                        : 16f / 9f;

                final float barWidth = currentBounds.width();
                final float barHeight = currentBounds.height();
                final float barAspectRatio = barWidth / barHeight;

                videoRect.set(currentBounds);

                if (currentVideoRatio > barAspectRatio) {
                    final float scale = barHeight / (barWidth / currentVideoRatio);
                    final int overflowX = Math.round(barWidth * (scale - 1f)) / 2;
                    videoRect.left -= overflowX;
                    videoRect.right += overflowX;
                } else {
                    final float scale = barWidth / (barHeight * currentVideoRatio);
                    final int overflowY = Math.round(barHeight * (scale - 1f)) / 2;
                    videoRect.top -= overflowY;
                    videoRect.bottom += overflowY;
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
        // Also kept for the other miniplayer types, to move them above the keyboard.
        boundsControllerRef = new WeakReference<>(controller);
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
        animator.addUpdateListener(value -> {
            MiniplayerBoundsController controller = boundsControllerRef.get();
            if (controller == null) {
                cancelMorph();
                return;
            }

            final float fraction = (float) value.getAnimatedValue();
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
        });
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
        // A bar moved to the top leaves through the top, the nearest edge.
        morphTo.offset(0, barAtTop
                ? -currentBounds.bottom
                : Dim.getScreenHeight() - currentBounds.top);

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

    /**
     * For the views outside the bar layout, which the bar only reaches through the window.
     */
    @Nullable
    private static View findInWindow(String name) {
        ViewGroup controls = controlsRef.get();
        if (controls == null) return null;

        return Utils.getChildViewByResourceName(controls.getRootView(), name);
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

    /**
     * The player changes size for every frame of a slide without asking for its bounds.
     */
    private static final View.OnLayoutChangeListener watchPlayerLayoutListener =
            (view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) ->
                    updateBarFill(view);

    /**
     * The window bottom the miniplayer was last placed against.
     */
    private static int lastWindowBottom = Integer.MAX_VALUE;

    private static WeakReference<ViewTreeObserver> windowObserverRef = new WeakReference<>(null);

    /**
     * The bounds YouTube last gave a miniplayer other than the bar, while minimized.
     */
    private static final Rect restingBounds = new Rect();

    private static WeakReference<View> otherWatchPlayerRef = new WeakReference<>(null);

    /**
     * YouTube asks for the bounds when the navigation bar goes away for the keyboard, which is
     * before the keyboard is reported as shown, and does not ask again once it is.
     */
    private static final ViewTreeObserver.OnGlobalLayoutListener windowLayoutListener = () -> {
        final int windowBottom = windowBottomFor(ENABLED ? barPlayerLayout() : otherPlayerLayout());
        if (windowBottom == lastWindowBottom) return;
        lastWindowBottom = windowBottom;

        // Not from inside the layout pass the change is reported from.
        if (ENABLED) {
            Utils.runOnMainThread(MinimalMiniplayerPatch::reapplyBarBounds);
            return;
        }

        Utils.runOnMainThread(() -> {
            if (restingBounds.isEmpty() || PlayerType.getCurrent() != PlayerType.WATCH_WHILE_MINIMIZED) {
                return;
            }

            MiniplayerBoundsController controller = boundsControllerRef.get();
            if (controller == null) return;

            applyingBounds = true;
            try {
                controller.patch_setBounds(aboveWindowBottom(new Rect(restingBounds), otherPlayerLayout()));
            } finally {
                applyingBounds = false;
            }
        });
    };

    private static void listenToWindowLayout(View view) {
        ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer == windowObserverRef.get()) return;
        windowObserverRef = new WeakReference<>(observer);

        observer.removeOnGlobalLayoutListener(windowLayoutListener);
        observer.addOnGlobalLayoutListener(windowLayoutListener);
    }

    /**
     * A new rect when moved, as YouTube can keep the one it gets back.
     */
    private static Rect aboveWindowBottom(Rect bounds, @Nullable View playerLayout) {
        // The margin YouTube itself keeps around its floating miniplayer.
        final int lowestBottom = windowBottomFor(playerLayout) - BAR_MARGIN;
        if (bounds.bottom <= lowestBottom) return bounds;

        Rect moved = new Rect(bounds);
        moved.offset(0, lowestBottom - bounds.bottom);
        return moved;
    }

    @Nullable
    private static View barPlayerLayout() {
        View watchPlayer = watchPlayer();
        return watchPlayer != null && watchPlayer.getParent() instanceof View parent ? parent : null;
    }

    /**
     * The other miniplayer types have no bar layout to reach the window through.
     */
    @Nullable
    private static View otherPlayerLayout() {
        View watchPlayer = otherWatchPlayerRef.get();
        // A recreated activity brings its own.
        if (watchPlayer == null || !watchPlayer.isAttachedToWindow()) {
            Activity activity = Utils.getActivity();
            if (activity == null) return null;

            watchPlayer = Utils.getChildViewByResourceName(activity.getWindow().getDecorView(), "watch_player");
            if (watchPlayer == null) return null;

            otherWatchPlayerRef = new WeakReference<>(watchPlayer);
        }

        return watchPlayer.getParent() instanceof View parent ? parent : null;
    }

    private static void reapplyBarBounds() {
        if (morphing || lastBounds.isEmpty()) return;
        
        // While the player is up the bar is where the next collapse ends. The keyboard closing
        // under it moves the navigation bar only after YouTube asked where that is.
        if (!barShapeApplied && !PlayerType.getCurrent().isMaximizedOrFullscreen()) return;

        MiniplayerBoundsController controller = boundsControllerRef.get();
        if (controller == null) return;

        barBoundsFor(lastBounds, barAtTop);
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
        if (!ENABLED) return;

        final boolean isPlaying = contentDescriptionId == PAUSE_DESCRIPTION;
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
            // Type 2 draws the bar on the video, and the player holds a SurfaceView that punches
            // a hole through whatever was drawn before it, so the bar has to come after the player
            // in the watch layout. Elevation is no use, this layout draws its children through
            // its own drawChild and does not order them by it.
            // The order is put back when the bar goes away. It also decides who gets a touch first,
            // and a bar left in front swallows the taps that should reach the expanded player.
            final boolean over = inBarMode();
            if (barDrawsOverPlayer != over) {
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
                            // Moving everything the bar jumped over back to the front, in order,
                            // leaves the bar itself at the index it started from.
                            for (int i = barIndexInParent; i < childCount - 1; i++) {
                                parent.bringChildToFront(parent.getChildAt(barIndexInParent));
                            }
                        }
                    }
                }

                barDrawsOverPlayer = over;
            }
        }

        final int height = currentBounds.height();
        final int width = currentBounds.width();

        if (getCurrentMiniplayerType() == MINIMAL_BAR) {
            final int videoWidth = videoWidthFor(height);

            updateBarFill(watchPlayer);

            // Only a bar is far wider than the video it holds. Going by the state instead would
            // uncover the video for as long as the player still holds the bar shape. Ads are
            // uncovered as well, their skip button is part of the overlay this hides.
            boolean uncover = !inBarMode() || height <= 0 || width <= videoWidth;
            if (!uncover) {
                View skipAd = skipAdRef.get();
                if (skipAd == null) {
                    skipAd = findInWindow("modern_miniplayer_skip_ad_button");
                    if (skipAd != null) {
                        skipAdRef = new WeakReference<>(skipAd);
                    }
                }

                uncover = skipAd != null && skipAd.isShown();
            }

            if (uncover) {
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
     * While sliding the player is not clipped yet, and YouTube paints it black beside the video
     * whatever the theme is. The bar background goes over that, so the bar has the same color
     * from the start of the slide to the end of the morph.
     * <p>
     * Taken from the view and not the bounds, which only say where the slide ends. YouTube
     * moves the player and the video towards there together, so the video stays 16:9.
     */
    private static void updateBarFill(View watchPlayer) {
        if (barFill == null) return;

        final int width = watchPlayer.getWidth();
        final int height = watchPlayer.getHeight();
        final int videoWidth = videoWidthFor(height);

        if (PlayerType.getCurrent() != PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_MAXIMIZED
                || height <= 0 || width <= videoWidth) {
            barFill.setBounds(0, 0, 0, 0);
        } else if (Utils.isRightToLeftLocale()) {
            barFill.setBounds(0, 0, width - videoWidth, height);
        } else {
            barFill.setBounds(videoWidth, 0, width, height);
        }
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

        if (barFill != null) {
            watchPlayer.getOverlay().add(barFill);
            watchPlayer.removeOnLayoutChangeListener(watchPlayerLayoutListener);
            watchPlayer.addOnLayoutChangeListener(watchPlayerLayoutListener);
        }

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
        String title = VideoInformation.getMiniplayerVideoName();
        if (!title.isEmpty()) {
            videoTitle = title;
        }

        String channel = VideoInformation.getMiniplayerChannelName();
        if (!channel.isEmpty()) {
            channelName = channel;
        }

        TextView titleView = titleRef.get();
        if (titleView != null && !videoTitle.equals(shownTitle)) {
            shownTitle = videoTitle;
            setText(titleView, videoTitle);
        }

        TextView subtitle = subtitleRef.get();
        if (setText(subtitle, channelName)) {
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

        if (morph) {
            // The app ships the drawable its own player uses to morph between the two icons.
            final String name = playing
                    ? "player_play_pause_vector_transition"
                    : "player_pause_play_vector_transition";

            Drawable drawable = getDrawable(playPause, name + "_delhi", name);
            if (drawable instanceof AnimatedVectorDrawable iconMorph) {
                // A player icon style swaps its own icons instead, and starts that on its first draw.
                if (playing) {
                    PlayPauseIcons.setPlayToPause(playPause, iconMorph);
                } else {
                    PlayPauseIcons.setPauseToPlay(playPause, iconMorph);
                }
                if (playPause.getDrawable() == iconMorph) {
                    iconMorph.start();
                }

                return;
            }
        }

        if (playing) {
            setIcon(playPause, PlayPauseIcons.PAUSE,
                    "yt_fill_experimental_pause_vd_theme_24", "yt_fill_pause_vd_theme_24");
        } else {
            setIcon(playPause, PlayPauseIcons.PLAY,
                    "yt_fill_experimental_play_vd_theme_24", "yt_fill_play_arrow_vd_theme_24");
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
     * A player icon style other than the app's own replaces both.
     */
    private static void setIcon(ImageView view, String styleBaseName, String boldName, String legacyName) {
        Drawable drawable = PlayerIcons.styledDrawable(styleBaseName);
        if (drawable == null) {
            drawable = getDrawable(view, boldName, legacyName);
        }
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

    /**
     * Injection point.
     * Records the encoded video size.
     */
    public static List<VideoFormat.FormatInterface> setVideoAspectRatio(@NonNull String videoId, @NonNull List<VideoFormat.FormatInterface> adaptiveFormats) {
        if (ShortsPlayerState.isOpen()) {
            Logger.printDebug(() -> "Ignoring shorts video aspect ratio, videoId: " + videoId);
            return adaptiveFormats;
        }

        for (VideoFormat.FormatInterface format : adaptiveFormats) {
            String mimeType = format.patch_getMimeType();
            if (mimeType != null && mimeType.contains("video")) {
                videoFormatWidth = format.patch_getWidth();
                videoFormatHeight = format.patch_getHeight();
            }
        }
        return adaptiveFormats;
    }
}
