/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5282
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.reddit.utils.ComposeUtils.getDensity;
import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Logger;

/**
 * Floating round glass search button next to the bottom navigation bar.
 */
final class GlassNavigationBarSearchButton extends View {

    private static final int RIPPLE_COLOR = 0x33888888;
    private static final int ICON_COLOR_LIGHT = 0xFF1C1C1E;
    private static final float ICON_LENS_RADIUS_RATIO = 0.135f;
    private static final float ICON_LENS_OFFSET_RATIO = 0.045f;
    private static final float ICON_HANDLE_END_RATIO = 0.19f;
    private static final float ICON_STROKE_RATIO = 0.036f;
    private static final float MIN_ICON_STROKE_DP = 1.5f;
    private static final float COS_45 = 0.70710678f;

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] windowLocation = new int[2];
    private final int[] parentLocation = new int[2];
    private final OnLayoutChangeListener parentLayoutListener = (v, l, t, r, b, ol, ot, or, ob) -> {
        if (r - l != or - ol || b - t != ob - ot) {
            GlassNavigationBarPatch.onConfigurationChanged();
        }
    };

    private int navBarInsetPx;

    GlassNavigationBarSearchButton(Context context) {
        super(context);
        setContentDescription(str("morphe_glass_navigation_bar_search_button_label"));
        setClickable(true);
        setFocusable(true);

        circlePaint.setStyle(Paint.Style.FILL);
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeCap(Paint.Cap.ROUND);

        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });
        setClipToOutline(true);

        ShapeDrawable mask = new ShapeDrawable(new OvalShape());
        setForeground(new RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), null, mask));

        setOnClickListener(v -> GlassNavigationBarPatch.invokeSearchAction());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        try {
            if (getParent() instanceof View parentView) {
                parentView.addOnLayoutChangeListener(parentLayoutListener);
            }
            WindowInsets rootInsets = getRootWindowInsets();
            if (rootInsets != null) {
                readBottomInset(rootInsets);
                updateLayoutParams();
            }
            requestApplyInsets();
        } catch (Exception ex) {
            Logger.printException(() -> "onAttachedToWindow failure", ex);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (getParent() instanceof View parentView) {
            parentView.removeOnLayoutChangeListener(parentLayoutListener);
        }
        super.onDetachedFromWindow();
    }

    @Override
    public WindowInsets onApplyWindowInsets(WindowInsets insets) {
        try {
            readBottomInset(insets);
            updateLayoutParams();
        } catch (Exception ex) {
            Logger.printException(() -> "onApplyWindowInsets failure", ex);
        }
        return super.onApplyWindowInsets(insets);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        GlassNavigationBarPatch.onConfigurationChanged();
    }

    @SuppressWarnings("deprecation")
    private void readBottomInset(WindowInsets insets) {
        if (insets == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            navBarInsetPx = insets.getInsets(WindowInsets.Type.navigationBars()).bottom;
        } else {
            navBarInsetPx = insets.getSystemWindowInsetBottom();
        }
        GlassNavigationBarPatch.setNavBarInsetPx(navBarInsetPx);
    }

    void updateLayoutParams() {
        ViewGroup.LayoutParams rawLp = getLayoutParams();
        if (!(rawLp instanceof FrameLayout.LayoutParams lp)) {
            return;
        }

        final int diameter = Math.round(GlassNavigationBarPatch.getCapsuleHeightPx(navBarInsetPx));
        ViewParent parent = getParent();
        final float parentWidth = (parent instanceof View parentView && parentView.getWidth() > 0)
                ? parentView.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        final int endMargin = Math.round(GlassNavigationBarPatch.getCapsuleSideGapPx(parentWidth));
        final int capsuleBottomOffset = Math.round(
                GlassNavigationBarPatch.getCapsuleBottomOffsetPx(navBarInsetPx));
        final int bottomMargin = Math.max(
                0,
                navBarInsetPx + capsuleBottomOffset + Math.round(GlassNavigationBarPatch.getLift())
        );

        if (lp.width != diameter
                || lp.height != diameter
                || lp.gravity != (Gravity.BOTTOM | Gravity.END)
                || lp.bottomMargin != bottomMargin
                || lp.getMarginEnd() != endMargin) {
            lp.width = diameter;
            lp.height = diameter;
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.bottomMargin = bottomMargin;
            lp.setMarginEnd(endMargin);
            setLayoutParams(lp);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        final float diameter = Math.min(width, height);
        final float left = (width - diameter) / 2;
        final float top = (height - diameter) / 2;
        final float cx = width / 2f;
        final float cy = height / 2f;
        final float radius = diameter / 2;
        final boolean dark = GlassNavigationBarPatch.isGlassDark();

        if (GlassNavigationBarPatch.FROSTED_ENABLED && canvas.isHardwareAccelerated()) {
            ViewParent parent = getParent();
            if (parent instanceof View parentView) {
                getLocationInWindow(windowLocation);
                parentView.getLocationInWindow(parentLocation);
                final int leftInPage = windowLocation[0] - parentLocation[0] + Math.round(left);
                final int topFromBottom = parentView.getHeight()
                        - (windowLocation[1] - parentLocation[1] + Math.round(top));
                GlassNavigationBarBlur.drawButtonBackdrop(
                        canvas,
                        leftInPage,
                        topFromBottom,
                        Math.round(diameter),
                        cx,
                        cy,
                        radius,
                        GlassNavigationBarPatch.getPageColor()
                );
            }
        }

        circlePaint.setColor((int) (GlassNavigationBarPatch.getGlassColor(dark) >>> 32));
        canvas.drawCircle(cx, cy, radius, circlePaint);

        if (GlassNavigationBarPatch.FROSTED_ENABLED) {
            GlassNavigationBarBlur.drawEdge(canvas, left, top, diameter, diameter, radius, dark);
        }

        iconPaint.setColor(dark ? Color.WHITE : ICON_COLOR_LIGHT);
        iconPaint.setStrokeWidth(Math.max(MIN_ICON_STROKE_DP * getDensity(), diameter * ICON_STROKE_RATIO));
        final float lensRadius = diameter * ICON_LENS_RADIUS_RATIO;
        final float lensCx = cx - diameter * ICON_LENS_OFFSET_RATIO;
        final float lensCy = cy - diameter * ICON_LENS_OFFSET_RATIO;
        canvas.drawCircle(lensCx, lensCy, lensRadius, iconPaint);
        final float handleOffset = lensRadius * COS_45;
        final float handleEnd = diameter * ICON_HANDLE_END_RATIO;
        canvas.drawLine(
                lensCx + handleOffset,
                lensCy + handleOffset,
                cx + handleEnd,
                cy + handleEnd,
                iconPaint
        );
    }
}
