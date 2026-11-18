/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4866
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.MaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.TintFilterCache;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WipeResult;
import app.morphe.extension.shared.Logger;

final class LyricsGlyphMaskCache {

    private LyricsGlyphMaskCache() {
    }

    static final float MASK_RASTER_SCALE = 1.25f;
    static final float GLOW_RASTER_SCALE = 0.75f;
    /** Masks are kept within the texture size every device can draw. */
    static final int MAX_MASK_SIDE_PX = 2048;
    /** Time each frame may spend rasterizing masks ahead of the words reaching them. */
    static final long PREWARM_BUDGET_NS = 1_500_000L;
    private static final PorterDuffXfermode SCREEN_MODE = new PorterDuffXfermode(PorterDuff.Mode.SCREEN);

    static final class ClusterMaskCache {
        public final float padPx;
        private final Bitmap[] bitmaps;
        private final float[] scales;
        private final Bitmap[] glowBitmaps;
        private final float[] glowScales;
        private int prewarmCursor = 0;
        private boolean hasBitmaps = false;

        public final TintFilterCache pastTint = new TintFilterCache();
        public final TintFilterCache unlitTint = new TintFilterCache();
        public final TintFilterCache inactiveTint = new TintFilterCache();
        public final ColorFilter glowFilter = new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);

        public final Paint layerPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF layerBounds = new RectF();

        private final int[] bandColors = new int[4];
        private int bandLit = Color.TRANSPARENT;
        private int bandUnlit = Color.TRANSPARENT;
        private float bandDx = Float.NaN;
        @Nullable
        private LinearGradient bandBrushCached = null;

        public ClusterMaskCache(int count, float padPx) {
            this.padPx = padPx;
            this.bitmaps = new Bitmap[count];
            this.scales = new float[count];
            this.glowBitmaps = new Bitmap[count];
            this.glowScales = new float[count];
            for (int i = 0; i < count; i++) {
                this.scales[i] = MASK_RASTER_SCALE;
                this.glowScales[i] = GLOW_RASTER_SCALE;
            }
            this.bandPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        }

        public float scaleOf(int idx) {
            return (idx >= 0 && idx < scales.length) ? scales[idx] : MASK_RASTER_SCALE;
        }

        public boolean hasGlow(int idx) {
            return idx >= 0 && idx < glowBitmaps.length && glowBitmaps[idx] != null;
        }

        public float glowScaleOf(int idx) {
            return (idx >= 0 && idx < glowScales.length) ? glowScales[idx] : GLOW_RASTER_SCALE;
        }

        @Nullable
        public Bitmap obtain(int idx, Layout layout) {
            Bitmap existing = bitmaps[idx];
            if (existing != null && !existing.isRecycled()) return existing;
            RasterResult res = rasterize(layout, padPx, MASK_RASTER_SCALE);
            if (res == null) return null;
            bitmaps[idx] = res.bitmap;
            scales[idx] = res.scale;
            hasBitmaps = true;
            return res.bitmap;
        }

        @Nullable
        public Bitmap obtainGlow(int idx, Layout layout, float blurRadius) {
            Bitmap existing = glowBitmaps[idx];
            if (existing != null && !existing.isRecycled()) return existing;
            RasterResult res = rasterizeGlow(layout, padPx, blurRadius, GLOW_RASTER_SCALE);
            if (res == null) return null;
            glowBitmaps[idx] = res.bitmap;
            glowScales[idx] = res.scale;
            hasBitmaps = true;
            return res.bitmap;
        }

        public interface LayoutProvider {
            Layout get(int index);
        }

        public interface GlowBlurProvider {
            float get(int index);
        }

