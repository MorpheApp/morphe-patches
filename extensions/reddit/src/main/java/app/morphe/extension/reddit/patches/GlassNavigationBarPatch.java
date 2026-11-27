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
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Build;
import android.view.View;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import com.reddit.launch.bottomnav.BottomNavScreen;
import com.reddit.screen.ComposeBottomSheetScreen;

import java.util.List;

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

    public enum GlassStrength {
        CLEAR,
        BALANCED,
        SOLID
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
     * Draws glass behind the menu button or the avatar.
     */
    private static final class TopBarButtonDraw implements Function1<ContentDrawScope, Object> {
        private static final TopBarButtonDraw INSTANCE = new TopBarButtonDraw();

        private TopBarButtonDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                drawTopBarButtonGlass(scope);
            } catch (Exception ex) {
                Logger.printException(() -> "TopBarButtonDraw failure", ex);
            }

            scope.patch_drawContent();
            return null;
        }
    }

    /**
     * Draws a soft pill behind the selected bottom navigation bar button.
     */
    private static final class BarPillDraw implements Function1<ContentDrawScope, Object> {
        private static final BarPillDraw INSTANCE = new BarPillDraw();

        private BarPillDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            try {
                if (isBarPillActive()) {
                    drawNavPill(scope);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "BarPillDraw failure", ex);
            }

            scope.patch_drawContent();
            return null;
        }
    }

    /**
     * Click action of the hidden search field.
     */
    private static final class NoClick implements Function0<Object> {
        private static final NoClick INSTANCE = new NoClick();

        private NoClick() {
        }

        @Override
        public Object invoke() {
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
     * Solid glass is at least this opaque.
     */
    private static final int SOLID_MIN_OPACITY_PERCENT = 85;

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
    private static final float BAR_PILL_INSET_DP = 6;
    /**
     * Black at 7% opacity.
     */
    private static final long PILL_COLOR_LIGHT = 0x12000000L << 32;
    /**
     * White at 14% opacity.
     */
    private static final long PILL_COLOR_DARK = 0x24FFFFFFL << 32;

    /**
     * A taller system navigation bar has buttons and can be almost opaque.
     */
    private static final float GESTURE_NAVIGATION_MAX_HEIGHT_DP = 32;

    /**
     * Rail height needed for Reddit to show the buttons in the middle of the rail.
     */
    private static final float RAIL_CENTERED_MIN_HEIGHT_DP = 500;
    private static final float RAIL_GAP_DP = 12;

    /**
     * Narrower windows show the bottom navigation bar instead of the side rail.
     */
    private static final int SIDE_RAIL_MIN_WIDTH_DP = 600;
    private static final float SEARCH_BUTTON_GAP_DP = 10;

    /**
     * How much larger the glass behind the menu button and the avatar is than the button.
     */
    static final float TOP_BAR_BUTTON_GLASS_EXTRA_DP = 8;

    /**
     * Minimum height of a top bar button. Smaller slots are empty (such as a hidden avatar).
     */
    private static final float MIN_TOP_BAR_BUTTON_DP = 12;

    private static final boolean GLASS_ENABLED = Settings.GLASS_NAVIGATION_BAR.get();
    static final GlassStrength STRENGTH = GLASS_ENABLED
            ? Settings.GLASS_NAVIGATION_BAR_STRENGTH.get()
            : GlassStrength.CLEAR;
    private static final int OPACITY_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_OPACITY);
    static final int BLUR_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_BLUR);
    private static final int HEIGHT_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_HEIGHT);
    private static final int WIDTH_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_WIDTH);
    /**
     * How much taller the row of buttons is, so a capsule taller than the default fits in it.
     */
    private static final float BAR_ROW_EXTRA_HEIGHT_DP = GLASS_ENABLED
            ? Math.max(0, BAR_CAPSULE_HEIGHT_DP * (HEIGHT_PERCENT - 100) / 100)
            : 0;
    /**
     * How far the bottom bar is lifted.
     */
    private static final int POSITION_DP = GLASS_ENABLED
            ? SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_POSITION)
            : 0;
    private static final boolean HIDE_LABELS = GLASS_ENABLED && !Settings.GLASS_NAVIGATION_BAR_SHOW_LABELS.get();
    private static final boolean SEARCH_BUTTON_ENABLED = GLASS_ENABLED
            && isSearchButtonSupported()
            && Settings.GLASS_NAVIGATION_BAR_SEARCH_BUTTON.get();

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

    /**
     * If the side rail is shown instead of the bottom navigation bar.
     */
    private static boolean isRailShown;

    /**
     * Full glass height of the top bar buttons, in pixels.
     */
    private static float topBarButtonGlassHeightPx;

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false; // Modified during patching.
    }

    /**
     * @return If the app version supports the search button.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isSearchButtonSupported() {
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
            if (!GLASS_ENABLED) {
                return modifier;
            }

            setRailShown(false);

            // The buttons sit inside the capsule, which ends before the search button when active.
            final float start = getSidePadding(getScreenWidthDp())
                    + BAR_CAPSULE_INSET_DP * WIDTH_PERCENT / 100;
            final float end = isSearchButtonActive()
                    ? start + BAR_CAPSULE_HEIGHT_DP * HEIGHT_PERCENT / 100 + SEARCH_BUTTON_GAP_DP
                    : start;
            return modifier.patch_padding(start, 0, end, 0);
        } catch (Exception ex) {
            Logger.printException(() -> "getBarModifier failure", ex);
        }

        return modifier;
    }

    /**
     * Injection point.
     *
     * @param original Height of the bottom navigation bar buttons, in dp.
     */
    public static float getBarRowHeight(float original) {
        return original + BAR_ROW_EXTRA_HEIGHT_DP;
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
        if (POSITION_DP == 0 && !FROSTED_ENABLED && !SEARCH_BUTTON_ENABLED) {
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
        if (POSITION_DP == 0 && !FROSTED_ENABLED && !SEARCH_BUTTON_ENABLED) {
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
     * @param selected If the bottom navigation bar button is selected.
     * @param modifier Modifier of the bottom navigation bar button.
     */
    @Nullable
    public static Object getBarButtonModifier(boolean selected, @Nullable Modifier modifier) {
        return selected && modifier != null && isBarPillActive()
                ? modifier.patch_drawWithContent(BarPillDraw.INSTANCE)
                : modifier;
    }

    /**
     * Injection point.
     *
     * @param original Space above the icon of a bottom navigation bar button, in dp.
     */
    public static float getBarButtonTopSpace(float original) {
        return isBarPillActive() ? 0 : original;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the search field at the top of Home.
     */
    public static Object getSearchFieldModifier(Modifier modifier) {
        return isSearchButtonActive()
                ? modifier.patch_drawWithContent(EmptyDraw.INSTANCE)
                : modifier;
    }

    /**
     * Injection point.
     *
     * @param action Opens search, if the search field at the top of Home is clicked.
     */
    public static Function0<?> getSearchFieldClick(Function0<?> action) {
        try {
            if (SEARCH_BUTTON_ENABLED) {
                GlassNavigationBarSearchButton.setSearchAction(action);

                if (isSearchButtonActive()) {
                    return NoClick.INSTANCE;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getSearchFieldClick failure", ex);
        }

        return action;
    }

    /**
     * Injection point.
     *
     * @param modifier Modifier of the menu button or of the avatar at the top of Home.
     */
    public static Object getTopBarButtonModifier(Modifier modifier) {
        return isSearchButtonActive()
                ? modifier.patch_drawWithContent(TopBarButtonDraw.INSTANCE)
                : modifier;
    }

    /**
     * @return If the glass of the Home top bar buttons and tabs blurs the feed.
     */
    private static boolean isTopBarFrosted() {
        return FROSTED_ENABLED && isSearchButtonActive();
    }

    /**
     * @param modifier Modifier of the Home feed.
     */
    static Object getFeedFrostedModifier(Modifier modifier) {
        return isTopBarFrosted()
                ? modifier.patch_drawWithContent(GlassNavigationBarBlur.FeedDraw.INSTANCE)
                : modifier;
    }

    /**
     * @param modifier Modifier of the Home top bar, outside its graphics layer.
     */
    static Object getTopBarFrostedModifier(Modifier modifier) {
        return isTopBarFrosted()
                ? modifier.patch_drawWithContent(GlassNavigationBarBlur.TopBarDraw.INSTANCE)
                : modifier;
    }

    /**
     * Injection point.
     */
    public static void onScreenAttached(Object screen, @Nullable View view) {
        if (!SEARCH_BUTTON_ENABLED) {
            return;
        }

        try {
            if (screen instanceof BottomNavScreen) {
                GlassNavigationBarSearchButton.onBottomNavAttached(view);
            } else if (screen instanceof ComposeBottomSheetScreen) {
                GlassNavigationBarSearchButton.onBottomSheetAttached(screen);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onScreenAttached failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onScreenDetached(Object screen, @Nullable View view) {
        if (!SEARCH_BUTTON_ENABLED) {
            return;
        }

        try {
            if (screen instanceof BottomNavScreen) {
                GlassNavigationBarSearchButton.onBottomNavDetached();
            } else if (screen instanceof ComposeBottomSheetScreen) {
                GlassNavigationBarSearchButton.onBottomSheetDetached(screen);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onScreenDetached failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param show If the current screen shows the bottom navigation bar.
     */
    public static void setShowBottomNav(boolean show) {
        if (!SEARCH_BUTTON_ENABLED) {
            return;
        }

        try {
            GlassNavigationBarSearchButton.setShowBottomNav(show);
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
        if (!GLASS_ENABLED) {
            return modifier;
        }

        setRailShown(true);
        return modifier.patch_drawWithContent(CapsuleDraw.RAIL);
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

    /**
     * @return If search is a button next to the bottom navigation bar.
     */
    static boolean isSearchButtonActive() {
        // The window width is checked too, because the top of Home can be shown before the side rail.
        return SEARCH_BUTTON_ENABLED && !isRailShown && getScreenWidthDp() < SIDE_RAIL_MIN_WIDTH_DP;
    }

    static boolean isSearchButtonEnabled() {
        return SEARCH_BUTTON_ENABLED;
    }

    private static boolean isBarPillActive() {
        return GLASS_ENABLED && !isRailShown && getScreenWidthDp() < SIDE_RAIL_MIN_WIDTH_DP;
    }

    static float getTopBarButtonGlassHeightPx() {
        return topBarButtonGlassHeightPx;
    }

    private static void setRailShown(boolean shown) {
        if (SEARCH_BUTTON_ENABLED && isRailShown != shown) {
            isRailShown = shown;
            GlassNavigationBarSearchButton.update();
        }
    }

    private static int getScreenWidthDp() {
        // Split screen windows are narrower than the display.
        Activity activity = Utils.getActivity();
        Context context = activity == null ? Utils.getContext() : activity;
        return context.getResources().getConfiguration().screenWidthDp;
    }

    /**
     * @param color Packed Compose sRGB color, with the ARGB color in the upper 32 bits.
     */
    private static void updateTheme(long color) {
        final int newPageColor = (int) (color >>> 32) | 0xFF000000;
        final boolean changed = pageColor != newPageColor;
        pageColor = newPageColor;
        isDarkTheme = Color.luminance(pageColor) < 0.5f;

        if (changed && SEARCH_BUTTON_ENABLED) {
            GlassNavigationBarSearchButton.redraw();
        }
    }

    /**
     * @return Space on each side of the navigation bar, in the unit of the width.
     */
    private static float getSidePadding(float width) {
        return width * (100 - WIDTH_PERCENT) / 200;
    }

    /**
     * @return How far the bottom bar is lifted up, in pixels.
     */
    private static float getLift() {
        return POSITION_DP * getDensity();
    }

    /**
     * @param barOffset How far down the bar is moved, in pixels.
     * @param hidden    If the bar is moved off the screen.
     */
    private static void updateBarOffset(float barOffset, boolean hidden) {
        if (FROSTED_ENABLED) {
            GlassNavigationBarBlur.setBarOffset(Math.round(barOffset));
        }
        if (SEARCH_BUTTON_ENABLED) {
            GlassNavigationBarSearchButton.setBarOffset(Math.round(barOffset), hidden);
        }
    }

    private static void drawBarCapsule(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float width = unpackFirst(size);
        final float barHeight = unpackSecond(size);
        final float density = getDensity();
        final float rowHeight = getBarRowHeightPx(density);

        float left = getSidePadding(width)
                + BAR_CAPSULE_INSET_DP * density * WIDTH_PERCENT / 100;
        float height = getBarCapsuleHeightPx(density);
        final float top = (rowHeight - height) / 2;

        if (barHeight - rowHeight > GESTURE_NAVIGATION_MAX_HEIGHT_DP * density) {
            // Do not draw the capsule behind the system navigation bar.
            height = Math.min(height, rowHeight - top + getLift());
        }

        float capsuleWidth = width - 2 * left;
        if (isSearchButtonActive()) {
            // The search button is a circle as tall as the capsule, at the end of the bar.
            GlassNavigationBarSearchButton.setBounds(
                    Math.round(height),
                    Math.round(left),
                    Math.round(barHeight - top - height)
            );

            final float buttonWidth = height + SEARCH_BUTTON_GAP_DP * density;
            capsuleWidth -= buttonWidth;
            if (Utils.isRightToLeftLocale()) {
                left += buttonWidth;
            }
        }

        drawCapsule(scope, left, top, capsuleWidth, height, density, true);
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
        Canvas canvas = scope.patch_getCanvas().patch_getNativeCanvas();

        GlassNavigationBarEdge.drawShadow(canvas, left, top, width, height, radius);

        if (FROSTED_ENABLED && hasPageBehind) {
            GlassNavigationBarBlur.drawBackdrop(scope, left, top, width, height, radius, pageColor);
        }

        scope.patch_drawRoundRect(
                getGlassColor(dark),
                pack(left, top),
                pack(width, height),
                pack(radius, radius)
        );

        GlassNavigationBarEdge.drawEdges(canvas, left, top, width, height, radius, dark);
    }

    /**
     * Draws a circle, or a pill if the button is wider than tall.
     */
    private static void drawTopBarButtonGlass(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float density = getDensity();
        final float elementHeight = unpackSecond(size);
        if (elementHeight < MIN_TOP_BAR_BUTTON_DP * density) {
            return;
        }

        final float extra = TOP_BAR_BUTTON_GLASS_EXTRA_DP * density;
        final float height = elementHeight + extra;
        final float width = Math.max(unpackFirst(size) + extra, height);
        final float left = (unpackFirst(size) - width) / 2;
        final float top = -extra / 2;

        topBarButtonGlassHeightPx = height;
        drawTopBarGlass(scope, left, top, width, height);
    }

    /**
     * Draws a glass pill behind a top bar element.
     */
    static void drawTopBarGlass(ContentDrawScope scope, float left, float top,
                                float width, float height) {
        if (width <= 0 || height <= 0) {
            return;
        }

        final float radius = height / 2;
        final boolean dark = isGlassDark();
        Canvas canvas = scope.patch_getCanvas().patch_getNativeCanvas();

        GlassNavigationBarEdge.drawShadow(canvas, left, top, width, height, radius);

        if (FROSTED_ENABLED) {
            GlassNavigationBarBlur.drawTopBarBackdrop(scope, left, top, width, height, radius);
        }

        scope.patch_drawRoundRect(
                getGlassColor(dark),
                pack(left, top),
                pack(width, height),
                pack(radius, radius)
        );

        GlassNavigationBarEdge.drawOutline(canvas, left, top, width, height, radius, dark);
    }

    private static float getBarRowHeightPx(float density) {
        return (BAR_ROW_HEIGHT_DP + BAR_ROW_EXTRA_HEIGHT_DP) * density;
    }

    private static float getBarCapsuleHeightPx(float density) {
        return BAR_CAPSULE_HEIGHT_DP * density * HEIGHT_PERCENT / 100;
    }

    private static void drawNavPill(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float width = unpackFirst(size);
        final float height = unpackSecond(size);
        if (width <= 0 || height <= 0) {
            return;
        }

        final float density = getDensity();
        final float inset = BAR_PILL_INSET_DP * density;
        final float pillWidth = width - 2 * inset;
        final float pillHeight = getBarCapsuleHeightPx(density) - 2 * inset;
        if (pillWidth <= 0 || pillHeight <= 0) {
            return;
        }

        final float left = (width - pillWidth) / 2;
        final float top = (height - pillHeight) / 2;
        final float radius = Settings.GLASS_NAVIGATION_BAR_ROUND_ENDS.get()
                ? pillHeight / 2
                : (CORNER_RADIUS_DP - BAR_PILL_INSET_DP) * density;

        drawPill(scope, left, top, pillWidth, pillHeight, radius);
    }

    /**
     * Draws a pill behind a selected button or tab.
     */
    static void drawPill(ContentDrawScope scope, float left, float top,
                         float width, float height, float radius) {
        scope.patch_drawRoundRect(
                isGlassDark() ? PILL_COLOR_DARK : PILL_COLOR_LIGHT,
                pack(left, top),
                pack(width, height),
                pack(radius, radius)
        );
    }

    static int getPageColor() {
        return pageColor;
    }

    static long getPackedPageColor() {
        return ((long) pageColor) << 32;
    }

    static boolean isGlassDark() {
        return switch (Settings.GLASS_NAVIGATION_BAR_COLOR.get()) {
            case AUTO -> isDarkTheme;
            case LIGHT -> false;
            case DARK -> true;
        };
    }

    static long getGlassColor(boolean dark) {
        final int opacityPercent = STRENGTH == GlassStrength.SOLID
                ? Math.max(OPACITY_PERCENT, SOLID_MIN_OPACITY_PERCENT)
                : OPACITY_PERCENT;
        final float opacity = FROSTED_ENABLED ? FROSTED_OPACITY : 1;
        final long alpha = Math.round(255 * opacityPercent * opacity / 100);
        final long argb = (alpha << 24) | (dark ? GLASS_RGB_DARK : GLASS_RGB_LIGHT);

        return argb << 32;
    }
}
