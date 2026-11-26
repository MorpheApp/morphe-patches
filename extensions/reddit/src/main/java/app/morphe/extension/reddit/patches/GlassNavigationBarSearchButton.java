/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5363
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
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.ViewParent;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.GlassStrength;
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
    private int shadowMarginPx;
    private int cachedRippleMargin = -1;

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
                final int margin = shadowMarginPx;
                outline.setOval(
                        margin,
                        margin,
                        Math.max(margin, view.getWidth() - margin),
                        Math.max(margin, view.getHeight() - margin)
                );
            }
        });

        shadowMarginPx = GlassNavigationBarStrength.getSearchButtonMarginPx();
        updateClipAndRipple(shadowMarginPx);

        setOnClickListener(v -> GlassNavigationBarPatch.invokeSearchAction());
    }

    private void updateClipAndRipple(int margin) {
        if (margin == cachedRippleMargin) {
            return;
        }
        cachedRippleMargin = margin;
        setClipToOutline(margin == 0);
        invalidateOutline();

        ShapeDrawable mask = new ShapeDrawable(new OvalShape());
        Drawable maskLayer = margin > 0 ? new InsetDrawable(mask, margin) : mask;
        setForeground(new RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), null, maskLayer));
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

        final int margin = GlassNavigationBarStrength.getSearchButtonMarginPx();
        shadowMarginPx = margin;
        updateClipAndRipple(margin);

        final int diameter = Math.round(GlassNavigationBarPatch.getCapsuleHeightPx(navBarInsetPx));
        final int viewSize = diameter + 2 * margin;
        ViewParent parent = getParent();
        final float parentWidth = (parent instanceof View parentView && parentView.getWidth() > 0)
                ? parentView.getWidth()
                : getResources().getDisplayMetrics().widthPixels;
        final int endMargin = Math.round(GlassNavigationBarPatch.getCapsuleSideGapPx(parentWidth)) - margin;
        final int capsuleBottomOffset = Math.round(
                GlassNavigationBarPatch.getCapsuleBottomOffsetPx(navBarInsetPx));
        final int bottomMargin = Math.max(
                0,
                navBarInsetPx + capsuleBottomOffset + Math.round(GlassNavigationBarPatch.getLift())
        ) - margin;

        if (lp.width != viewSize
                || lp.height != viewSize
                || lp.gravity != (Gravity.BOTTOM | Gravity.END)
                || lp.bottomMargin != bottomMargin
                || lp.getMarginEnd() != endMargin) {
            lp.width = viewSize;
            lp.height = viewSize;
            lp.gravity = Gravity.BOTTOM | Gravity.END;
            lp.bottomMargin = bottomMargin;
            lp.setMarginEnd(endMargin);
            setLayoutParams(lp);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (shadowMarginPx > 0 && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            final float cx = getWidth() / 2f;
            final float cy = getHeight() / 2f;
            final float radius = Math.min(getWidth(), getHeight()) / 2f - shadowMarginPx;
            final float dx = event.getX() - cx;
            final float dy = event.getY() - cy;
            if (radius <= 0f || dx * dx + dy * dy > radius * radius) {
                return false;
            }
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth();
        final int height = getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }

        final int margin = shadowMarginPx;
        final float diameter = Math.min(width, height) - 2f * margin;
        if (diameter <= 0f) {
            return;
        }
        final float left = (width - diameter) / 2f;
        final float top = (height - diameter) / 2f;
        final float cx = width / 2f;
        final float cy = height / 2f;
        final float radius = diameter / 2f;
        final boolean dark = GlassNavigationBarPatch.isGlassDark();

        if (GlassNavigationBarPatch.STRENGTH != GlassStrength.CLEAR) {
            GlassNavigationBarStrength.drawShadow(canvas, left, top, diameter, diameter, radius);
        }

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

        if (GlassNavigationBarPatch.STRENGTH != GlassStrength.CLEAR) {
            GlassNavigationBarStrength.drawOutline(canvas, left, top, diameter, diameter, radius);
        } else if (GlassNavigationBarPatch.FROSTED_ENABLED) {
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
