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
import android.graphics.RectF;
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
            ComposeCanvas composeCanvas = scope.patch_getCanvas();
            Canvas canvas = composeCanvas.patch_getNativeCanvas();
            final long size = scope.patch_getSize();
            final int width = (int) Math.ceil(unpackFirst(size));
            final int height = (int) Math.ceil(unpackSecond(size));

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

    /**
     * Records the Home feed into its own render node and saves its position in the parent canvas.
     */
    static final class FeedDraw implements Function1<ContentDrawScope, Object> {
        static final FeedDraw INSTANCE = new FeedDraw();

        private FeedDraw() {
        }

        @Override
        @SuppressWarnings("deprecation")
        public Object invoke(ContentDrawScope scope) {
            ComposeCanvas composeCanvas = scope.patch_getCanvas();
            Canvas canvas = composeCanvas.patch_getNativeCanvas();
            final long size = scope.patch_getSize();
            final int width = (int) Math.ceil(unpackFirst(size));
            final int height = (int) Math.ceil(unpackSecond(size));

            if (!canvas.isHardwareAccelerated() || width <= 0 || height <= 0) {
                scope.patch_drawContent();
                return null;
            }

            FEED_NODE.setPosition(0, 0, width, height);
            RecordingCanvas recording = FEED_NODE.beginRecording(width, height);
            composeCanvas.patch_setNativeCanvas(recording);
            try {
                scope.patch_drawContent();
            } finally {
                composeCanvas.patch_setNativeCanvas(canvas);
                FEED_NODE.endRecording();
            }

            canvas.getMatrix(FEED_MATRIX);
            feedRecorded = true;
            canvas.drawRenderNode(FEED_NODE);
            return null;
        }
    }

    /**
     * Saves the Home top bar's position in the parent canvas.
     */
    static final class HeaderDraw implements Function1<ContentDrawScope, Object> {
        static final HeaderDraw INSTANCE = new HeaderDraw();

        private HeaderDraw() {
        }

        @Override
        @SuppressWarnings("deprecation")
        public Object invoke(ContentDrawScope scope) {
            scope.patch_getCanvas().patch_getNativeCanvas().getMatrix(HEADER_MATRIX);
            headerPositioned = true;
            scope.patch_drawContent();
            return null;
        }
    }

    private static final class TopBarSlot {
        final RenderNode node;
        int blurLeft;
        int blurTop;
        int blurWidth;
        int blurHeight;
        @ColorInt
        int backgroundColor;

        TopBarSlot() {
            this.node = new RenderNode("morphe_glass_top_bar_blur");
            this.node.setRenderEffect(BLUR_EFFECT);
        }

        boolean matches(int left, int top, int width, int height) {
            return blurLeft == left && blurTop == top
                    && blurWidth == width && blurHeight == height;
        }

        void record(int left, int top, int width, int height, @ColorInt int color) {
            blurLeft = left;
            blurTop = top;
            blurWidth = width;
            blurHeight = height;
            backgroundColor = color;
            recordNode(node, FEED_NODE, left, top, width, height, color);
        }
    }

    private static final float MAX_BLUR_RADIUS_DP = 32;

    /**
     * A blur alone makes the colors dull and gray.
     */
    private static final float SATURATION = 1.4f;
    private static final int MAX_TOP_BAR_SLOTS = 8;

    private static final RenderNode PAGE_NODE = new RenderNode("morphe_glass_page");
    private static final RenderNode FEED_NODE = new RenderNode("morphe_glass_feed");
    private static final RenderNode BLUR_NODE = new RenderNode("morphe_glass_blur");
    private static final RenderNode BUTTON_BLUR_NODE = new RenderNode("morphe_glass_button_blur");
    private static final RenderEffect BLUR_EFFECT;

    private static final Path CAPSULE_PATH = new Path();
    private static final Path BUTTON_PATH = new Path();
    private static final Path TOP_BAR_PATH = new Path();
    private static final Matrix FEED_MATRIX = new Matrix();
    private static final Matrix HEADER_MATRIX = new Matrix();
    private static final Matrix SHAPE_MATRIX = new Matrix();
    private static final float[] MATRIX_VALUES = new float[9];
    private static final RectF SHAPE_RECT = new RectF();
    private static final TopBarSlot[] TOP_BAR_SLOTS = new TopBarSlot[MAX_TOP_BAR_SLOTS];

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
    private static boolean headerPositioned;
    private static int topBarSlotCount;

    static {
        // Same as without the render node.
        PAGE_NODE.setClipToBounds(false);
        FEED_NODE.setClipToBounds(false);

        final float density = getDensity();
        final float radius = GlassNavigationBarPatch.BLUR_PERCENT * MAX_BLUR_RADIUS_DP * density / 100;
        BLUR_MARGIN = (int) Math.ceil(radius);

        ColorMatrix colors = new ColorMatrix();
        colors.setSaturation(SATURATION);
        BLUR_EFFECT = RenderEffect.createChainEffect(
                RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.CLAMP),
                RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(colors))
        );
        BLUR_NODE.setRenderEffect(BLUR_EFFECT);
        BUTTON_BLUR_NODE.setRenderEffect(BLUR_EFFECT);
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
     * Draws the blurred feed inside a top bar glass shape.
     */
    @SuppressWarnings("deprecation")
    static void drawTopBarBackdrop(ContentDrawScope scope, float left, float top,
                                   float width, float height, float radius) {
        if (!feedRecorded || !headerPositioned || width <= 0 || height <= 0) {
            return;
        }

        Canvas canvas = scope.patch_getCanvas().patch_getNativeCanvas();
        if (!canvas.isHardwareAccelerated()) {
            return;
        }

        HEADER_MATRIX.getValues(MATRIX_VALUES);
        final float headerX = MATRIX_VALUES[Matrix.MTRANS_X];
        final float headerY = MATRIX_VALUES[Matrix.MTRANS_Y];
        FEED_MATRIX.getValues(MATRIX_VALUES);
        final float feedX = MATRIX_VALUES[Matrix.MTRANS_X];
        final float feedY = MATRIX_VALUES[Matrix.MTRANS_Y];

        canvas.getMatrix(SHAPE_MATRIX);
        SHAPE_RECT.set(left, top, left + width, top + height);
        SHAPE_MATRIX.mapRect(SHAPE_RECT);
        SHAPE_RECT.offset(headerX - feedX, headerY - feedY);

        final int shapeLeft = (int) Math.floor(SHAPE_RECT.left);
        final int shapeTop = (int) Math.floor(SHAPE_RECT.top);
        final int blurLeft = shapeLeft - BLUR_MARGIN;
        final int blurTop = shapeTop - BLUR_MARGIN;
        final int blurWidth = (int) Math.ceil(SHAPE_RECT.right) - shapeLeft + 2 * BLUR_MARGIN;
        final int blurHeight = (int) Math.ceil(SHAPE_RECT.bottom) - shapeTop + 2 * BLUR_MARGIN;
        if (blurWidth <= 0 || blurHeight <= 0) {
            return;
        }

        TopBarSlot slot = getSlot(blurLeft, blurTop, blurWidth, blurHeight,
                GlassNavigationBarPatch.getPageColor());

        TOP_BAR_PATH.rewind();
        TOP_BAR_PATH.addRoundRect(left, top, left + width, top + height,
                radius, radius, Path.Direction.CW);

        final int saveCount = canvas.save();
        canvas.clipPath(TOP_BAR_PATH);
        canvas.translate(left + blurLeft - SHAPE_RECT.left, top + blurTop - SHAPE_RECT.top);
        canvas.drawRenderNode(slot.node);
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

    private static TopBarSlot getSlot(int left, int top, int width, int height, @ColorInt int color) {
        for (int i = 0; i < topBarSlotCount; i++) {
            TopBarSlot slot = TOP_BAR_SLOTS[i];
            if (slot.matches(left, top, width, height)) {
                if (i < topBarSlotCount - 1) {
                    System.arraycopy(TOP_BAR_SLOTS, i + 1, TOP_BAR_SLOTS, i, topBarSlotCount - 1 - i);
                    TOP_BAR_SLOTS[topBarSlotCount - 1] = slot;
                }
                if (slot.backgroundColor != color) {
                    slot.record(left, top, width, height, color);
                }
                return slot;
            }
        }

        final TopBarSlot slot;
        if (topBarSlotCount < MAX_TOP_BAR_SLOTS) {
            slot = new TopBarSlot();
            TOP_BAR_SLOTS[topBarSlotCount++] = slot;
        } else {
            slot = TOP_BAR_SLOTS[0];
            System.arraycopy(TOP_BAR_SLOTS, 1, TOP_BAR_SLOTS, 0, MAX_TOP_BAR_SLOTS - 1);
            TOP_BAR_SLOTS[MAX_TOP_BAR_SLOTS - 1] = slot;
        }
        slot.record(left, top, width, height, color);
        return slot;
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
