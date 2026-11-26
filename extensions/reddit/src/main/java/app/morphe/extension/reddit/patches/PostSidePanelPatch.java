/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4978
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import com.reddit.screen.dialog.ModalBackdropView;

import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
public final class PostSidePanelPatch {

    public enum PanelWidth {
        ONE_THIRD(1f / 3),
        TWO_THIRDS(2f / 3);

        /**
         * Fraction of the feed width.
         */
        final float fraction;

        PanelWidth(float fraction) {
            this.fraction = fraction;
        }
    }

    public enum FeedDim {
        OFF(0f),
        LIGHT(0.38f),
        MEDIUM(0.55f),
        STRONG(0.72f);

        /**
         * Alpha of the backdrop over the feed.
         */
        final float alpha;

        FeedDim(float alpha) {
            this.alpha = alpha;
        }
    }

    /**
     * Window width needed to show a post in a panel.
     */
    private static final int MIN_WINDOW_WIDTH_DP = 840;

    /**
     * Width of the navigation rail next to the feed.
     */
    private static final int NAVIGATION_RAIL_WIDTH_DP = 96;

    /**
     * Minimum width of the panel and of the feed next to it when dragging.
     * Both panel width choices are always within these limits.
     */
    private static final int MIN_DRAGGED_WIDTH_DP = 240;

    /**
     * Width of the touch area of the resize handle.
     */
    private static final int HANDLE_WIDTH_DP = 24;

    /**
     * Height of the touch area of the resize handle.
     */
    private static final int HANDLE_HEIGHT_DP = 96;

    /**
     * Horizontal inset of the visible handle pill within its touch area.
     */
    private static final int HANDLE_INSET_HORIZONTAL_DP = 10;

    /**
     * Vertical inset of the visible handle pill within its touch area.
     */
    private static final int HANDLE_INSET_VERTICAL_DP = 28;

    /**
     * Alpha of the visible handle pill, from 0 to 255.
     */
    private static final int HANDLE_ALPHA = 115;

    private static final long SLIDE_IN_DURATION_MS = 250;

    private static final int MODAL_CONTAINER_ID = ResourceUtils.getIdIdentifier("screen_modal_container");

    /**
     * Before Android 11, the androidx insets dispatch that Reddit uses calls the
     * listener saved in this tag instead of the listener set on the view.
     */
    private static final int ON_APPLY_WINDOW_LISTENER_TAG_ID =
            ResourceUtils.getIdIdentifier("tag_on_apply_window_listener");

    /**
     * Post screens and if each is shown in a panel. Reddit asks a screen for its
     * presentation many times, so the first answer is kept for the lifetime of the screen.
     */
    private static final Map<Object, Boolean> panelScreens = new WeakHashMap<>();

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * Injection point.
     *
     * @return If the post screen is shown in a panel instead of full screen.
     */
    public static boolean usePanel(Object postScreen) {
        try {
            if (!Settings.POST_SIDE_PANEL.get()) {
                return false;
            }

            synchronized (panelScreens) {
                Boolean usePanel = panelScreens.get(postScreen);
                if (usePanel == null) {
                    usePanel = Utils.isTablet() && Utils.getContext().getResources()
                            .getConfiguration().screenWidthDp >= MIN_WINDOW_WIDTH_DP;
                    panelScreens.put(postScreen, usePanel);
                }
                return usePanel;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "usePanel failure", ex);
            return false;
        }
    }

    private static boolean isPanelScreen(Object screen) {
        synchronized (panelScreens) {
            return Boolean.TRUE.equals(panelScreens.get(screen));
        }
    }

