/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5527
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.animation.Interpolator;

import androidx.annotation.Nullable;

/**
 * Manages double-buffered outgoing bitmap snapshots for smooth line/gap/loading crossfades in
 * {@link PlayerLiveLyricsView}.
 */
final class PlayerLiveLyricsTransition {

    interface ContentRenderer {
        void render(Canvas targetCanvas, int viewportWidth, long captureNow,
                    float alphaScale, float offsetY);
    }

    private final Paint outgoingPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    @Nullable
    private Bitmap outgoingBitmap;
    @Nullable
    private Canvas outgoingCanvas;
    @Nullable
    private Bitmap swapBitmap;
    @Nullable
    private Canvas swapCanvas;
    private boolean hasOutgoing;

    boolean hasOutgoingSnapshot() {
        return hasOutgoing;
    }

    void clearOutgoingFlag() {
        hasOutgoing = false;
    }

    void release() {
        if (outgoingBitmap != null && !outgoingBitmap.isRecycled()) {
            outgoingBitmap.recycle();
        }
        outgoingBitmap = null;
        outgoingCanvas = null;
        if (swapBitmap != null && !swapBitmap.isRecycled()) {
            swapBitmap.recycle();
        }
        swapBitmap = null;
        swapCanvas = null;
        hasOutgoing = false;
    }

    void capture(int width, int height, long now, long lastCapturedNow,
                 long transitionStartMs, long fadeDurationMs,
                 float transitionSlidePx, Interpolator ease,
                 ContentRenderer renderer) {
        if (width <= 0 || height <= 0) {
            return;
        }
        if (!hasValidDimensions(outgoingBitmap, width, height)) {
            release();
            outgoingBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            outgoingCanvas = new Canvas(outgoingBitmap);
        }

        final long captureTime = lastCapturedNow > 0L ? lastCapturedNow : now;
        final boolean midTransition = hasOutgoing
                && transitionStartMs > 0L
                && (now - transitionStartMs) < fadeDurationMs;

        if (midTransition && swapCanvasOrAllocate(width, height) != null && outgoingBitmap != null) {
            swapBitmap.eraseColor(Color.TRANSPARENT);
            final float progress = PlayerLiveLyricsSupport.clamp01(
                    (now - transitionStartMs) / (float) fadeDurationMs);
            final float prevIn = ease.getInterpolation(progress);
            final float prevOut = 1f - prevIn;
            if (prevOut > 0.005f) {
                outgoingPaint.setAlpha(Math.round(prevOut * 255f));
                swapCanvas.drawBitmap(outgoingBitmap, 0f, -transitionSlidePx * 0.7f * prevIn, outgoingPaint);
            }
            renderer.render(swapCanvas, width, captureTime, prevIn,
                    (1f - prevIn) * transitionSlidePx);
            final Bitmap tmpBmp = outgoingBitmap;
            final Canvas tmpCanvas = outgoingCanvas;
            outgoingBitmap = swapBitmap;
            outgoingCanvas = swapCanvas;
            swapBitmap = tmpBmp;
            swapCanvas = tmpCanvas;
        } else if (outgoingBitmap != null && outgoingCanvas != null) {
            outgoingBitmap.eraseColor(Color.TRANSPARENT);
            renderer.render(outgoingCanvas, width, captureTime, 1f, 0f);
        }

        hasOutgoing = true;
    }

    void drawOutgoingIfActive(Canvas canvas, float inProgress, float revealAlpha, float transitionSlidePx) {
        final float outAlpha = (1f - PlayerLiveLyricsSupport.clamp01(inProgress))
                * PlayerLiveLyricsSupport.clamp01(revealAlpha);
        final float outOffsetY = -transitionSlidePx * 0.7f * PlayerLiveLyricsSupport.clamp01(inProgress);
        drawOutgoing(canvas, outAlpha, outOffsetY);
    }

    void drawOutgoing(Canvas canvas, float alpha, float offsetY) {
        if (!hasOutgoing || outgoingBitmap == null || outgoingBitmap.isRecycled()) {
            return;
        }
        final float clampedAlpha = PlayerLiveLyricsSupport.clamp01(alpha);
        if (clampedAlpha <= 0.005f) {
            return;
        }
        outgoingPaint.setAlpha(Math.round(clampedAlpha * 255f));
        canvas.drawBitmap(outgoingBitmap, 0f, offsetY, outgoingPaint);
    }

    @Nullable
    private Canvas swapCanvasOrAllocate(int width, int height) {
        if (!hasValidDimensions(swapBitmap, width, height)) {
            if (swapBitmap != null && !swapBitmap.isRecycled()) {
                swapBitmap.recycle();
            }
            swapBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            swapCanvas = new Canvas(swapBitmap);
        }
        return swapCanvas;
    }

    private static boolean hasValidDimensions(@Nullable Bitmap bmp, int width, int height) {
        return bmp != null && !bmp.isRecycled()
                && bmp.getWidth() == width && bmp.getHeight() == height;
    }
}
