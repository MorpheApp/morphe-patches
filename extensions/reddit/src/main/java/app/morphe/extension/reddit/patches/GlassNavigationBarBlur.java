/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5282
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.RequiresApi;

import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.ComposeCanvas;
import app.morphe.extension.reddit.patches.GlassNavigationBarPatch.ContentDrawScope;
import kotlin.jvm.functions.Function1;

/**
 * Frosted glass of the navigation bar. The page is recorded into a render node, which the bar
 * draws again blurred. The render nodes of the page are shared and not copied, so the blur
 * stays up to date if the page scrolls and the bar itself is not drawn again.
 * <p>
 * Used only on Android 14 and later: on Android 12 and 13 a blur render effect redrawn
 * every frame can crash the app.
 */
@RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
final class GlassNavigationBarBlur {

    /**
     * Draws the page and records it.
     */
    static final class ContentDraw implements Function1<ContentDrawScope, Object> {
        static final ContentDraw INSTANCE = new ContentDraw();

        private ContentDraw() {
        }

        @Override
        public Object invoke(ContentDrawScope scope) {
            ComposeCanvas composeCanvas = scope.patch_getCanvas();
            Canvas canvas = composeCanvas.patch_getNativeCanvas();
            final long size = scope.patch_getSize();
            final int width = (int) Math.ceil(GlassNavigationBarPatch.unpackFirst(size));
            final int height = (int) Math.ceil(GlassNavigationBarPatch.unpackSecond(size));

            if (!canvas.isHardwareAccelerated() || width <= 0 || height <= 0) {
                scope.patch_drawContent();
                return null;
            }

            PAGE_NODE.setPosition(0, 0, width, height);
            RecordingCanvas recording = PAGE_NODE.beginRecording(width, height);
            composeCanvas.patch_setNativeCanvas(recording);
            try {
                scope.patch_drawContent();
            } finally {
                composeCanvas.patch_setNativeCanvas(canvas);
                PAGE_NODE.endRecording();
            }

            canvas.drawRenderNode(PAGE_NODE);
            recordBackdrop();
            return null;
        }
    }

    private static final float MAX_BLUR_RADIUS_DP = 32;

    /**
     * A blur alone makes the colors dull and gray.
     */
    private static final float SATURATION = 1.4f;

    private static final float EDGE_WIDTH_DP = 1;

    private static final RenderNode PAGE_NODE = new RenderNode("morphe_glass_page");
    private static final RenderNode BLUR_NODE = new RenderNode("morphe_glass_blur");

    private static final Path CAPSULE_PATH = new Path();
    private static final Paint EDGE_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG);

    /**
     * Extra size on each side of the blurred area, so the page next to the capsule blurs into it.
     */
    private static final int BLUR_MARGIN;

    /**
     * Blurred area relative to the bottom left corner of the page.
     */
    private static int blurLeft, blurTopFromBottom, blurWidth, blurHeight;

    /**
     * Drawn below the page, because not all pages have their own background.
     */
    @ColorInt
    private static int backgroundColor;

    static {
        // Same as without the render node.
        PAGE_NODE.setClipToBounds(false);

        final float density = GlassNavigationBarPatch.getDensity();
        final float radius = GlassNavigationBarPatch.BLUR_PERCENT * MAX_BLUR_RADIUS_DP * density / 100;
        BLUR_MARGIN = (int) Math.ceil(radius);

        ColorMatrix colors = new ColorMatrix();
        colors.setSaturation(SATURATION);
        BLUR_NODE.setRenderEffect(RenderEffect.createChainEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colors))
        ));

        EDGE_PAINT.setStyle(Paint.Style.STROKE);
        EDGE_PAINT.setStrokeWidth(EDGE_WIDTH_DP * density);
    }

    private GlassNavigationBarBlur() {
    }

    /**
     * Draws the blurred page inside the capsule.
     *
     * @param scope Scope of the navigation bar, which is at the bottom of the page
     *              and has the same width.
     * @param color Background color of the page.
     */
    static void drawBackdrop(ContentDrawScope scope, float left, float top, float width, float height,
                             float cornerRadius, @ColorInt int color) {
        Canvas canvas = scope.patch_getCanvas().patch_getNativeCanvas();
        if (!canvas.isHardwareAccelerated()) {
            return;
        }

        final int capsuleLeft = (int) Math.floor(left);
        final int capsuleTop = (int) Math.floor(top);
        final float barHeight = GlassNavigationBarPatch.unpackSecond(scope.patch_getSize());

        blurLeft = capsuleLeft - BLUR_MARGIN;
        blurTopFromBottom = Math.round(barHeight) - capsuleTop - GlassNavigationBarPatch.getBarSlide() + BLUR_MARGIN;
        blurWidth = (int) Math.ceil(left + width) - capsuleLeft + 2 * BLUR_MARGIN;
        blurHeight = (int) Math.ceil(top + height) - capsuleTop + 2 * BLUR_MARGIN;
        backgroundColor = color;
        recordBackdrop();

        CAPSULE_PATH.rewind();
        CAPSULE_PATH.addRoundRect(left, top, left + width, top + height,
                cornerRadius, cornerRadius, Path.Direction.CW);

        final int saveCount = canvas.save();
        canvas.clipPath(CAPSULE_PATH);
        canvas.translate(blurLeft, capsuleTop - BLUR_MARGIN);
        canvas.drawRenderNode(BLUR_NODE);
        canvas.restoreToCount(saveCount);
    }

    /**
     * Records the area of the page that is behind the capsule.
     */
    private static void recordBackdrop() {
        if (blurWidth <= 0 || blurHeight <= 0) {
            return; // The bar is not drawn yet.
        }

        BLUR_NODE.setPosition(0, 0, blurWidth, blurHeight);
        RecordingCanvas recording = BLUR_NODE.beginRecording(blurWidth, blurHeight);
        try {
            recording.drawColor(backgroundColor);
            recording.translate(-blurLeft, blurTopFromBottom - PAGE_NODE.getHeight());
            recording.drawRenderNode(PAGE_NODE);
        } finally {
            BLUR_NODE.endRecording();
        }
    }

    /**
     * Draws a light edge that fades to the bottom of the capsule.
     */
    static void drawEdge(ContentDrawScope scope, float left, float top, float width, float height,
                         float cornerRadius, boolean dark) {
        EDGE_PAINT.setShader(new LinearGradient(
                0, top, 0, top + height,
                dark ? 0x40FFFFFF : 0xB3FFFFFF,
                dark ? 0x0DFFFFFF : 0x14000000,
                Shader.TileMode.CLAMP
        ));

        final float inset = EDGE_PAINT.getStrokeWidth() / 2;
        final float edgeRadius = Math.max(0, cornerRadius - inset);
        scope.patch_getCanvas().patch_getNativeCanvas().drawRoundRect(
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
