/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5363
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.reddit.patches.GlassNavigationBarPatch.FROSTED_ENABLED;
import static app.morphe.extension.reddit.patches.GlassNavigationBarPatch.STRENGTH;
import static app.morphe.extension.reddit.utils.ComposeUtils.getDensity;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;

import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.GlassStrength;

/**
 * Shadow and edges of the glass shapes.
 */
final class GlassNavigationBarEdge {

    private static final float EDGE_WIDTH_DP = 1;

    private static final float SHADOW_OFFSET_DP = 1;
    private static final float BALANCED_SHADOW_BLUR_DP = 6;
    private static final float SOLID_SHADOW_BLUR_DP = 10;
    private static final int BALANCED_SHADOW_COLOR = 0x24000000;
    private static final int SOLID_SHADOW_COLOR = 0x3D000000;
    /**
     * The shadow shape is this much smaller, so its anti aliased edge is clipped away.
     */
    private static final float SHADOW_INSET_PX = 1;

    private static final int BALANCED_OUTLINE_LIGHT = 0x1F000000;
    private static final int BALANCED_OUTLINE_DARK = 0x1AFFFFFF;
    private static final int SOLID_OUTLINE_LIGHT = 0x38000000;
    private static final int SOLID_OUTLINE_DARK = 0x2EFFFFFF;

    /**
     * Space around the search button for its shadow.
     */
    private static final float SHADOW_MARGIN_DP = 16;

    private static final Paint EDGE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint OUTLINE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Paint SHADOW_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path SHADOW_CLIP_PATH = new Path();

    static {
        final float density = getDensity();

        EDGE_PAINT.setStyle(Paint.Style.STROKE);
        EDGE_PAINT.setStrokeWidth(EDGE_WIDTH_DP * density);
        OUTLINE_PAINT.setStyle(Paint.Style.STROKE);
        OUTLINE_PAINT.setStrokeWidth(EDGE_WIDTH_DP * density);

        final boolean solid = STRENGTH == GlassStrength.SOLID;
        SHADOW_PAINT.setShadowLayer(
                (solid ? SOLID_SHADOW_BLUR_DP : BALANCED_SHADOW_BLUR_DP) * density,
                0,
                SHADOW_OFFSET_DP * density,
                solid ? SOLID_SHADOW_COLOR : BALANCED_SHADOW_COLOR
        );
    }

    private GlassNavigationBarEdge() {
    }

    /**
     * @return Space around the search button for its shadow, in pixels.
     */
    static int getShadowMargin() {
        return STRENGTH == GlassStrength.CLEAR
                ? 0
                : (int) Math.ceil(SHADOW_MARGIN_DP * getDensity());
    }

    /**
     * Draws a shadow outside a shape, if the glass strength has one.
     */
    static void drawShadow(Canvas canvas, float left, float top, float width, float height,
                           float radius) {
        if (STRENGTH == GlassStrength.CLEAR) {
            return;
        }

        SHADOW_CLIP_PATH.rewind();
        SHADOW_CLIP_PATH.addRoundRect(left, top, left + width, top + height,
                radius, radius, Path.Direction.CW);

        final int saveCount = canvas.save();
        canvas.clipOutPath(SHADOW_CLIP_PATH);
        final float shadowRadius = Math.max(0, radius - SHADOW_INSET_PX);
        canvas.drawRoundRect(
                left + SHADOW_INSET_PX,
                top + SHADOW_INSET_PX,
                left + width - SHADOW_INSET_PX,
                top + height - SHADOW_INSET_PX,
                shadowRadius,
                shadowRadius,
                SHADOW_PAINT
        );
        canvas.restoreToCount(saveCount);
    }

    /**
     * Draws the edges of the capsule or the search button.
     */
    static void drawEdges(Canvas canvas, float left, float top, float width, float height,
                          float radius, boolean dark) {
        if (STRENGTH != GlassStrength.CLEAR) {
            drawOutline(canvas, left, top, width, height, radius, dark);
        } else if (FROSTED_ENABLED) {
            drawLightEdge(canvas, left, top, width, height, radius, dark);
        }
    }

    /**
     * Draws a light edge inside and a thin outline outside a shape,
     * if the glass strength has them.
     */
    static void drawOutline(Canvas canvas, float left, float top, float width, float height,
                            float radius, boolean dark) {
        if (STRENGTH == GlassStrength.CLEAR) {
            return;
        }

        drawLightEdge(canvas, left, top, width, height, radius, dark);

        final int color = STRENGTH == GlassStrength.SOLID
                ? (dark ? SOLID_OUTLINE_DARK : SOLID_OUTLINE_LIGHT)
                : (dark ? BALANCED_OUTLINE_DARK : BALANCED_OUTLINE_LIGHT);
        OUTLINE_PAINT.setColor(color);

        final float outset = OUTLINE_PAINT.getStrokeWidth() / 2;
        canvas.drawRoundRect(
                left - outset,
                top - outset,
                left + width + outset,
                top + height + outset,
                radius + outset,
                radius + outset,
                OUTLINE_PAINT
        );
    }

    /**
     * Draws a light edge that fades to the bottom of a shape.
     */
    private static void drawLightEdge(Canvas canvas, float left, float top, float width,
                                      float height, float radius, boolean dark) {
        EDGE_PAINT.setShader(new LinearGradient(
                0, top, 0, top + height,
                dark ? 0x40FFFFFF : 0xB3FFFFFF,
                dark ? 0x0DFFFFFF : 0x14000000,
                Shader.TileMode.CLAMP
        ));

        final float inset = EDGE_PAINT.getStrokeWidth() / 2;
        final float edgeRadius = Math.max(0, radius - inset);
        canvas.drawRoundRect(
                left + inset,
                top + inset,
                left + width - inset,
                top + height - inset,
                edgeRadius,
                edgeRadius,
                EDGE_PAINT
        );
    }
}