        public void prewarmStep(
                int count,
                long budgetNs,
                LayoutProvider layoutOf,
                GlowBlurProvider glowBlurOf) {
            int limit = Math.min(count, bitmaps.length);
            if (prewarmCursor >= limit) return;
            long startNs = SystemClock.elapsedRealtimeNanos();
            while (prewarmCursor < limit) {
                int i = prewarmCursor;
                Layout layout = layoutOf.get(i);
                if (obtain(i, layout) == null) {
                    break;
                }
                float blur = glowBlurOf.get(i);
                if (blur > 0f && !hasGlow(i)) {
                    obtainGlow(i, layout, blur);
                }
                prewarmCursor++;
                if (SystemClock.elapsedRealtimeNanos() - startNs >= budgetNs) {
                    break;
                }
            }
        }

        public void evictAndClear() {
            if (!hasBitmaps && prewarmCursor == 0) {
                return;
            }
            for (int i = 0; i < bitmaps.length; i++) {
                if (bitmaps[i] != null && !bitmaps[i].isRecycled()) {
                    bitmaps[i].recycle();
                }
                bitmaps[i] = null;
                scales[i] = MASK_RASTER_SCALE;
                if (glowBitmaps[i] != null && !glowBitmaps[i].isRecycled()) {
                    glowBitmaps[i].recycle();
                }
                glowBitmaps[i] = null;
                glowScales[i] = GLOW_RASTER_SCALE;
            }
            prewarmCursor = 0;
            hasBitmaps = false;
        }

        public LinearGradient bandBrush(int lit, int unlit, float dx) {
            LinearGradient cached = bandBrushCached;
            if (cached != null && lit == bandLit && unlit == bandUnlit && dx == bandDx) {
                return cached;
            }
            bandLit = lit;
            bandUnlit = unlit;
            bandDx = dx;
            bandColors[0] = lit;
            bandColors[1] = lit;
            bandColors[2] = unlit;
            bandColors[3] = unlit;
            float safeDx = (Math.abs(dx) < 0.01f) ? 0.01f : dx;
            LinearGradient created = new LinearGradient(
                    0f, 0f, safeDx, 0f,
                    bandColors,
                    WordAnimationUtils.GRADIENT_STOPS,
                    Shader.TileMode.CLAMP);
            bandBrushCached = created;
            return created;
        }

        public void drawMaskTinted(
                Canvas canvas,
                Bitmap mask,
                float maskScale,
                ColorFilter filter,
                float alpha,
                boolean screenBlend) {
            maskPaint.setColor(Color.WHITE);
            maskPaint.setColorFilter(filter);
            maskPaint.setAlpha(Math.max(0, Math.min(255, Math.round(alpha * 255f))));
            maskPaint.setXfermode(screenBlend ? SCREEN_MODE : null);

            float inv = 1f / maskScale;
            float padM = padPx * maskScale;
            canvas.save();
            canvas.scale(inv, inv);
            canvas.translate(-padM, -padM);
            canvas.drawBitmap(mask, 0f, 0f, maskPaint);
            canvas.restore();

            maskPaint.setXfermode(null);
            maskPaint.setAlpha(255);
        }

