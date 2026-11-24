/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import java.util.List;
import kotlin.jvm.functions.Function1;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public final class GlassNavigationBarPatch {

    public enum GlassColor {
        AUTO,
        LIGHT,
        DARK
    }

    public static final float BAR_ROW_HEIGHT_DP = 50f;

    private static final float RAIL_THRESHOLD_HEIGHT_DP = 500f;
    private static final float RAIL_HEADER_HEIGHT_DP = 60f;
    private static final float RAIL_TAB_HEIGHT_DP = 64f;
    private static final float RAIL_GAP_DP = 12f;

    private static volatile float cachedDensity = -1f;
    private static volatile Boolean cachedIsDark;
    private static volatile int railTabCount;

    private static final Function1<Object, Object> BAR_DRAW =
            new Function1<Object, Object>() {
                @Override
                public Object invoke(Object scope) {
                    try {
                        if (isEnabled()) {
                            drawBarCapsule(scope);
                        }
                    } catch (Throwable ex) {
                        Logger.printException(() -> "drawBarCapsule failure", ex);
                    } finally {
                        try {
                            drawContent(scope);
                        } catch (Throwable ex) {
                            Logger.printException(() -> "drawContent failure", ex);
                        }
                    }
                    return null;
                }
            };

    private static final Function1<Object, Object> RAIL_DRAW =
            new Function1<Object, Object>() {
                @Override
                public Object invoke(Object scope) {
                    try {
                        if (isEnabled()) {
                            drawRailCapsule(scope);
                        }
                    } catch (Throwable ex) {
                        Logger.printException(() -> "drawRailCapsule failure", ex);
                    } finally {
                        try {
                            drawContent(scope);
                        } catch (Throwable ex) {
                            Logger.printException(() -> "drawContent failure", ex);
                        }
                    }
                    return null;
                }
            };

    private GlassNavigationBarPatch() {}

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;
    }

    private static boolean isEnabled() {
        return isPatchIncluded() && Settings.GLASS_NAVIGATION_BAR.get();
    }

    private static int getOpacity() {
        final int val = Settings.GLASS_NAVIGATION_BAR_OPACITY.get();
        return (val < 0 || val > 100) ? 70 : val;
    }

    private static int getHeightPercent() {
        final int val = Settings.GLASS_NAVIGATION_BAR_HEIGHT.get();
        return (val < 70 || val > 150) ? 100 : val;
    }

    private static int getWidthPercent() {
        final int val = Settings.GLASS_NAVIGATION_BAR_WIDTH.get();
        return (val < 50 || val > 100) ? 100 : val;
    }

    private static void updateDarkTheme(long color) {
        try {
            final int argb = (int) (color >>> 32);
            final int r = (argb >> 16) & 0xFF;
            final int g = (argb >> 8) & 0xFF;
            final int b = argb & 0xFF;
            final double luminance = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0;
            cachedIsDark = luminance < 0.5;
        } catch (Throwable ex) {
            Logger.printException(() -> "updateDarkTheme failure", ex);
        }
    }

    public static long barColor(long original) {
        try {
            updateDarkTheme(original);

            if (!isEnabled()) {
                return original;
            }
            return original & 0x00FFFFFFFFFFFFFFL;
        } catch (Throwable ex) {
            Logger.printException(() -> "barColor failure", ex);
            return original;
        }
    }

    public static long barBackdrop(long color) {
        try {
            if (isEnabled()) {
                return color & 0x00FFFFFFFFFFFFFFL;
            }
            return color;
        } catch (Throwable ex) {
            Logger.printException(() -> "barBackdrop failure", ex);
            return color;
        }
    }

    public static long pageColor(Object colors) {
        try {
            if (isEnabled()) {
                return feedColor(colors);
            }
            return stripColor(colors);
        } catch (Throwable ex) {
            Logger.printException(() -> "pageColor failure", ex);
            try {
                return stripColor(colors);
            } catch (Throwable t) {
                return 0L;
            }
        }
    }

    public static void railThemeColor(long color) {
        try {
            updateDarkTheme(color);
        } catch (Throwable ex) {
            Logger.printException(() -> "railThemeColor failure", ex);
        }
    }

    public static void railTabs(Object tabs) {
        try {
            if (tabs instanceof List) {
                railTabCount = ((List<?>) tabs).size();
            } else {
                railTabCount = 0;
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "railTabs failure", ex);
            railTabCount = 0;
        }
    }

    public static float barElevation(float original) {
        try {
            if (isEnabled()) {
                return 0f;
            }
            return original;
        } catch (Throwable ex) {
            Logger.printException(() -> "barElevation failure", ex);
            return original;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static Function1 barDraw(Function1 original) {
        try {
            if (isEnabled()) {
                return BAR_DRAW;
            }
            return original;
        } catch (Throwable ex) {
            Logger.printException(() -> "barDraw failure", ex);
            return original;
        }
    }

    @SuppressWarnings("rawtypes")
    public static Function1 railDraw() {
        try {
            return RAIL_DRAW;
        } catch (Throwable ex) {
            Logger.printException(() -> "railDraw failure", ex);
            return RAIL_DRAW;
        }
    }

    public static boolean padContentForBottomNav(boolean original) {
        try {
            if (isEnabled()) {
                return false;
            }
            return original;
        } catch (Throwable ex) {
            Logger.printException(() -> "padContentForBottomNav failure", ex);
            return original;
        }
    }

    public static Object barModifier(Object modifier) {
        try {
            if (!isEnabled()) {
                return modifier;
            }
            final int width = getWidthPercent();
            if (width == 100) {
                return modifier;
            }
            final float density = getDensity();
            final float screenWidthDp = Resources.getSystem().getDisplayMetrics().widthPixels / density;
            final float pad = screenWidthDp * (100 - width) / 200f;
            return padding(modifier, pad, 0f, pad, 0f);
        } catch (Throwable ex) {
            Logger.printException(() -> "barModifier failure", ex);
            return modifier;
        }
    }

    private static boolean isAppNightMode() {
        try {
            final Context context = Utils.getContext();
            final Resources res = context != null ? context.getResources() : Resources.getSystem();
            final int uiMode = res.getConfiguration().uiMode;
            return (uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        } catch (Throwable ex) {
            Logger.printException(() -> "isAppNightMode failure", ex);
            return false;
        }
    }

    private static boolean isDarkTheme() {
        final Boolean dark = cachedIsDark;
        if (dark != null) {
            return dark;
        }
        return isAppNightMode();
    }

    private static long getCapsuleColor() {
        try {
            final GlassColor tint = Settings.GLASS_NAVIGATION_BAR_COLOR.get();
            final boolean effectiveDark;
            if (tint == GlassColor.LIGHT) {
                effectiveDark = false;
            } else if (tint == GlassColor.DARK) {
                effectiveDark = true;
            } else {
                effectiveDark = isDarkTheme();
            }
            final int rgb = effectiveDark ? 0x1C1C1E : 0xF2F2F7;
            final int opacity = getOpacity();
            final int alpha = Math.round(255f * opacity / 100f);
            final int argb = (alpha << 24) | rgb;
            return ((long) argb & 0xFFFFFFFFL) << 32;
        } catch (Throwable ex) {
            Logger.printException(() -> "getCapsuleColor failure", ex);
            final int alpha = Math.round(255f * 70f / 100f);
            final int argb = (alpha << 24) | 0x1C1C1E;
            return ((long) argb & 0xFFFFFFFFL) << 32;
        }
    }

    private static float getDensity() {
        if (cachedDensity <= 0f) {
            try {
                final Context context = Utils.getContext();
                final Resources res = context != null ? context.getResources() : Resources.getSystem();
                cachedDensity = res.getDisplayMetrics().density;
            } catch (Throwable ex) {
                Logger.printException(() -> "getDensity failure", ex);
                return 1.0f;
            }
        }
        return cachedDensity;
    }

    private static long packFloats(float x, float y) {
        return ((long) Float.floatToRawIntBits(x) << 32) | (Float.floatToRawIntBits(y) & 0xFFFFFFFFL);
    }

    private static void drawBarCapsule(Object scope) {
        final long size = drawScopeSize(scope);
        final float width = Float.intBitsToFloat((int) (size >>> 32));

        final float density = getDensity();
        final int widthPercent = getWidthPercent();
        final float pad = width * (100 - widthPercent) / 200f;
        final float inset = 20f * density * widthPercent / 100f;
        final float left = pad + inset;
        final float capsuleWidth = width - (2f * pad) - (2f * inset);

        final int heightPercent = getHeightPercent();
        final float capsuleHeight = (BAR_ROW_HEIGHT_DP + 10f) * density * heightPercent / 100f;
        final float top = (BAR_ROW_HEIGHT_DP * density - capsuleHeight) / 2f;

        if (capsuleWidth <= 0f || capsuleHeight <= 0f) {
            return;
        }

        final float radius = Settings.GLASS_NAVIGATION_BAR_ROUND_ENDS.get()
                ? capsuleHeight / 2f
                : 18f * density;
        final long topLeft = packFloats(left, top);
        final long capsuleSize = packFloats(capsuleWidth, capsuleHeight);
        final long cornerRadius = packFloats(radius, radius);
        final long color = getCapsuleColor();

        drawRoundRect(scope, color, topLeft, capsuleSize, cornerRadius, null, 1.0f, 0xF0);
    }

    private static void drawRailCapsule(Object scope) {
        final long size = drawScopeSize(scope);
        final float width = Float.intBitsToFloat((int) (size >>> 32));
        final float height = Float.intBitsToFloat((int) size);

        final float density = getDensity();
        final float heightDp = height / density;

        final float left = RAIL_GAP_DP * density;
        final float capsuleWidth = width - (2f * RAIL_GAP_DP * density);
        final float top;
        final float capsuleHeight;

        final int n = railTabCount;
        if (heightDp > RAIL_THRESHOLD_HEIGHT_DP && n > 0) {
            final float tabsHeight = (n * RAIL_TAB_HEIGHT_DP) + ((n - 1) * RAIL_GAP_DP);
            final float tabsTop = RAIL_HEADER_HEIGHT_DP + (heightDp - RAIL_HEADER_HEIGHT_DP - tabsHeight) / 2f;
            top = (tabsTop - RAIL_GAP_DP) * density;
            capsuleHeight = (tabsHeight + (2f * RAIL_GAP_DP)) * density;
        } else {
            top = 16f * density;
            capsuleHeight = height - 32f * density;
        }

        if (capsuleWidth <= 0f || capsuleHeight <= 0f) {
            return;
        }

        final float radius = Settings.GLASS_NAVIGATION_BAR_ROUND_ENDS.get()
                ? capsuleWidth / 2f
                : 18f * density;
        final long topLeft = packFloats(left, top);
        final long capsuleSize = packFloats(capsuleWidth, capsuleHeight);
        final long cornerRadius = packFloats(radius, radius);
        final long color = getCapsuleColor();

        drawRoundRect(scope, color, topLeft, capsuleSize, cornerRadius, null, 1.0f, 0xF0);
    }

    private static Object padding(
            Object modifier, float start, float top, float end, float bottom) {
        return modifier;
    }

    private static void drawRoundRect(
            Object scope,
            long color,
            long topLeft,
            long size,
            long cornerRadius,
            Object style,
            float alpha,
            int flags) {}

    private static long drawScopeSize(Object scope) {
        long v = 0L;
        return v;
    }

    private static void drawContent(Object scope) {}

    private static long feedColor(Object colors) {
        long v = 0L;
        return v;
    }

    private static long stripColor(Object colors) {
        long v = 0L;
        return v;
    }
}