    /**
     * Injection point.
     *
     * @param screenView View of any screen. A post screen in a panel is shown as a dialog,
     *                   which is a backdrop with the screen in a container.
     */
    public static void onScreenViewCreated(Object screen, View screenView) {
        try {
            if (!Settings.POST_SIDE_PANEL.get() || !isPanelScreen(screen)) {
                return;
            }

            ModalBackdropView backdrop = (ModalBackdropView) screenView;
            backdrop.setBackdropAlpha(Settings.POST_SIDE_PANEL_DIM.get().alpha);
            removeSystemBarPadding(backdrop);

            // Panel width dragged to, as a fraction of the feed width. 0 if not dragged.
            float[] draggedFraction = {
                    Settings.POST_SIDE_PANEL_REMEMBER_WIDTH.get()
                            ? Settings.POST_SIDE_PANEL_DRAGGED_WIDTH.get()
                            : 0
            };

            // The post screen keeps some sizes from its first layout, such as the media height.
            // Use the window width from the configuration, so the first layout is already a panel.
            final int windowWidth = Dim.dp(backdrop.getResources().getConfiguration().screenWidthDp);
            final int startMargin = getPanelStartMargin(windowWidth, draggedFraction[0]);

            ViewGroup panel = backdrop.findViewById(MODAL_CONTAINER_ID);
            ViewGroup.MarginLayoutParams panelParams = (ViewGroup.MarginLayoutParams) panel.getLayoutParams();
            panelParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            panelParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            panelParams.setMargins(0, 0, 0, 0);
            // The constraint set Reddit applies to the dialog also sets the relative margins.
            panelParams.setMarginStart(startMargin);
            panelParams.setMarginEnd(0);
            panel.setLayoutParams(panelParams);
            panel.setClipToOutline(startMargin > 0);
            panel.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    // Round only the start corners by extending the outline past the end edge.
                    final int radius = Dim.dp16;
                    final int width = view.getWidth();
                    final int height = view.getHeight();
                    if (view.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL) {
                        outline.setRoundRect(-radius, 0, width, height, radius);
                    } else {
                        outline.setRoundRect(0, 0, width + radius, height, radius);
                    }
                }
            });

            View content = panel.getChildAt(0);
            ViewGroup.LayoutParams contentParams = content.getLayoutParams();
            contentParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            contentParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            content.setLayoutParams(contentParams);

            View handle = addResizeHandle(backdrop, panel, panelParams, draggedFraction);
            handle.setVisibility(startMargin > 0 ? View.VISIBLE : View.GONE);

            if (startMargin > 0) {
                // The animation starts when the view is attached.
                final int panelWidth = windowWidth - startMargin;
                panel.setTranslationX(Utils.isRightToLeftLocale() ? -panelWidth : panelWidth);
                panel.animate()
                        .translationX(0)
                        .setDuration(SLIDE_IN_DURATION_MS)
                        .setInterpolator(new DecelerateInterpolator())
                        .start();
            }

            // Window size changes, such as rotating the device.
            // Dragging the panel also lays out the backdrop again, but with the same width.
            backdrop.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                oldLeft, oldTop, oldRight, oldBottom) -> {
                if (right - left == oldRight - oldLeft) {
                    return;
                }

                final int newStartMargin = getPanelStartMargin(right - left, draggedFraction[0]);
                if (newStartMargin == panelParams.getMarginStart()) {
                    return;
                }

                // Layout params cannot change during layout.
                view.post(() -> {
                    panelParams.setMarginStart(newStartMargin);
                    panel.setLayoutParams(panelParams);
                    panel.setClipToOutline(newStartMargin > 0);
                    handle.setVisibility(newStartMargin > 0 ? View.VISIBLE : View.GONE);
                });
            });
        } catch (Exception ex) {
            Logger.printException(() -> "onScreenViewCreated failure", ex);
        }
    }

    /**
     * Adds a handle to the start edge of the panel, to resize the panel by dragging it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private static View addResizeHandle(ModalBackdropView backdrop, ViewGroup panel,
                                        ViewGroup.MarginLayoutParams panelParams,
                                        float[] draggedFraction) {
        View handle = new View(backdrop.getContext());

        final int pillChannel = Utils.isDarkModeEnabled() ? 255 : 0;
        GradientDrawable pill = new GradientDrawable();
        pill.setCornerRadius(Dim.dp2);
        pill.setColor(Color.argb(HANDLE_ALPHA, pillChannel, pillChannel, pillChannel));
        final int insetHorizontal = Dim.dp(HANDLE_INSET_HORIZONTAL_DP);
        final int insetVertical = Dim.dp(HANDLE_INSET_VERTICAL_DP);
        handle.setBackground(new InsetDrawable(pill,
                insetHorizontal, insetVertical, insetHorizontal, insetVertical));
        handle.setContentDescription(str("morphe_post_side_panel_resize_handle"));
        handle.setElevation(Dim.dp4);

        float[] downRawX = new float[1];
        int[] downPanelWidth = new int[1];

        handle.setOnTouchListener((view, event) -> {
            try {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        view.getParent().requestDisallowInterceptTouchEvent(true);
                        downRawX[0] = event.getRawX();
                        downPanelWidth[0] = panel.getWidth();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        final float dx = event.getRawX() - downRawX[0];
                        final int windowWidth = backdrop.getWidth();
                        final int panelWidth = clampDraggedWidth(windowWidth, Math.round(
                                downPanelWidth[0] + (Utils.isRightToLeftLocale() ? dx : -dx)));
                        draggedFraction[0] = panelWidth / (float) getFeedWidth(windowWidth);
                        panelParams.setMarginStart(windowWidth - panelWidth);
                        panel.setLayoutParams(panelParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        view.getParent().requestDisallowInterceptTouchEvent(false);
                        if (draggedFraction[0] > 0 && Settings.POST_SIDE_PANEL_REMEMBER_WIDTH.get()) {
                            Settings.POST_SIDE_PANEL_DRAGGED_WIDTH.save(draggedFraction[0]);
                        }
                        return true;
                    default:
                        return false;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onTouch failure", ex);
                return false;
            }
        });

        panel.addView(handle, new FrameLayout.LayoutParams(
                Dim.dp(HANDLE_WIDTH_DP), Dim.dp(HANDLE_HEIGHT_DP),
                Gravity.START | Gravity.CENTER_VERTICAL));
        return handle;
    }

    private static int getFeedWidth(int windowWidth) {
        return windowWidth - Dim.dp(NAVIGATION_RAIL_WIDTH_DP);
    }

    /**
     * @return Panel width that leaves both the panel and the feed at least the minimum dragged width.
     */
    private static int clampDraggedWidth(int windowWidth, int panelWidth) {
        final int minWidth = Dim.dp(MIN_DRAGGED_WIDTH_DP);
        return Math.max(minWidth, Math.min(getFeedWidth(windowWidth) - minWidth, panelWidth));
    }

    /**
     * Dialogs are padded for the system bars, but the post screen pads itself like it does in full screen.
     */
    private static void removeSystemBarPadding(View backdrop) {
        View.OnApplyWindowInsetsListener listener = (view, insets) -> {
            view.setPadding(0, 0, 0, 0);
            return insets;
        };
        backdrop.setOnApplyWindowInsetsListener(listener);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            backdrop.setTag(ON_APPLY_WINDOW_LISTENER_TAG_ID, listener);
        }
        backdrop.setPadding(0, 0, 0, 0);
    }

    /**
     * @return Start margin of the panel, or 0 to fill a window that is too narrow for a panel.
     */
    private static int getPanelStartMargin(int windowWidth, float draggedFraction) {
        if (windowWidth < Dim.dp(MIN_WINDOW_WIDTH_DP)) {
            return 0;
        }

        final int feedWidth = getFeedWidth(windowWidth);
        if (draggedFraction > 0) {
            // The width may have been dragged in a wider window.
            return windowWidth - clampDraggedWidth(windowWidth, Math.round(feedWidth * draggedFraction));
        }

        return windowWidth - (int) (feedWidth * Settings.POST_SIDE_PANEL_WIDTH.get().fraction);
    }
}
