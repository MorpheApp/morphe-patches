/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3516
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;

@SuppressWarnings("unused")
final class FeedAutoScrollButton {

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String OVERLAY_TAG = "morphe_feed_auto_scroll_overlay";
    private static final float PHONE_BOTTOM_OFFSET_DP = 72f;
    private static final float NAVBAR_SHOWN_THRESHOLD = 0.5f;
    private static final float RIPPLE_ALPHA_RATIO = 0.25f;
    private static final float TABLET_BG_ALPHA_RATIO = 0.12f;

    private static boolean isResumed;
    private static boolean isNavBarShown = true;
    @Nullable
    private static WeakReference<FeedAutoScrollOverlay> overlayRef;
    @Nullable
    private static WeakReference<ImageView> buttonRef;
    private static final List<WeakReference<View>> postPageViews = new ArrayList<>();

    private static final class FeedAutoScrollOverlay extends FrameLayout {
        private final Rect hitRect = new Rect();
        private boolean dispatchedToButton;

        FeedAutoScrollOverlay(Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            try {
                requestApplyInsets();
                applyInsets(getRootWindowInsets());
            } catch (Exception ex) {
                Logger.printException(() -> "onAttachedToWindow insets failure", ex);
            }
        }

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            try {
                ImageView button = buttonRef != null ? buttonRef.get() : null;
                if (button == null || button.getVisibility() != View.VISIBLE) {
                    if (FeedAutoScrollPatch.isScrolling()) {
                        FeedAutoScrollPatch.stop();
                    }
                    return false;
                }

                final int action = ev.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    button.getHitRect(hitRect);
                    final boolean inside = hitRect.contains((int) ev.getX(), (int) ev.getY());
                    if (inside) {
                        dispatchedToButton = true;
                        return super.dispatchTouchEvent(ev);
                    } else {
                        dispatchedToButton = false;
                        if (FeedAutoScrollPatch.isScrolling()) {
                            FeedAutoScrollPatch.stop();
                        }
                        return false;
                    }
                }

                if (dispatchedToButton) {
                    final boolean handled = super.dispatchTouchEvent(ev);
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        dispatchedToButton = false;
                    }
                    return handled;
                }

