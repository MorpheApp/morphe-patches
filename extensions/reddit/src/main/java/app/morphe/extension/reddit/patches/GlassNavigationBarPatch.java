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
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Build;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.ColorInt;

import java.util.List;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.utils.ComposeUtils.ContentDrawScope;
import app.morphe.extension.reddit.utils.ComposeUtils.Modifier;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
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

    private static final boolean GLASS_ENABLED = Settings.GLASS_NAVIGATION_BAR.get();
    private static final int OPACITY_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_OPACITY);
    static final int BLUR_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_BLUR);
    private static final int HEIGHT_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_HEIGHT);
    private static final int WIDTH_PERCENT = SeekBarPreference.clampToRange(Settings.GLASS_NAVIGATION_BAR_WIDTH);

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private static final boolean FROSTED_ENABLED = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
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
        pageColor = (int) (color >>> 32) | 0xFF000000;
        isDarkTheme = Color.luminance(pageColor) < 0.5f;
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

        drawCapsule(scope, left, top, width - 2 * left, height, density, true);
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

    private static boolean isGlassDark() {
        return switch (Settings.GLASS_NAVIGATION_BAR_COLOR.get()) {
            case AUTO -> isDarkTheme;
            case LIGHT -> false;
            case DARK -> true;
        };
    }

    private static long getGlassColor(boolean dark) {
        final float opacity = FROSTED_ENABLED ? FROSTED_OPACITY : 1;
        final long alpha = Math.round(255 * OPACITY_PERCENT * opacity / 100);
        final long argb = (alpha << 24) | (dark ? GLASS_RGB_DARK : GLASS_RGB_LIGHT);

        return argb << 32;
    }
}
