/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5272
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
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

    /**
     * Compose ContentDrawScope. Sizes, offsets and corner radii are two packed floats.
     */
    public interface ContentDrawScope {
        // Methods are added during patching.
        long patch_getSize();

        void patch_drawContent();

        void patch_drawRoundRect(long color, long topLeft, long size, long cornerRadius);
    }

    /**
     * Compose Modifier.
     */
    public interface Modifier {
        // Methods are added during patching.
        Object patch_drawWithContent(Function1<?, ?> draw);

        Object patch_padding(float start, float top, float end, float bottom);
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
     * Draws a capsule behind the content. Not a lambda, because the extension shrinker
     * removes the invoke method of lambda classes as it is only called by app code.
     * <p>
     * The instances are not in the outer class, because Function1 is obfuscated in
     * Reddit 2026.10.0 and the outer class is always loaded by the settings.
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
     * Mask of everything except the alpha of a packed Compose sRGB color.
     */
    private static final long COLOR_WITHOUT_ALPHA_MASK = 0x00FFFFFFFFFFFFFFL;

    private static final int GLASS_RGB_DARK = 0x1C1C1E;
    private static final int GLASS_RGB_LIGHT = 0xF2F2F7;

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

    static final boolean GLASS_ENABLED = Settings.GLASS_NAVIGATION_BAR.get();
    private static final int OPACITY_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_OPACITY);
    private static final int HEIGHT_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_HEIGHT);
    private static final int WIDTH_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_WIDTH);

    /**
     * If the Reddit theme is dark, which can be different from the system theme.
     */
    static boolean isDarkTheme;

    /**
     * Packed Compose color of the page.
     */
    static long lastPageColor;

    private static boolean isRailCentered;

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
            if (GLASS_ENABLED && WIDTH_PERCENT < 100) {
                // Split screen windows are narrower than the display.
                Activity activity = Utils.getActivity();
                Context context = activity == null ? Utils.getContext() : activity;
                Configuration configuration = context.getResources().getConfiguration();

                final float padding = getSidePadding(configuration.screenWidthDp);
                return modifier.patch_padding(padding, 0, padding, 0);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getBarModifier failure", ex);
        }

        return modifier;
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
     * @param modifier Modifier of the column with the buttons of the side rail.
     */
    public static Object getRailModifier(Modifier modifier) {
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

    /**
     * @param color Packed Compose sRGB color, with the ARGB color in the upper 32 bits.
     */
    private static void updateTheme(long color) {
        lastPageColor = color;
        isDarkTheme = Color.luminance((int) (color >>> 32)) < 0.5f;
    }

    /**
     * @return Space on each side of the navigation bar, in the unit of the width.
     */
    private static float getSidePadding(float width) {
        return width * (100 - WIDTH_PERCENT) / 200;
    }

    private static void drawBarCapsule(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float width = unpackFirst(size);
        final float density = getDensity();
        final float rowHeight = BAR_ROW_HEIGHT_DP * density;

        final float left = getSidePadding(width)
                + BAR_CAPSULE_INSET_DP * density * WIDTH_PERCENT / 100;
        float height = BAR_CAPSULE_HEIGHT_DP * density * HEIGHT_PERCENT / 100;
        final float top = (rowHeight - height) / 2;

        if (unpackSecond(size) - rowHeight > GESTURE_NAVIGATION_MAX_HEIGHT_DP * density) {
            // Do not draw the capsule behind the system navigation bar.
            height = Math.min(height, rowHeight - top);
        }

        drawCapsule(scope, false, left, top, width - 2 * left, height, density);
    }

    private static void drawRailCapsule(ContentDrawScope scope) {
        final long size = scope.patch_getSize();
        final float density = getDensity();
        final float gap = RAIL_GAP_DP * density;
        // If not centered then the buttons use all space below the header.
        final float verticalGap = isRailCentered ? gap : 0;

        drawCapsule(
                scope,
                true,
                gap,
                -verticalGap,
                unpackFirst(size) - 2 * gap,
                unpackSecond(size) + 2 * verticalGap,
                density
        );
    }

    private static void drawCapsule(ContentDrawScope scope, boolean isRail, float left, float top,
                                    float width, float height, float density) {
        if (width <= 0 || height <= 0) {
            return;
        }

        final float radius = Settings.GLASS_NAVIGATION_BAR_ROUND_ENDS.get()
                ? Math.min(width, height) / 2
                : CORNER_RADIUS_DP * density;

        if (!isRail) {
            GlassNavigationBarBlur.drawBar(scope, left, top, width, height, radius);
        }
        scope.patch_drawRoundRect(
                getGlassColor(),
                pack(left, top),
                pack(width, height),
                pack(radius, radius)
        );
        GlassNavigationBarBlur.drawEdge(scope, left, top, width, height, radius);
    }

    static boolean glassIsDark() {
        return switch (Settings.GLASS_NAVIGATION_BAR_COLOR.get()) {
            case AUTO -> isDarkTheme;
            case LIGHT -> false;
            case DARK -> true;
        };
    }

    private static long getGlassColor() {
        final float factor = GlassNavigationBarBlur.blurActive() ? 0.6f : 1;
        final long alpha = Math.round(255 * OPACITY_PERCENT / 100f * factor);
        final long argb = (alpha << 24) | (glassIsDark() ? GLASS_RGB_DARK : GLASS_RGB_LIGHT);

        return argb << 32;
    }

    static float getDensity() {
        return Utils.getContext().getResources().getDisplayMetrics().density;
    }

    private static long pack(float first, float second) {
        return ((long) Float.floatToRawIntBits(first) << 32)
                | (Float.floatToRawIntBits(second) & 0xFFFFFFFFL);
    }

    private static float unpackFirst(long packed) {
        return Float.intBitsToFloat((int) (packed >>> 32));
    }

    private static float unpackSecond(long packed) {
        return Float.intBitsToFloat((int) packed);
    }
}
