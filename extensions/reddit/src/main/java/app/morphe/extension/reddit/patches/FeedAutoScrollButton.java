/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4958
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.ColorInt;

import com.reddit.typeahead.TypeaheadResultsScreen;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

/**
 * Play and stop button of {@link FeedAutoScrollPatch}.
 */
final class FeedAutoScrollButton {

    private static final int FRAME_CONTAINER_ID = ResourceUtils.getIdentifier(ResourceType.ID, "frame_container");
    private static final int PLAY_ICON = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "icon_play_fill");
    private static final int STOP_ICON = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "icon_stop_fill");

    private static final String OVERLAY_TAG = "morphe_feed_auto_scroll_overlay";

    /**
     * Space below the button on phones, so it is above the navigation bar.
     */
    private static final float PHONE_BOTTOM_MARGIN_DP = 72;

    /**
     * The button is hidden when less of the navigation bar is shown. Phones only.
     */
    private static final float NAV_BAR_SHOWN_FRACTION = 0.5f;

    private static final float RIPPLE_ALPHA = 0.25f;
    private static final float TABLET_BACKGROUND_ALPHA = 0.12f;

    private static boolean isResumed;
    private static boolean isNavBarShown = true;
    private static WeakReference<ImageView> buttonRef = new WeakReference<>(null);
    private static final List<WeakReference<View>> postDetailPagerViews = new ArrayList<>();
    private static boolean isSearchScreenShown;

    /**
     * Covers the screen and gets touches first. Touches outside the button stop scrolling
     * and go to the views below.
     */
    private static final class Overlay extends FrameLayout {
        private final ImageView button;
        private final Rect buttonRect = new Rect();
        private boolean touchingButton;

        Overlay(Context context) {
            super(context);
            setTag(OVERLAY_TAG);

            button = new ImageView(context);
            button.setPadding(Dim.dp12, Dim.dp12, Dim.dp12, Dim.dp12);
            button.setScaleType(ImageView.ScaleType.FIT_CENTER);
            button.setOnClickListener(v -> {
                try {
                    if (FeedAutoScrollPatch.isScrolling()) {
                        FeedAutoScrollPatch.stop();
                    } else {
                        FeedAutoScrollPatch.start(v.getContext());
                    }
                } catch (Exception ex) {
                    Logger.printException(() -> "onClick failure", ex);
                }
            });
            addView(button, new LayoutParams(Dim.dp48, Dim.dp48, Gravity.BOTTOM | Gravity.LEFT));
            setButtonMargins(0, 0);

            setOnApplyWindowInsetsListener((v, insets) -> {
                try {
                    updateButtonMargins();
                } catch (Exception ex) {
                    Logger.printException(() -> "onApplyWindowInsets failure", ex);
                }
                return insets;
            });
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                updateButtonMargins();
            } catch (Exception ex) {
                Logger.printException(() -> "onAttachedToWindow failure", ex);
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent event) {
            try {
                final int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    button.getHitRect(buttonRect);
                    touchingButton = button.getVisibility() == VISIBLE
                            && buttonRect.contains((int) event.getX(), (int) event.getY());
                    if (!touchingButton) {
                        FeedAutoScrollPatch.stop();
                    }
                }

                if (!touchingButton) {
                    return false;
                }

                final boolean handled = super.dispatchTouchEvent(event);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    touchingButton = false;
                }
                return handled;
            } catch (Exception ex) {
                Logger.printException(() -> "dispatchTouchEvent failure", ex);
                return false;
            }
        }

        /**
         * Places the button above the system bars. The insets of the window are used, because the
         * app can consume the insets before they reach the overlay.
         */
        private void updateButtonMargins() {
            WindowInsets insets = getRootWindowInsets();
            if (insets == null) {
                return;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
                setButtonMargins(systemBars.left, systemBars.bottom);
            } else {
                setButtonMargins(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetBottom());
            }
        }

        private void setButtonMargins(int leftInset, int bottomInset) {
            final boolean isTablet = Utils.isTablet();
            LayoutParams params = (LayoutParams) button.getLayoutParams();
            params.leftMargin = leftInset + (isTablet ? Dim.dp24 : Dim.dp16);
            params.bottomMargin = bottomInset + (isTablet ? Dim.dp24 : Dim.dp(PHONE_BOTTOM_MARGIN_DP));
            button.setLayoutParams(params);
        }

        /**
         * Sets the colors of the current app theme.
         */
        private void applyStyle(Context context) {
            final int iconColor = getThemeColor(context, android.R.attr.textColorPrimary, Color.WHITE);
            button.setColorFilter(iconColor);

            final int backgroundColor;
            if (Utils.isTablet()) {
                backgroundColor = withAlpha(iconColor, TABLET_BACKGROUND_ALPHA);
                button.setElevation(0);
            } else {
                backgroundColor = getThemeColor(context, android.R.attr.colorBackground, Color.BLACK);
                button.setElevation(Dim.dp6);
                button.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
            }

            GradientDrawable content = new GradientDrawable();
            content.setShape(GradientDrawable.OVAL);
            content.setColor(backgroundColor);

            GradientDrawable mask = new GradientDrawable();
            mask.setShape(GradientDrawable.OVAL);
            mask.setColor(Color.WHITE);

            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(withAlpha(iconColor, RIPPLE_ALPHA)), content, mask));
        }
    }

    @ColorInt
    private static int withAlpha(@ColorInt int color, float alpha) {
        return Color.argb(Math.round(Color.alpha(color) * alpha),
                Color.red(color), Color.green(color), Color.blue(color));
    }

    @ColorInt
    private static int getThemeColor(Context context, int attribute, @ColorInt int defaultColor) {
        TypedValue value = new TypedValue();
        if (!context.getTheme().resolveAttribute(attribute, value, true)) {
            return defaultColor;
        }
        if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
            return value.data;
        }
        return value.resourceId != 0
                ? context.getColor(value.resourceId)
                : defaultColor;
    }

    static void onMainActivityResumed(Activity activity) {
        isResumed = true;

        ViewGroup container = activity.findViewById(FRAME_CONTAINER_ID);
        if (container == null) {
            Logger.printDebug(() -> "No frame container");
            return;
        }

        Overlay overlay = container.findViewWithTag(OVERLAY_TAG);
        if (overlay == null) {
            overlay = new Overlay(activity);
            container.addView(overlay, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            // Screens opened since are added above the overlay.
            overlay.bringToFront();
        }
        overlay.updateButtonMargins();
        // The app theme can change while the activity is paused.
        overlay.applyStyle(activity);

        buttonRef = new WeakReference<>(overlay.button);
        onScrollStateChanged();
        updateVisibility();
    }

    static void onMainActivityPaused() {
        isResumed = false;
        updateVisibility();
    }

    static void onNavBarStateChange(float fraction) {
        if (Utils.isTablet()) {
            return;
        }

        final boolean shown = fraction > NAV_BAR_SHOWN_FRACTION;
        if (isNavBarShown != shown) {
            isNavBarShown = shown;
            updateVisibility();
        }
    }

    static void onPostDetailPagerViewCreated(View view) {
        postDetailPagerViews.add(new WeakReference<>(view));

        view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                try {
                    updateVisibility();
                } catch (Exception ex) {
                    Logger.printException(() -> "onViewAttachedToWindow failure", ex);
                }
            }

            @Override
            public void onViewDetachedFromWindow(View v) {
                try {
                    updateVisibility();
                } catch (Exception ex) {
                    Logger.printException(() -> "onViewDetachedFromWindow failure", ex);
                }
            }
        });

        updateVisibility();
    }

    static void onScreenAttachChanged(Object screen, boolean attached) {
        if (!(screen instanceof TypeaheadResultsScreen)) {
            return;
        }

        isSearchScreenShown = attached;
        updateVisibility();
    }

    private static boolean isPostDetailPagerShown() {
        boolean shown = false;
        for (Iterator<WeakReference<View>> iterator = postDetailPagerViews.iterator(); iterator.hasNext(); ) {
            View view = iterator.next().get();
            if (view == null) {
                iterator.remove();
            } else if (view.isAttachedToWindow()) {
                shown = true;
            }
        }
        return shown;
    }

    /**
     * Shows the button if a feed is on screen, and stops scrolling if the button is hidden.
     */
    static void updateVisibility() {
        final boolean show = isResumed
                && (isNavBarShown || Utils.isTablet())
                && !isPostDetailPagerShown()
                && !isSearchScreenShown
                && FeedAutoScrollPatch.hasOnScreenFeed();
        if (!show) {
            FeedAutoScrollPatch.stop();
        }

        ImageView button = buttonRef.get();
        if (button != null) {
            button.setVisibility(show ? View.VISIBLE : View.GONE);
        }
    }

    static void onScrollStateChanged() {
        ImageView button = buttonRef.get();
        if (button == null) {
            return;
        }

        final boolean scrolling = FeedAutoScrollPatch.isScrolling();
        button.setImageResource(scrolling ? STOP_ICON : PLAY_ICON);
        button.setContentDescription(str(scrolling
                ? "morphe_feed_auto_scroll_stop"
                : "morphe_feed_auto_scroll_start"));
    }
}