                return false;
            } catch (Exception ex) {
                Logger.printException(() -> "dispatchTouchEvent failure", ex);
                return false;
            }
        }
    }

    private FeedAutoScrollButton() {}

    private static void applyStyle(Context context, ImageView button) {
        final int primaryColor = getThemeColor(context, android.R.attr.textColorPrimary, Color.WHITE);
        final int primaryAlpha = Color.alpha(primaryColor);
        final int rippleColor = Color.argb(
                Math.round(primaryAlpha * RIPPLE_ALPHA_RATIO),
                Color.red(primaryColor),
                Color.green(primaryColor),
                Color.blue(primaryColor));

        button.setColorFilter(primaryColor);
        button.setPadding(Dim.dp12, Dim.dp12, Dim.dp12, Dim.dp12);
        button.setScaleType(ImageView.ScaleType.FIT_CENTER);

        final boolean isTablet = Utils.isTablet();
        final int backgroundColor;
        if (isTablet) {
            backgroundColor = Color.argb(
                    Math.round(primaryAlpha * TABLET_BG_ALPHA_RATIO),
                    Color.red(primaryColor),
                    Color.green(primaryColor),
                    Color.blue(primaryColor));
            button.setElevation(0f);
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

        RippleDrawable ripple = new RippleDrawable(
                ColorStateList.valueOf(rippleColor),
                content,
                mask);
        button.setBackground(ripple);
    }

    private static int getThemeColor(Context context, int attribute, int defaultColor) {
        try {
            TypedValue value = new TypedValue();
            if (context.getTheme().resolveAttribute(attribute, value, true)) {
                if (value.type >= TypedValue.TYPE_FIRST_COLOR_INT && value.type <= TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data;
                }
                if (value.resourceId != 0) {
                    return context.getColor(value.resourceId);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getThemeColor failure", ex);
        }
        return defaultColor;
    }

    private static void applyInsets(@Nullable WindowInsets insets) {
        if (insets == null) {
            return;
        }
        final int leftInset;
        final int bottomInset;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Insets systemBars = insets.getInsets(WindowInsets.Type.systemBars());
            leftInset = systemBars.left;
            bottomInset = systemBars.bottom;
        } else {
            leftInset = insets.getSystemWindowInsetLeft();
            bottomInset = insets.getSystemWindowInsetBottom();
        }
        updateButtonPosition(leftInset, bottomInset);
    }

    private static void updateButtonPosition(int leftInset, int bottomInset) {
        ImageView button = buttonRef != null ? buttonRef.get() : null;
        if (button == null) {
            return;
        }
        final boolean isTablet = Utils.isTablet();
        final int leftMargin = leftInset + (isTablet ? Dim.dp24 : Dim.dp16);
        final int bottomMargin = bottomInset + (isTablet ? Dim.dp24 : Dim.dp(PHONE_BOTTOM_OFFSET_DP));

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
        if (params == null) {
            params = new FrameLayout.LayoutParams(Dim.dp48, Dim.dp48, Gravity.BOTTOM | Gravity.LEFT);
        }
        params.width = Dim.dp48;
        params.height = Dim.dp48;
        params.gravity = Gravity.BOTTOM | Gravity.LEFT;
        params.leftMargin = leftMargin;
        params.bottomMargin = bottomMargin;
        button.setLayoutParams(params);
    }

    public static void onMainActivityResumed(@Nullable Activity activity) {
        try {
            if (!FeedAutoScrollPatch.isEnabled() || activity == null) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onMainActivityResumed(activity));
                return;
            }

            isResumed = true;

            final int frameContainerId = ResourceUtils.getIdIdentifier("frame_container");
            View container = frameContainerId != 0 ? activity.findViewById(frameContainerId) : null;
            if (!(container instanceof ViewGroup)) {
                container = activity.findViewById(android.R.id.content);
            }
            if (!(container instanceof ViewGroup)) {
                return;
            }

            ViewGroup parent = (ViewGroup) container;
            FeedAutoScrollOverlay overlay = parent.findViewWithTag(OVERLAY_TAG);
            ImageView button;

            if (overlay != null) {
                overlay.bringToFront();
                overlayRef = new WeakReference<>(overlay);
                button = buttonRef != null ? buttonRef.get() : null;
                if (button == null && overlay.getChildCount() > 0 && overlay.getChildAt(0) instanceof ImageView) {
                    button = (ImageView) overlay.getChildAt(0);
                    buttonRef = new WeakReference<>(button);
                }
                if (button != null) {
                    applyStyle(activity, button);
                }
            } else {
                overlay = new FeedAutoScrollOverlay(activity);
                overlay.setTag(OVERLAY_TAG);
                overlayRef = new WeakReference<>(overlay);

                button = new ImageView(activity);
                buttonRef = new WeakReference<>(button);
                applyStyle(activity, button);

                button.setOnClickListener(v -> {
                    try {
                        if (FeedAutoScrollPatch.isScrolling()) {
                            FeedAutoScrollPatch.stop();
                        } else {
                            FeedAutoScrollPatch.start(v.getContext());
                        }
                    } catch (Exception ex) {
                        Logger.printException(() -> "button click failure", ex);
                    }
                });

                final boolean isTablet = Utils.isTablet();
                final int initialLeft = isTablet ? Dim.dp24 : Dim.dp16;
                final int initialBottom = isTablet ? Dim.dp24 : Dim.dp(PHONE_BOTTOM_OFFSET_DP);
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                        Dim.dp48, Dim.dp48, Gravity.BOTTOM | Gravity.LEFT);
                params.leftMargin = initialLeft;
                params.bottomMargin = initialBottom;
                overlay.addView(button, params);

                overlay.setOnApplyWindowInsetsListener((v, insets) -> {
                    try {
                        applyInsets(insets);
                    } catch (Exception ex) {
                        Logger.printException(() -> "onApplyWindowInsets failure", ex);
                    }
                    return insets;
                });

                parent.addView(overlay, new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            applyInsets(overlay.getRootWindowInsets());

            FeedAutoScrollPatch.setOnScrollStateChangedListener(FeedAutoScrollButton::onScrollStateChanged);
            onScrollStateChanged(FeedAutoScrollPatch.isScrolling());
            updateVisibility();
        } catch (Exception ex) {
            Logger.printException(() -> "onMainActivityResumed failure", ex);
        }
    }

    public static void onMainActivityPaused(@Nullable Activity activity) {
        try {
            if (!FeedAutoScrollPatch.isEnabled()) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onMainActivityPaused(activity));
                return;
            }
            FeedAutoScrollPatch.stop();
            isResumed = false;
            updateVisibility();
        } catch (Exception ex) {
            Logger.printException(() -> "onMainActivityPaused failure", ex);
        }
    }

    public static void onNavBarFraction(float fraction) {
        try {
            if (!FeedAutoScrollPatch.isEnabled() || Utils.isTablet()) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onNavBarFraction(fraction));
                return;
            }
            final boolean shown = fraction > NAVBAR_SHOWN_THRESHOLD;
            if (isNavBarShown != shown) {
                isNavBarShown = shown;
                updateVisibility();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onNavBarFraction failure", ex);
        }
    }

    public static void onPostPageViewCreated(@Nullable View view) {
        try {
            if (!FeedAutoScrollPatch.isEnabled() || view == null) {
                return;
            }
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onPostPageViewCreated(view));
                return;
            }
            postPageViews.add(new WeakReference<>(view));
            view.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View v) {
                    try {
                        FeedAutoScrollPatch.stop();
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
            if (view.isAttachedToWindow()) {
                FeedAutoScrollPatch.stop();
            }
            updateVisibility();
        } catch (Exception ex) {
            Logger.printException(() -> "onPostPageViewCreated failure", ex);
        }
    }

    private static boolean isPostPageShowing() {
        Iterator<WeakReference<View>> iterator = postPageViews.iterator();
        boolean showing = false;
        while (iterator.hasNext()) {
            View view = iterator.next().get();
            if (view == null) {
                iterator.remove();
            } else if (view.isAttachedToWindow()) {
                showing = true;
            }
        }
        return showing;
    }

    public static void updateVisibility() {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(FeedAutoScrollButton::updateVisibility);
                return;
            }
            final boolean shouldShow = FeedAutoScrollPatch.isEnabled()
                    && isResumed
                    && FeedAutoScrollPatch.hasOnScreenFeed()
                    && !isPostPageShowing()
                    && (Utils.isTablet() || isNavBarShown);

            if (!shouldShow && FeedAutoScrollPatch.isScrolling()) {
                FeedAutoScrollPatch.stop();
            }

            ImageView button = buttonRef != null ? buttonRef.get() : null;
            if (button != null) {
                button.setVisibility(shouldShow ? View.VISIBLE : View.GONE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateVisibility failure", ex);
        }
    }

    static void onScrollStateChanged(boolean scrolling) {
        try {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                MAIN_HANDLER.post(() -> onScrollStateChanged(scrolling));
                return;
            }
            ImageView button = buttonRef != null ? buttonRef.get() : null;
            if (button == null) {
                return;
            }
            final int iconResId = ResourceUtils.getDrawableIdentifier(
                    scrolling ? "icon_stop_fill" : "icon_play_fill");
            if (iconResId != 0) {
                button.setImageResource(iconResId);
            }
            String desc = ResourceUtils.getString(
                    scrolling ? "morphe_feed_auto_scroll_stop" : "morphe_feed_auto_scroll_start");
            if (desc != null) {
                button.setContentDescription(desc);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onScrollStateChanged failure", ex);
        }
    }
}
