/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5360
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.Dim;
import kotlin.jvm.functions.Function0;

/**
 * Round search button next to the floating navigation bar of {@link GlassNavigationBarPatch}.
 * It is a view above all screens, so it is hidden on screens without the navigation bar.
 */
final class GlassNavigationBarSearchButton extends View {

    private static final int SEARCH_ICON = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "icon_search");
    private static final int ICON_SIZE = Dim.dp24;

    private static final String BUTTON_TAG = "morphe_glass_navigation_bar_search_button";

    private static final int RIPPLE_COLOR = 0x33888888;
    private static final int ICON_COLOR_LIGHT = 0xFF1C1C1E;

    /**
     * Space around the circle for its shadow, in pixels.
     */
    private static final int SHADOW_MARGIN = GlassNavigationBarEdge.getShadowMargin();

    private static WeakReference<GlassNavigationBarSearchButton> buttonRef = new WeakReference<>(null);
    private static boolean isBottomNavAttached;
    // Weak keys ensure a missed detach cannot keep the search button hidden forever.
    private static final Set<Object> openSheets = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * If the current screen shows the navigation bar.
     * Screens opened from Home are not detected otherwise, because all screens are in one Compose view.
     */
    private static boolean showBottomNav = true;

    @Nullable
    private static Function0<?> searchAction;

    /**
     * Position of the button, in pixels. Set when the navigation bar is drawn.
     */
    private static int buttonSize, endMargin, bottomMargin;

    private static int barOffset;
    private static boolean isBarHidden;

    private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] location = new int[2];
    private final int[] parentLocation = new int[2];

    private final Drawable icon;

    private GlassNavigationBarSearchButton(Context context) {
        super(context);
        setTag(BUTTON_TAG);
        setContentDescription(str("morphe_glass_navigation_bar_search_button_label"));
        setForeground(new RippleDrawable(ColorStateList.valueOf(RIPPLE_COLOR), null,
                new InsetDrawable(new ShapeDrawable(new OvalShape()), SHADOW_MARGIN)));
        setOnClickListener(v -> {
            try {
                if (searchAction != null) {
                    searchAction.invoke();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onClick failure", ex);
            }
        });

        icon = Objects.requireNonNull(context.getDrawable(SEARCH_ICON)).mutate();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (SHADOW_MARGIN > 0 && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            // Ignore touches on the shadow.
            final float radius = getWidth() / 2f;
            final float x = event.getX() - radius;
            final float y = event.getY() - radius;
            final float circleRadius = radius - SHADOW_MARGIN;
            if (x * x + y * y > circleRadius * circleRadius) {
                return false;
            }
        }

        return super.dispatchTouchEvent(event);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        try {
            final int size = getWidth() - 2 * SHADOW_MARGIN;
            final float radius = size / 2f;
            final boolean dark = GlassNavigationBarPatch.isGlassDark();

            canvas.translate(SHADOW_MARGIN, SHADOW_MARGIN);
            GlassNavigationBarEdge.drawShadow(canvas, 0, 0, size, size, radius);

            if (GlassNavigationBarPatch.FROSTED_ENABLED && getParent() instanceof View parent) {
                // The page behind the navigation bar fills the parent.
                getLocationInWindow(location);
                parent.getLocationInWindow(parentLocation);
                GlassNavigationBarBlur.drawButtonBackdrop(
                        canvas,
                        location[0] - parentLocation[0] + SHADOW_MARGIN,
                        parent.getHeight() - (location[1] - parentLocation[1] + SHADOW_MARGIN),
                        size,
                        GlassNavigationBarPatch.getPageColor()
                );
            }

            circlePaint.setColor((int) (GlassNavigationBarPatch.getGlassColor(dark) >>> 32));
            canvas.drawCircle(radius, radius, radius, circlePaint);

            GlassNavigationBarEdge.drawEdges(canvas, 0, 0, size, size, radius, dark);

            final int iconStart = (size - ICON_SIZE) / 2;
            icon.setBounds(iconStart, iconStart, iconStart + ICON_SIZE, iconStart + ICON_SIZE);
            icon.setTint(dark ? Color.WHITE : ICON_COLOR_LIGHT);
            icon.draw(canvas);
        } catch (Exception ex) {
            Logger.printException(() -> "onDraw failure", ex);
        }
    }

    /**
     * Adds the button to the window of the screen with the navigation bar.
     */
    static void onBottomNavAttached(@Nullable View bottomNavView) {
        isBottomNavAttached = true;

        // The activity can be new.
        ViewGroup content = bottomNavView == null
                ? null
                : bottomNavView.getRootView().findViewById(android.R.id.content);
        if (content == null) {
            Logger.printDebug(() -> "No content view");
        } else {
            GlassNavigationBarSearchButton button = content.findViewWithTag(BUTTON_TAG);
            if (button == null) {
                button = new GlassNavigationBarSearchButton(content.getContext());
                content.addView(button, new FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM | Gravity.END));
            }
            buttonRef = new WeakReference<>(button);
        }

        update();
    }

    static void onBottomNavDetached() {
        isBottomNavAttached = false;
        update();
    }

    static void onBottomSheetAttached(Object sheet) {
        openSheets.add(sheet);
        update();
    }

    static void onBottomSheetDetached(Object sheet) {
        openSheets.remove(sheet);
        update();
    }

    static void setShowBottomNav(boolean show) {
        if (showBottomNav != show) {
            showBottomNav = show;
            update();
        }
    }

    static void setSearchAction(Function0<?> action) {
        if (searchAction != action) {
            searchAction = action;
            update();
        }
    }

    /**
     * @param size         Diameter of the button.
     * @param endMargin    Space between the button and the end of the screen.
     * @param bottomMargin Space between the button and the bottom of the navigation bar.
     */
    static void setBounds(int size, int endMargin, int bottomMargin) {
        if (buttonSize != size
                || GlassNavigationBarSearchButton.endMargin != endMargin
                || GlassNavigationBarSearchButton.bottomMargin != bottomMargin) {
            buttonSize = size;
            GlassNavigationBarSearchButton.endMargin = endMargin;
            GlassNavigationBarSearchButton.bottomMargin = bottomMargin;
            // Called while the navigation bar draws.
            Utils.runOnMainThread(GlassNavigationBarSearchButton::update);
        }
    }

    /**
     * @param offset How far down the navigation bar is moved, in pixels.
     * @param hidden If the navigation bar is moved off the screen.
     */
    static void setBarOffset(int offset, boolean hidden) {
        if (barOffset == offset && isBarHidden == hidden) {
            return;
        }
        barOffset = offset;

        if (isBarHidden != hidden) {
            // The glass can be taller than the navigation bar.
            isBarHidden = hidden;
            update();
            return;
        }

        GlassNavigationBarSearchButton button = buttonRef.get();
        if (button != null) {
            button.setTranslationY(offset);
            if (GlassNavigationBarPatch.FROSTED_ENABLED) {
                // The blurred area moves with the button.
                button.invalidate();
            }
        }
    }

    /**
     * Draws the button again, such as after the theme changes.
     */
    static void redraw() {
        GlassNavigationBarSearchButton button = buttonRef.get();
        if (button != null) {
            button.invalidate();
        }
    }

    static void update() {
        GlassNavigationBarSearchButton button = buttonRef.get();
        if (button == null) {
            return;
        }

        final boolean show = GlassNavigationBarPatch.isSearchButtonActive()
                && searchAction != null
                && buttonSize > 0
                && isBottomNavAttached
                && showBottomNav
                && !isBarHidden
                && openSheets.isEmpty();
        button.setVisibility(show ? VISIBLE : GONE);
        if (!show) {
            return;
        }

        // The view is larger than the circle by the shadow margin.
        final int viewSize = buttonSize + 2 * SHADOW_MARGIN;
        final int viewEndMargin = endMargin - SHADOW_MARGIN;
        final int viewBottomMargin = bottomMargin - SHADOW_MARGIN;
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) button.getLayoutParams();
        if (params.width != viewSize || params.getMarginEnd() != viewEndMargin
                || params.bottomMargin != viewBottomMargin) {
            params.width = viewSize;
            params.height = viewSize;
            params.setMarginEnd(viewEndMargin);
            params.bottomMargin = viewBottomMargin;
            button.setLayoutParams(params);
        }
        button.setTranslationY(barOffset);
    }
}
