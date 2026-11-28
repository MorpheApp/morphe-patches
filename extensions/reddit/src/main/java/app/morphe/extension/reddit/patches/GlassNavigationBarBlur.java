/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5282
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.reddit.utils.ComposeUtils.getDensity;
import static app.morphe.extension.reddit.utils.ComposeUtils.unpackFirst;
import static app.morphe.extension.reddit.utils.ComposeUtils.unpackSecond;

import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Matrix;
import android.graphics.Path;
import android.graphics.RecordingCanvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;

import androidx.annotation.ColorInt;
import androidx.annotation.RequiresApi;

import app.morphe.extension.reddit.utils.ComposeUtils.ComposeCanvas;
import app.morphe.extension.reddit.utils.ComposeUtils.ContentDrawScope;
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
            if (drawContentInto(scope, PAGE_NODE)) {
                recordBackdrop();
            }
            return null;
        }
    }

    /**
     * Draws the Home feed and records it. The top bar is part of the page,
     * so its glass blurs this separate recording of the feed instead.
     */
    static final class FeedDraw implements Function1<ContentDrawScope, Object> {
        static final FeedDraw INSTANCE = new FeedDraw();

        private FeedDraw() {
        }

        @Override
        @SuppressWarnings("deprecation")
        public Object invoke(ContentDrawScope scope) {
            if (drawContentInto(scope, FEED_NODE)) {
                scope.patch_getCanvas().patch_getNativeCanvas().getMatrix(FEED_MATRIX);
                feedRecorded = true;
            }
            return null;
        }
    }

    /**
     * Records the blurred feed behind the Home top bar, which the glass of its buttons and tabs draws.
     * Must be drawn by the same parent as the feed, outside the graphics layer of the top bar.
     */
    static final class TopBarDraw implements Function1<ContentDrawScope, Object> {
        static final TopBarDraw INSTANCE = new TopBarDraw();

        private TopBarDraw() {
        }

        @Override
        @SuppressWarnings("deprecation")
        public Object invoke(ContentDrawScope scope) {
            Canvas canvas = scope.patch_getCanvas().patch_getNativeCanvas();
            final long size = scope.patch_getSize();
            topBarRecorded = feedRecorded && canvas.isHardwareAccelerated();

            if (topBarRecorded) {
                canvas.getMatrix(TOP_BAR_MATRIX);
                TOP_BAR_MATRIX.getValues(MATRIX_VALUES);
                final float topBarX = MATRIX_VALUES[Matrix.MTRANS_X];
                final float topBarY = MATRIX_VALUES[Matrix.MTRANS_Y];
                FEED_MATRIX.getValues(MATRIX_VALUES);

                recordNode(TOP_BAR_BLUR_NODE, FEED_NODE,
                        Math.round(topBarX - MATRIX_VALUES[Matrix.MTRANS_X]) - BLUR_MARGIN,
                        Math.round(topBarY - MATRIX_VALUES[Matrix.MTRANS_Y]) - BLUR_MARGIN,
                        (int) Math.ceil(unpackFirst(size)) + 2 * BLUR_MARGIN,
                        (int) Math.ceil(unpackSecond(size)) + 2 * BLUR_MARGIN,
                        GlassNavigationBarPatch.getPageColor());
            }

            scope.patch_drawContent();
            return null;
        }
    }

    private static final float MAX_BLUR_RADIUS_DP = 32;

    /**
     * A blur alone makes the colors dull and gray.
     */
    private static final float SATURATION = 1.4f;

    private static final RenderNode PAGE_NODE = new RenderNode("morphe_glass_page");
    private static final RenderNode FEED_NODE = new RenderNode("morphe_glass_feed");
    private static final RenderNode BLUR_NODE = new RenderNode("morphe_glass_blur");
    private static final RenderNode BUTTON_BLUR_NODE = new RenderNode("morphe_glass_button_blur");
    private static final RenderNode TOP_BAR_BLUR_NODE = new RenderNode("morphe_glass_top_bar_blur");

    private static final Path CAPSULE_PATH = new Path();
    private static final Path BUTTON_PATH = new Path();
    private static final Path TOP_BAR_PATH = new Path();
    /**
     * Positions of the feed and the top bar in their parent.
     */
    private static final Matrix FEED_MATRIX = new Matrix();
    private static final Matrix TOP_BAR_MATRIX = new Matrix();
    private static final Matrix SHAPE_MATRIX = new Matrix();
    private static final float[] MATRIX_VALUES = new float[9];

    /**
     * Extra size on each side of the blurred area, so the page next to the capsule blurs into it.
     */
    private static final int BLUR_MARGIN;

    /**
     * Blurred area relative to the bottom left corner of the page, if the bar is not moved.
     */
    private static int blurLeft, blurTopFromBottom, blurWidth, blurHeight;
    /**
     * Blurred area of the search button, relative to the bottom left corner of the page.
     */
    private static int buttonBlurLeft, buttonBlurTopFromBottom, buttonBlurSize;

    /**
     * How far down the bar is moved from the bottom of the page, in pixels.
     */
    private static int barOffset;

    /**
     * Drawn below the page, because not all pages have their own background.
     */
    @ColorInt
    private static int backgroundColor;

    private static boolean feedRecorded;
    private static boolean topBarRecorded;

    static {
        // Same as without the render node.
        PAGE_NODE.setClipToBounds(false);
        FEED_NODE.setClipToBounds(false);

        final float density = getDensity();
        final float radius = GlassNavigationBarPatch.BLUR_PERCENT * MAX_BLUR_RADIUS_DP * density / 100;
        BLUR_MARGIN = (int) Math.ceil(radius);

        ColorMatrix colors = new ColorMatrix();
        colors.setSaturation(SATURATION);
        RenderEffect effect = RenderEffect.createChainEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colors))
        );
        BLUR_NODE.setRenderEffect(effect);
        BUTTON_BLUR_NODE.setRenderEffect(effect);
        TOP_BAR_BLUR_NODE.setRenderEffect(effect);
    }

    private GlassNavigationBarBlur() {
    }

    /**
     * The bar is not drawn again if only its position changes.
     */
    static void setBarOffset(int offset) {
        if (barOffset != offset) {
            barOffset = offset;
            recordCapsuleBackdrop();
        }
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
        final float barHeight = unpackSecond(scope.patch_getSize());

        blurLeft = capsuleLeft - BLUR_MARGIN;
        blurTopFromBottom = Math.round(barHeight) - capsuleTop + BLUR_MARGIN;
        blurWidth = (int) Math.ceil(left + width) - capsuleLeft + 2 * BLUR_MARGIN;
        blurHeight = (int) Math.ceil(top + height) - capsuleTop + 2 * BLUR_MARGIN;
        backgroundColor = color;
        recordCapsuleBackdrop();

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
     * Draws the blurred feed inside the glass of a top bar button or the tabs.
     *
     * @param scope Scope inside the top bar, without its own graphics layer.
     */
    @SuppressWarnings("deprecation")
    static void drawTopBarBackdrop(ContentDrawScope scope, float left, float top,
                                   float width, float height, float cornerRadius) {
        Canvas canvas = scope.patch_getCanvas().patch_getNativeCanvas();
        if (!topBarRecorded || !canvas.isHardwareAccelerated()) {
            return;
        }

        // Position in the graphics layer of the top bar, which starts at the top bar.
        canvas.getMatrix(SHAPE_MATRIX);
        SHAPE_MATRIX.getValues(MATRIX_VALUES);
        final float x = MATRIX_VALUES[Matrix.MTRANS_X];
        final float y = MATRIX_VALUES[Matrix.MTRANS_Y];

        TOP_BAR_PATH.rewind();
        TOP_BAR_PATH.addRoundRect(left, top, left + width, top + height,
                cornerRadius, cornerRadius, Path.Direction.CW);

        final int saveCount = canvas.save();
        canvas.clipPath(TOP_BAR_PATH);
        canvas.translate(-x - BLUR_MARGIN, -y - BLUR_MARGIN);
        canvas.drawRenderNode(TOP_BAR_BLUR_NODE);
        canvas.restoreToCount(saveCount);
    }

    /**
     * Draws the blurred page inside the search button.
     *
     * @param canvas        Canvas of the button.
     * @param left          Left of the button in the page.
     * @param topFromBottom Top of the button, from the bottom of the page.
     * @param color         Background color of the page.
     */
    static void drawButtonBackdrop(Canvas canvas, int left, int topFromBottom, int size,
                                   @ColorInt int color) {
        if (!canvas.isHardwareAccelerated()) {
            return;
        }

        buttonBlurLeft = left - BLUR_MARGIN;
        buttonBlurTopFromBottom = topFromBottom + BLUR_MARGIN;
        buttonBlurSize = size + 2 * BLUR_MARGIN;
        backgroundColor = color;
        recordButtonBackdrop();

        final float radius = size / 2f;
        BUTTON_PATH.rewind();
        BUTTON_PATH.addCircle(radius, radius, radius, Path.Direction.CW);

        final int saveCount = canvas.save();
        canvas.clipPath(BUTTON_PATH);
        canvas.translate(-BLUR_MARGIN, -BLUR_MARGIN);
        canvas.drawRenderNode(BUTTON_BLUR_NODE);
        canvas.restoreToCount(saveCount);
    }

    /**
     * Draws the content of a scope into a render node, and the render node into the canvas.
     *
     * @return If the content was recorded.
     */
    private static boolean drawContentInto(ContentDrawScope scope, RenderNode node) {
        ComposeCanvas composeCanvas = scope.patch_getCanvas();
        Canvas canvas = composeCanvas.patch_getNativeCanvas();
        final long size = scope.patch_getSize();
        final int width = (int) Math.ceil(unpackFirst(size));
        final int height = (int) Math.ceil(unpackSecond(size));

        if (!canvas.isHardwareAccelerated() || width <= 0 || height <= 0) {
            scope.patch_drawContent();
            return false;
        }

        node.setPosition(0, 0, width, height);
        RecordingCanvas recording = node.beginRecording(width, height);
        composeCanvas.patch_setNativeCanvas(recording);
        try {
            scope.patch_drawContent();
        } finally {
            composeCanvas.patch_setNativeCanvas(canvas);
            node.endRecording();
        }

        canvas.drawRenderNode(node);
        return true;
    }

    /**
     * Records the areas of the page that are behind the capsule and the search button.
     */
    private static void recordBackdrop() {
        recordCapsuleBackdrop();
        recordButtonBackdrop();
    }

    private static void recordCapsuleBackdrop() {
        recordNode(BLUR_NODE, PAGE_NODE, blurLeft, PAGE_NODE.getHeight() - blurTopFromBottom + barOffset,
                blurWidth, blurHeight, backgroundColor);
    }

    private static void recordButtonBackdrop() {
        recordNode(BUTTON_BLUR_NODE, PAGE_NODE, buttonBlurLeft,
                PAGE_NODE.getHeight() - buttonBlurTopFromBottom, buttonBlurSize, buttonBlurSize,
                backgroundColor);
    }

    /**
     * Records an area of a source render node.
     */
    private static void recordNode(RenderNode target, RenderNode source, int left, int top,
                                   int width, int height, @ColorInt int color) {
        if (width <= 0 || height <= 0) {
            return; // Not drawn yet.
        }

        target.setPosition(0, 0, width, height);
        RecordingCanvas recording = target.beginRecording(width, height);
        try {
            recording.drawColor(color);
            recording.translate(-left, -top);
            recording.drawRenderNode(source);
        } finally {
            target.endRecording();
        }
    }
}
