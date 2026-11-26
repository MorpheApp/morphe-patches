/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.reddit.utils.ComposeUtils.getDensity;
import static app.morphe.extension.reddit.utils.ComposeUtils.pack;
import static app.morphe.extension.reddit.utils.ComposeUtils.unpackFirst;
import static app.morphe.extension.reddit.utils.ComposeUtils.unpackSecond;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.utils.ComposeUtils.ContentDrawScope;
import app.morphe.extension.reddit.utils.ComposeUtils.Modifier;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import kotlin.jvm.functions.Function0;
import kotlin.jvm.functions.Function1;

@SuppressWarnings("unused")
public final class GlassNavigationBarPatch {

    public enum GlassColor {
        AUTO,
        LIGHT,
        DARK
    }

    public enum GlassStyle {
        TINTED,
        FROSTED
    }

    public static final class FrostedGlassAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return Settings.GLASS_NAVIGATION_BAR.get()
                    && Settings.GLASS_NAVIGATION_BAR_STYLE.get() == GlassStyle.FROSTED;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(Settings.GLASS_NAVIGATION_BAR, Settings.GLASS_NAVIGATION_BAR_STYLE);
        }
    }

    /**
     * Reddit theme colors.
     */
    public interface NeutralColors {
        // Methods are added during patching.

        /**
         * @return Background color of the feed and of the navigation bar.
         */
        long patch_getBackground();

        /**
         * @return Background color behind the side rail.
         */
        long patch_getRailBackground();
    }

    /**
     * Draws a capsule behind the content.
     */
    private static final class CapsuleDraw implements Function1<ContentDrawScope, Object> {
        private static final CapsuleDraw BAR = new CapsuleDraw(false);
        private static final CapsuleDraw RAIL = new CapsuleDraw(true);

        private final boolean isRail;

        private CapsuleDraw(boolean isRail) {
            this.isRail = isRail;
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                if (isRail) {
                    drawRailCapsule(scope);
                } else {
                    drawBarCapsule(scope);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "CapsuleDraw failure", ex);
            }

            scope.patch_drawContent();
            return null;
        }
    }

    /**
     * Draws nothing, hiding the search field while keeping its layout space.
     */
    private static final class EmptyDraw implements Function1<ContentDrawScope, Object> {
        private static final EmptyDraw INSTANCE = new EmptyDraw();

        private EmptyDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            return null;
        }
    }

    /**
     * Draws a glass circle or pill behind the menu button or avatar.
     */
    private static final class CircleDraw implements Function1<ContentDrawScope, Object> {
        private static final CircleDraw INSTANCE = new CircleDraw();

        private CircleDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                drawSearchCircle(scope);
            } catch (Exception ex) {
                Logger.printException(() -> "CircleDraw failure", ex);
            }

            scope.patch_drawContent();
            return null;
        }
    }

    /**
     * Mask of everything except the alpha of a packed Compose sRGB color.
     */
    private static final long COLOR_WITHOUT_ALPHA_MASK = 0x00FFFFFFFFFFFFFFL;

    private static final int GLASS_RGB_DARK = 0x1C1C1E;
    private static final int GLASS_RGB_LIGHT = 0xF2F2F7;

    /**
     * The tint of frosted glass is lighter, so more of the blurred page shows.
     */
    private static final float FROSTED_OPACITY = 0.6f;

    /**
     * Corner radius if the ends are not round.
     */
    private static final float CORNER_RADIUS_DP = 18;

    /**
     * Height of the row of buttons. The bar also covers the system navigation bar below it.
     */
    private static final float BAR_ROW_HEIGHT_DP = 50;
    private static final float BAR_CAPSULE_HEIGHT_DP = 60;
    private static final float BAR_CAPSULE_INSET_DP = 20;

    /**
     * A taller system navigation bar has buttons and can be almost opaque.
     */
    private static final float GESTURE_NAVIGATION_MAX_HEIGHT_DP = 32;

    /**
     * Rail height needed for Reddit to show the buttons in the middle of the rail.
     */
    private static final float RAIL_CENTERED_MIN_HEIGHT_DP = 500;
    private static final float RAIL_GAP_DP = 12;

    private static final int TABLET_MIN_WIDTH_DP = 600;
    private static final float SEARCH_BUTTON_GAP_DP = 10;
    private static final float SEARCH_CIRCLE_EXTRA_DP = 8;
    private static final String BOTTOM_NAV_SCREEN_CLASS = "com.reddit.launch.bottomnav.BottomNavScreen";
    private static final String SEARCH_BUTTON_TAG = "morphe_glass_search_button";

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final AtomicBoolean UPDATE_PENDING = new AtomicBoolean(false);
    private static final Runnable UPDATE_RUNNABLE = () -> {
        UPDATE_PENDING.set(false);
        updateSearchButtonState();
    };
    private static final Set<Object> OVERLAY_SCREENS =
            Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    private static final Function0<Object> NO_OP_CLICK = new Function0<>() {
        @Override
        public Object invoke() {
            return null;
        }
    };

    private static final boolean GLASS_ENABLED = Settings.GLASS_NAVIGATION_BAR.get();
    private static final int OPACITY_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_OPACITY);
    static final int BLUR_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_BLUR);
    private static final int HEIGHT_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_HEIGHT);
    private static final int WIDTH_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_WIDTH);
    /**
     * How far the bottom bar is lifted.
     */
    private static final int POSITION_DP = GLASS_ENABLED
            ? SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_POSITION)
            : 0;
    private static final boolean HIDE_LABELS = GLASS_ENABLED && !Settings.GLASS_NAVIGATION_BAR_SHOW_LABELS.get();
    static final boolean SEARCH_ENABLED = GLASS_ENABLED && Settings.GLASS_NAVIGATION_BAR_SEARCH_BUTTON.get();

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    static final boolean FROSTED_ENABLED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            && GLASS_ENABLED
            && Settings.GLASS_NAVIGATION_BAR_STYLE.get() == GlassStyle.FROSTED
            && BLUR_PERCENT > 0;

    /**
     * If the Reddit theme is dark, which can be different from the system theme.
     */
    private static boolean isDarkTheme;

    /**
     * Opaque background color of the page.
     */
    @ColorInt
    private static int pageColor = Color.BLACK;

    private static boolean isRailCentered;
    private static boolean railInUse;

    private static int lastNavBarInsetPx = -1;
    private static int screenWidthDp = -1;

    @Nullable
    private static Function0<?> searchAction;
    private static WeakReference<GlassNavigationBarSearchButton> buttonRef = new WeakReference<>(null);
    private static WeakReference<View> bottomNavViewRef = new WeakReference<>(null);
    private static boolean bottomNavAttached;
    private static boolean showBottomNav = true;
    private static int lastSlideDownPx;
    private static boolean barAutoHidden;

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
     * @return Background color of the navigation bar, and of the area it slides over.
     */
    public static long getBarColor(long color) {
        if (!GLASS_ENABLED) {
            return color;
        }

        updateTheme(color);
        return color & COLOR_WITHOUT_ALPHA_MASK;
    }

    /**
     * Injection point.
     */
    public static float getBarElevation(float elevation) {
        return GLASS_ENABLED ? 0 : elevation;
    }

    /**
     * Injection point.
     *
     * @param draw Draws the line on top of the navigation bar.
     */
    public static Function1<?, ?> getBarDraw(Function1<?, ?> draw) {
        return GLASS_ENABLED ? CapsuleDraw.BAR : draw;
    }

    /**
     * Injection point.
     */
    public static Object getBarModifier(Modifier modifier) {
        try {
            screenWidthDp = -1;
            if (SEARCH_ENABLED && railInUse) {
                railInUse = false;
                postUpdateSearchButtonState();
            }

            final boolean searchActive = isSearchButtonActive();
            if (GLASS_ENABLED && (WIDTH_PERCENT < 100 || searchActive)) {
                final float sidePadding = WIDTH_PERCENT < 100
                        ? getSidePadding(getScreenWidthDp())
                        : 0;
                final float inset = searchActive
                        ? BAR_CAPSULE_INSET_DP * WIDTH_PERCENT / 100
                        : 0;
                final float endExtra = searchActive
                        ? getCapsuleHeightDp() + SEARCH_BUTTON_GAP_DP
                        : 0;
                return modifier.patch_padding(
                        sidePadding + inset, 0, sidePadding + inset + endExtra, 0);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getBarModifier failure", ex);
        }

        return modifier;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of everything that is behind the navigation bar.
     */
    public static Object getContentModifier(Modifier modifier) {
        return FROSTED_ENABLED
                ? modifier.patch_drawWithContent(GlassNavigationBarBlur.ContentDraw.INSTANCE)
                : modifier;
    }

    /**
     * Injection point.
     *
     * @return If the screen content ends above the navigation bar.
     */
    public static boolean padContentForBottomNav(boolean original) {
        return !GLASS_ENABLED && original;
    }

    /**
     * Injection point.
     */
    public static void setRailHeight(float heightDp) {
        isRailCentered = heightDp > RAIL_CENTERED_MIN_HEIGHT_DP;
    }

    /**
     * Injection point.
     *
     * @param slide     How far the bar is slid down while it hides, in pixels.
     * @param barHeight Height of the bar, in pixels.
     */
    public static float getBarSlide(float slide, float barHeight) {
        if (POSITION_DP == 0 && !FROSTED_ENABLED && !SEARCH_ENABLED) {
            return slide;
        }

        try {
            if (barHeight > 0) {
                // The lift decreases while the bar hides, so it still slides fully away.
                slide -= getLift() * (1 - slide / barHeight);
            }
            updateBarOffset(slide, barHeight > 0 && slide >= barHeight);
        } catch (Exception ex) {
            Logger.printException(() -> "getBarSlide failure", ex);
        }

        return slide;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the bar, if it does not hide on the current screen.
     */
    public static Object getStaticBarModifier(Modifier modifier) {
        if (POSITION_DP == 0 && !FROSTED_ENABLED && !SEARCH_ENABLED) {
            return modifier;
        }

        try {
            updateBarOffset(-getLift(), false);

            if (POSITION_DP > 0) {
                return modifier.patch_offset(0, -POSITION_DP);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getStaticBarModifier failure", ex);
        }

        return modifier;
    }

    /**
     * Injection point.
     *
     * @param barHeight Height of the bar, in pixels.
     * @return How far the create button is above the bottom of the screen.
     */
    public static float getCreateButtonOffset(float barHeight) {
        return POSITION_DP == 0 ? barHeight : barHeight + getLift();
    }

    /**
     * Injection point.
     *
     * @return If the buttons show their labels.
     */
    public static boolean showLabel(boolean original) {
        return original && !HIDE_LABELS;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the search field in the Home top bar.
     */
    public static Object getSearchFieldModifier(Modifier modifier) {
        try {
            if (isSearchButtonActive()) {
                return modifier.patch_drawWithContent(EmptyDraw.INSTANCE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getSearchFieldModifier failure", ex);
        }

        return modifier;
    }

    /**
     * Injection point.
     *
     * @param action Search click action of the Home top bar.
     */
    public static Function0<?> getSearchFieldClick(Function0<?> action) {
        try {
            if (!SEARCH_ENABLED) {
                return action;
            }

            if (searchAction != action) {
                searchAction = action;
                postUpdateSearchButtonState();
            }

            if (isSearchButtonActive()) {
                return NO_OP_CLICK;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getSearchFieldClick failure", ex);
        }

        return action;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the menu button or avatar in the Home top bar.
     */
    public static Object getSearchCircleModifier(Modifier modifier) {
        try {
            if (isSearchButtonActive()) {
                return modifier.patch_drawWithContent(CircleDraw.INSTANCE);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getSearchCircleModifier failure", ex);
        }

        return modifier;
    }

    /**
     * Injection point.
     */
    public static void onScreenAttached(Object screen, @Nullable View view) {
        if (!SEARCH_ENABLED || screen == null) {
            return;
        }

        try {
            if (BOTTOM_NAV_SCREEN_CLASS.equals(screen.getClass().getName())) {
                bottomNavAttached = true;
                bottomNavViewRef = new WeakReference<>(view);
                OVERLAY_SCREENS.clear();
                ensureSearchButton(view);
            } else if (view != null) {
                View bottomView = bottomNavViewRef.get();
                if (bottomView != null
                        && bottomView != view
                        && bottomView.getParent() != null
                        && view.getParent() == bottomView.getParent()) {
                    OVERLAY_SCREENS.add(screen);
                }
            }

            updateSearchButtonState();
        } catch (Exception ex) {
            Logger.printException(() -> "onScreenAttached failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onScreenDetached(Object screen, @Nullable View view) {
        if (!SEARCH_ENABLED || screen == null) {
            return;
        }

        try {
            if (BOTTOM_NAV_SCREEN_CLASS.equals(screen.getClass().getName())) {
                bottomNavAttached = false;
            } else {
                OVERLAY_SCREENS.remove(screen);
            }

            updateSearchButtonState();
        } catch (Exception ex) {
            Logger.printException(() -> "onScreenDetached failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setShowBottomNav(boolean show) {
        if (!SEARCH_ENABLED) {
            return;
        }

        try {
            if (showBottomNav != show) {
                showBottomNav = show;
                updateSearchButtonState();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setShowBottomNav failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the column with the buttons of the side rail.
     */
    public static Object getRailModifier(Modifier modifier) {
        screenWidthDp = -1;
        if (SEARCH_ENABLED && !railInUse) {
            railInUse = true;
            postUpdateSearchButtonState();
        }

        return GLASS_ENABLED
                ? modifier.patch_drawWithContent(CapsuleDraw.RAIL)
                : modifier;
    }

    /**
     * Injection point.
     *
     * @return Color behind the side rail.
     */
    public static long getRailBackground(NeutralColors colors) {
        if (!GLASS_ENABLED) {
            return colors.patch_getRailBackground();
        }

        final long color = colors.patch_getBackground();
        updateTheme(color);
        return color;
    }

    static boolean isSearchButtonActive() {
        return SEARCH_ENABLED && !railInUse && getScreenWidthDp() < TABLET_MIN_WIDTH_DP;
    }

    private static int getScreenWidthDp() {
        if (screenWidthDp < 0) {
            // Split screen windows are narrower than the display.
            Activity activity = Utils.getActivity();
            Context context = activity == null ? Utils.getContext() : activity;
            screenWidthDp = context.getResources().getConfiguration().screenWidthDp;
        }
        return screenWidthDp;
    }

    /**
     * @param color Packed Compose sRGB color, with the ARGB color in the upper 32 bits.
     */
    private static void updateTheme(long color) {
        final int newPageColor = (int) (color >>> 32) | 0xFF000000;
        final boolean newIsDarkTheme = Color.luminance(newPageColor) < 0.5f;
        final boolean changed = pageColor != newPageColor || isDarkTheme != newIsDarkTheme;
        pageColor = newPageColor;
        isDarkTheme = newIsDarkTheme;
        if (changed && SEARCH_ENABLED) {
            invalidateSearchButton();
        }
    }

    /**
     * @return Space on each side of the navigation bar, in the unit of the width.
     */
    private static float getSidePadding(float width) {
        return width * (100 - WIDTH_PERCENT) / 200;
    }

    static float getCapsuleSideGapPx(float width) {
        return getSidePadding(width) + BAR_CAPSULE_INSET_DP * getDensity() * WIDTH_PERCENT / 100;
    }

    private static float getCapsuleHeightDp() {
        if (lastNavBarInsetPx >= 0) {
            return getCapsuleHeightPx(lastNavBarInsetPx) / getDensity();
        }
        return BAR_CAPSULE_HEIGHT_DP * HEIGHT_PERCENT / 100;
    }

    static float getCapsuleHeightPx(float navBarInsetPx) {
        final float density = getDensity();
        final float rowHeight = BAR_ROW_HEIGHT_DP * density;
        float height = BAR_CAPSULE_HEIGHT_DP * density * HEIGHT_PERCENT / 100;
        if (navBarInsetPx > GESTURE_NAVIGATION_MAX_HEIGHT_DP * density) {
            // Do not draw the capsule behind the system navigation bar.
            final float top = (rowHeight - height) / 2;
            height = Math.min(height, rowHeight - top + getLift());
        }
        return height;
    }

    static float getCapsuleBottomOffsetPx(float navBarInsetPx) {
        final float density = getDensity();
        final float rowHeight = BAR_ROW_HEIGHT_DP * density;
        final float rawHeight = BAR_CAPSULE_HEIGHT_DP * density * HEIGHT_PERCENT / 100;
        final float top = (rowHeight - rawHeight) / 2;
        final float height = getCapsuleHeightPx(navBarInsetPx);
        return rowHeight - (top + height);
    }

    static void setNavBarInsetPx(int insetPx) {
        lastNavBarInsetPx = insetPx;
    }

    /**
     * @return How far the bottom bar is lifted up, in pixels.
     */
    static float getLift() {
        return POSITION_DP * getDensity();
    }

    private static void updateBarOffset(float barOffset, boolean hidden) {
        updateBlurOffset(barOffset);
        if (isSearchButtonActive()) {
            onBarSlide(Math.round(barOffset + getLift()), hidden);
        }
    }

    private static void updateBlurOffset(float barOffset) {
        if (FROSTED_ENABLED) {
            GlassNavigationBarBlur.setBarOffset(Math.round(barOffset));
        }
    }

    private static void drawBarCapsule(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float width = unpackFirst(size);
        final float density = getDensity();
        final float rowHeight = BAR_ROW_HEIGHT_DP * density;

        final float left = getCapsuleSideGapPx(width);
        final float navBarInsetPx = unpackSecond(size) - rowHeight;
        lastNavBarInsetPx = Math.round(navBarInsetPx);
        final float height = getCapsuleHeightPx(navBarInsetPx);
        final float rawHeight = BAR_CAPSULE_HEIGHT_DP * density * HEIGHT_PERCENT / 100;
        final float top = (rowHeight - rawHeight) / 2;

        float capsuleLeft = left;
        float capsuleWidth = width - 2 * left;
        if (isSearchButtonActive()) {
            final float reserved = height + SEARCH_BUTTON_GAP_DP * density;
            capsuleWidth -= reserved;
            if (Utils.isRightToLeftLocale()) {
                capsuleLeft += reserved;
            }
        }

        drawCapsule(scope, capsuleLeft, top, capsuleWidth, height, density, true);
    }

    private static void drawRailCapsule(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float density = getDensity();
        final float gap = RAIL_GAP_DP * density;
        // If not centered then the buttons use all space below the header.
        final float verticalGap = isRailCentered ? gap : 0;

        drawCapsule(
                scope,
                gap,
                -verticalGap,
                unpackFirst(size) - 2 * gap,
                unpackSecond(size) + 2 * verticalGap,
                density,
                false
        );
    }

    /**
     * @param hasPageBehind If the page is behind the capsule, and not only a plain color.
     */
    private static void drawCapsule(ContentDrawScope scope, float left, float top, float width,
                                    float height, float density, boolean hasPageBehind) {
        if (width <= 0 || height <= 0) {
            return;
        }

        final float radius = Settings.GLASS_NAVIGATION_BAR_ROUND_ENDS.get()
                ? Math.min(width, height) / 2
                : CORNER_RADIUS_DP * density;
        final boolean dark = isGlassDark();

        if (FROSTED_ENABLED && hasPageBehind) {
            GlassNavigationBarBlur.drawBackdrop(scope, left, top, width, height, radius, pageColor);
        }

        scope.patch_drawRoundRect(
                getGlassColor(dark),
                pack(left, top),
                pack(width, height),
                pack(radius, radius)
        );

        if (FROSTED_ENABLED) {
            GlassNavigationBarBlur.drawEdge(scope, left, top, width, height, radius, dark);
        }
    }

    private static void drawSearchCircle(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float width = unpackFirst(size);
        final float height = unpackSecond(size);
        if (width <= 0 || height <= 0) {
            return;
        }

        final float extra = SEARCH_CIRCLE_EXTRA_DP * getDensity();
        final float rectWidth;
        final float rectHeight;
        if (width > height) {
            rectWidth = width + extra;
            rectHeight = height + extra;
        } else {
            final float diameter = Math.max(width, height) + extra;
            rectWidth = diameter;
            rectHeight = diameter;
        }
        final float left = (width - rectWidth) / 2;
        final float top = (height - rectHeight) / 2;
        final float radius = rectHeight / 2;

        scope.patch_drawRoundRect(
                getGlassColor(isGlassDark()),
                pack(left, top),
                pack(rectWidth, rectHeight),
                pack(radius, radius)
        );
    }

    private static void ensureSearchButton(@Nullable View view) {
        View root = view != null ? view.getRootView() : null;
        View contentView = root != null ? root.findViewById(android.R.id.content) : null;
        if (contentView == null) {
            Activity activity = Utils.getActivity();
            if (activity != null) {
                contentView = activity.findViewById(android.R.id.content);
            }
        }
        if (!(contentView instanceof FrameLayout content)) {
            return;
        }

        View existing = content.findViewWithTag(SEARCH_BUTTON_TAG);
        if (existing instanceof GlassNavigationBarSearchButton existingButton) {
            buttonRef = new WeakReference<>(existingButton);
            existingButton.updateLayoutParams();
            return;
        }

        GlassNavigationBarSearchButton button = new GlassNavigationBarSearchButton(content.getContext());
        button.setTag(SEARCH_BUTTON_TAG);
        button.setVisibility(View.GONE);
        final int diameter = Math.round(getCapsuleHeightPx(Math.max(0, lastNavBarInsetPx)));
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                diameter, diameter, Gravity.BOTTOM | Gravity.END);
        content.addView(button, lp);
        buttonRef = new WeakReference<>(button);
        button.updateLayoutParams();
    }

    private static void onBarSlide(int slideDownPx, boolean hidden) {
        if (lastSlideDownPx == slideDownPx && barAutoHidden == hidden) {
            return;
        }
        lastSlideDownPx = slideDownPx;
        barAutoHidden = hidden;

        if (Looper.myLooper() != Looper.getMainLooper()) {
            postUpdateSearchButtonState();
            return;
        }

        GlassNavigationBarSearchButton button = buttonRef.get();
        if (button == null) {
            return;
        }

        if (button.getTranslationY() != slideDownPx) {
            button.setTranslationY(slideDownPx);
            if (FROSTED_ENABLED && !hidden && button.getVisibility() == View.VISIBLE) {
                button.invalidate();
            }
        }

        updateSearchButtonVisibility(button);
    }

    private static void postUpdateSearchButtonState() {
        if (UPDATE_PENDING.compareAndSet(false, true)) {
            MAIN_HANDLER.post(UPDATE_RUNNABLE);
        }
    }

    private static void updateSearchButtonState() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            postUpdateSearchButtonState();
            return;
        }

        GlassNavigationBarSearchButton button = buttonRef.get();
        if (button == null) {
            View bottomView = bottomNavViewRef.get();
            if (bottomView != null && isSearchButtonActive()) {
                ensureSearchButton(bottomView);
                button = buttonRef.get();
            }
            if (button == null) {
                return;
            }
        }

        updateSearchButtonVisibility(button);
        if (button.getVisibility() == View.VISIBLE) {
            if (button.getTranslationY() != lastSlideDownPx) {
                button.setTranslationY(lastSlideDownPx);
            }
            button.updateLayoutParams();
            button.invalidate();
        }
    }

    private static void updateSearchButtonVisibility(GlassNavigationBarSearchButton button) {
        final boolean visible = isSearchButtonActive()
                && searchAction != null
                && bottomNavAttached
                && OVERLAY_SCREENS.isEmpty()
                && showBottomNav
                && !barAutoHidden;
        final int targetVisibility = visible ? View.VISIBLE : View.GONE;
        if (button.getVisibility() != targetVisibility) {
            button.setVisibility(targetVisibility);
        }
    }

    static void onConfigurationChanged() {
        screenWidthDp = -1;
        GlassNavigationBarSearchButton button = buttonRef.get();
        if (button != null) {
            button.updateLayoutParams();
        }
        updateSearchButtonState();
    }

    static void invokeSearchAction() {
        try {
            Function0<?> action = searchAction;
            if (action != null) {
                action.invoke();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "invokeSearchAction failure", ex);
        }
    }

    static void invalidateSearchButton() {
        GlassNavigationBarSearchButton button = buttonRef.get();
        if (button != null && button.isShown()) {
            if (Utils.isCurrentlyOnMainThread()) {
                button.invalidate();
            } else {
                button.postInvalidate();
            }
        }
    }

    static boolean isSearchButtonVisible() {
        GlassNavigationBarSearchButton button = buttonRef.get();
        return button != null && button.isShown();
    }

    static int getPageColor() {
        return pageColor;
    }

    static boolean isGlassDark() {
        return switch (Settings.GLASS_NAVIGATION_BAR_COLOR.get()) {
            case AUTO -> isDarkTheme;
            case LIGHT -> false;
            case DARK -> true;
        };
    }

    static long getGlassColor(boolean dark) {
        final float opacity = FROSTED_ENABLED ? FROSTED_OPACITY : 1;
        final long alpha = Math.round(255 * OPACITY_PERCENT * opacity / 100);
        final long argb = (alpha << 24) | (dark ? GLASS_RGB_DARK : GLASS_RGB_LIGHT);

        return argb << 32;
    }
}