        public void drawMaskBand(
                Canvas canvas,
                Bitmap mask,
                float maskScale,
                WipeResult wipe,
                int litColor,
                int unlitColor) {
            float inv = 1f / maskScale;
            float padM = padPx * maskScale;
            float w = mask.getWidth();
            float h = mask.getHeight();
            float bandX = wipe.bandStart * maskScale + padM;
            float dx = (wipe.bandEnd - wipe.bandStart) * maskScale;

            LinearGradient shader = bandBrush(litColor, unlitColor, dx);
            bandPaint.setShader(shader);

            canvas.save();
            canvas.scale(inv, inv);
            canvas.translate(-padM, -padM);

            layerBounds.set(0f, 0f, w, h);
            int saveCount = canvas.saveLayer(layerBounds, layerPaint);
            maskPaint.setColor(Color.WHITE);
            maskPaint.setColorFilter(null);
            maskPaint.setAlpha(255);
            maskPaint.setXfermode(null);
            canvas.drawBitmap(mask, 0f, 0f, maskPaint);

            canvas.save();
            canvas.translate(bandX, 0f);
            canvas.drawRect(-bandX, 0f, w - bandX, h, bandPaint);
            canvas.restore();

            canvas.restoreToCount(saveCount);
            canvas.restore();
        }
    }

    public record RasterResult(Bitmap bitmap, float scale) {
    }

    @Nullable
    private static RasterResult allocateMaskBitmap(Layout subLayout, float padPx, float scale) {
        float lw = Math.max(1f, subLayout.getWidth() + padPx * 2f);
        float lh = Math.max(1f, subLayout.getHeight() + padPx * 2f);
        float s = Math.min(scale, MAX_MASK_SIDE_PX / Math.max(lw, lh));
        int w = Math.max(1, Math.min(MAX_MASK_SIDE_PX, (int) Math.ceil(lw * s)));
        int h = Math.max(1, Math.min(MAX_MASK_SIDE_PX, (int) Math.ceil(lh * s)));
        Bitmap bmp = createAlphaMask(w, h);
        return bmp != null ? new RasterResult(bmp, s) : null;
    }

    /**
     * {@return a new single-channel mask, a quarter of the memory of a color bitmap, or null if
     * there is no memory for it and the text is drawn without a mask}
     */
    @Nullable
    static Bitmap createAlphaMask(int width, int height) {
        try {
            return Bitmap.createBitmap(width, height, Bitmap.Config.ALPHA_8);
        } catch (OutOfMemoryError error) {
            Logger.printDebug(() -> "Could not allocate " + width + "x" + height + " lyrics mask: " + error);
            return null;
        }
    }

    @Nullable
    public static RasterResult rasterize(Layout subLayout, float padPx, float scale) {
        RasterResult allocated = allocateMaskBitmap(subLayout, padPx, scale);
        if (allocated == null) {
            return null;
        }
        Canvas c = new Canvas(allocated.bitmap);
        c.scale(allocated.scale, allocated.scale);
        c.translate(padPx, padPx);

        TextPaint paint = subLayout.getPaint();
        int prevColor = paint.getColor();
        Shader prevShader = paint.getShader();
        paint.setColor(Color.WHITE);
        paint.setShader(null);
        paint.clearShadowLayer();
        subLayout.draw(c);
        paint.setColor(prevColor);
        paint.setShader(prevShader);

        return allocated;
    }

    @Nullable
    public static RasterResult rasterizeGlow(Layout subLayout, float padPx, float blurRadius, float scale) {
        RasterResult allocated = allocateMaskBitmap(subLayout, padPx, scale);
        if (allocated == null) {
            return null;
        }
        Canvas c = new Canvas(allocated.bitmap);
        c.scale(allocated.scale, allocated.scale);
        c.translate(padPx, padPx);

        TextPaint paint = subLayout.getPaint();
        int prevColor = paint.getColor();
        Shader prevShader = paint.getShader();
        MaskFilter prevMask = paint.getMaskFilter();
        paint.setColor(Color.WHITE);
        paint.setShader(null);
        paint.clearShadowLayer();

        // Outer soft halo + inner solid bloom (blurRadius = 0.20f * fontSizePx).
        float effectiveBlur = Math.max(0.5f, blurRadius);
        paint.setMaskFilter(new BlurMaskFilter(effectiveBlur, BlurMaskFilter.Blur.NORMAL));
        subLayout.draw(c);
        paint.setMaskFilter(new BlurMaskFilter(Math.max(0.5f, effectiveBlur * 0.55f), BlurMaskFilter.Blur.SOLID));
        paint.setColor(Color.argb(190, 255, 255, 255));
        subLayout.draw(c);

        paint.setMaskFilter(prevMask);
        paint.setColor(prevColor);
        paint.setShader(prevShader);

        return allocated;
    }
}