/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5363
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.reddit.utils.ComposeUtils.getDensity;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.GlassStrength;

final class GlassNavigationBarStrength {

    private static final float SHADOW_OFFSET_Y_DP = 1f;
    private static final float BALANCED_SHADOW_BLUR_DP = 6f;
    private static final float SOLID_SHADOW_BLUR_DP = 10f;
    private static final int BALANCED_SHADOW_COLOR = 0x24000000;
    private static final int SOLID_SHADOW_COLOR = 0x3D000000;
    private static final float SHADOW_CLIP_INSET_PX = 1f;

    private static final float EDGE_WIDTH_DP = 1f;
    private static final int INNER_START_DARK = 0x40FFFFFF;
    private static final int INNER_START_LIGHT = 0xB3FFFFFF;
    private static final int INNER_END_DARK = 0x0DFFFFFF;
    private static final int INNER_END_LIGHT = 0x14000000;
    private static final int BALANCED_HAIRLINE_LIGHT = 0x1F000000;
    private static final int BALANCED_HAIRLINE_DARK = 0x1AFFFFFF;
    private static final int SOLID_HAIRLINE_LIGHT = 0x38000000;
    private static final int SOLID_HAIRLINE_DARK = 0x2EFFFFFF;

    private static final float SEARCH_BUTTON_SHADOW_MARGIN_DP = 16f;
    private static final int SHADER_CACHE_SIZE = 4;

    private static final Path CLIP_PATH = new Path();
    private static final float[] SHADER_TOP = new float[SHADER_CACHE_SIZE];
    private static final float[] SHADER_HEIGHT = new float[SHADER_CACHE_SIZE];
    private static final boolean[] SHADER_DARK = new boolean[SHADER_CACHE_SIZE];
    private static final LinearGradient[] SHADERS = new LinearGradient[SHADER_CACHE_SIZE];
    private static int shaderNextSlot;

    @Nullable
    private static Paint shadowPaint;
    @Nullable
    private static GlassStrength cachedShadowStrength;
    private static float cachedShadowDensity = -1f;

    @Nullable
    private static Paint innerPaint;
    @Nullable
    private static Paint outerPaint;

    private GlassNavigationBarStrength() {
    }

    static int getSearchButtonMarginPx() {
        if (GlassNavigationBarPatch.STRENGTH == GlassStrength.CLEAR) {
            return 0;
        }
        return (int) Math.ceil(SEARCH_BUTTON_SHADOW_MARGIN_DP * getDensity());
    }

    static void drawShadow(@Nullable Canvas canvas, float left, float top,
                           float width, float height, float radius) {
        if (canvas == null
                || Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || width <= 0f
                || height <= 0f) {
            return;
        }
        GlassStrength strength = GlassNavigationBarPatch.STRENGTH;
        if (strength == GlassStrength.CLEAR) {
            return;
        }

        final float density = getDensity();
        if (shadowPaint == null) {
            shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            shadowPaint.setStyle(Paint.Style.FILL);
            shadowPaint.setColor(Color.BLACK);
        }
        if (cachedShadowStrength != strength || cachedShadowDensity != density) {
            final float blurPx = (strength == GlassStrength.SOLID
                    ? SOLID_SHADOW_BLUR_DP
                    : BALANCED_SHADOW_BLUR_DP) * density;
            final float dyPx = SHADOW_OFFSET_Y_DP * density;
            final int shadowColor = strength == GlassStrength.SOLID
                    ? SOLID_SHADOW_COLOR
                    : BALANCED_SHADOW_COLOR;
            shadowPaint.setShadowLayer(blurPx, 0f, dyPx, shadowColor);
            cachedShadowStrength = strength;
            cachedShadowDensity = density;
        }

        CLIP_PATH.reset();
        CLIP_PATH.addRoundRect(left, top, left + width, top + height,
                radius, radius, Path.Direction.CW);
        final int saveCount = canvas.save();
        try {
            canvas.clipOutPath(CLIP_PATH);
            // Inset by 1px so the opaque fill's AA edge stays strictly inside clipOutPath.
            final float inset = (width > 2f && height > 2f) ? SHADOW_CLIP_INSET_PX : 0f;
            final float shadowRadius = Math.max(0f, radius - inset);
            canvas.drawRoundRect(
                    left + inset,
                    top + inset,
                    left + width - inset,
                    top + height - inset,
                    shadowRadius,
                    shadowRadius,
                    shadowPaint
            );
        } finally {
            canvas.restoreToCount(saveCount);
        }
    }

    static void drawOutline(@Nullable Canvas canvas, float left, float top,
                            float width, float height, float radius) {
        if (canvas == null || width <= 0f || height <= 0f) {
            return;
        }
        GlassStrength strength = GlassNavigationBarPatch.STRENGTH;
        if (strength == GlassStrength.CLEAR) {
            return;
        }

        final float density = getDensity();
        final float strokeWidth = EDGE_WIDTH_DP * density;
        final float halfStroke = strokeWidth / 2f;
        final boolean isDark = GlassNavigationBarPatch.isGlassDark();

        if (innerPaint == null) {
            innerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            innerPaint.setStyle(Paint.Style.STROKE);
        }
        innerPaint.setStrokeWidth(strokeWidth);
        innerPaint.setShader(getOrCreateInnerShader(top, height, isDark));

        final float innerLeft = left + halfStroke;
        final float innerTop = top + halfStroke;
        final float innerRight = left + width - halfStroke;
        final float innerBottom = top + height - halfStroke;
        if (innerRight > innerLeft && innerBottom > innerTop) {
            final float innerRadius = Math.max(0f, radius - halfStroke);
            canvas.drawRoundRect(innerLeft, innerTop, innerRight, innerBottom,
                    innerRadius, innerRadius, innerPaint);
        }

        if (outerPaint == null) {
            outerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            outerPaint.setStyle(Paint.Style.STROKE);
        }
        outerPaint.setStrokeWidth(strokeWidth);
        final int hairlineColor;
        if (strength == GlassStrength.SOLID) {
            hairlineColor = isDark ? SOLID_HAIRLINE_DARK : SOLID_HAIRLINE_LIGHT;
        } else {
            hairlineColor = isDark ? BALANCED_HAIRLINE_DARK : BALANCED_HAIRLINE_LIGHT;
        }
        outerPaint.setColor(hairlineColor);
        canvas.drawRoundRect(
                left - halfStroke,
                top - halfStroke,
                left + width + halfStroke,
                top + height + halfStroke,
                radius + halfStroke,
                radius + halfStroke,
                outerPaint
        );
    }

    private static LinearGradient getOrCreateInnerShader(float top, float height, boolean isDark) {
        for (int i = 0; i < SHADER_CACHE_SIZE; i++) {
            if (SHADERS[i] != null
                    && SHADER_TOP[i] == top
                    && SHADER_HEIGHT[i] == height
                    && SHADER_DARK[i] == isDark) {
                return SHADERS[i];
            }
        }
        final int colorStart = isDark ? INNER_START_DARK : INNER_START_LIGHT;
        final int colorEnd = isDark ? INNER_END_DARK : INNER_END_LIGHT;
        LinearGradient shader = new LinearGradient(
                0f, top, 0f, top + height, colorStart, colorEnd, Shader.TileMode.CLAMP);
        final int slot = shaderNextSlot;
        shaderNextSlot = (slot + 1) % SHADER_CACHE_SIZE;
        SHADER_TOP[slot] = top;
        SHADER_HEIGHT[slot] = height;
        SHADER_DARK[slot] = isDark;
        SHADERS[slot] = shader;
        return shader;
    }
}
