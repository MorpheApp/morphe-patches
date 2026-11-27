/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import app.morphe.extension.music.patches.lyrics.derive.CoverStyleConfig;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

final class CoverStyleDrawable extends Drawable {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Path circle = new Path();
    private final Path clip = new Path();
    private CoverStyleConfig config = CoverStyleConfig.defaults(CoverStyleConfig.DEFAULT_STYLE);
    private int backgroundColor = 0xFF141418;
    private Bitmap artwork;
    private float rotation;
    private float shaderLeft = Float.NaN;
    private float shaderTop = Float.NaN;
    private float shaderSize = -1f;
    private Shader vinylDiscShader;
    private Shader vinylSheenShader;
    private Shader cdDiscShader;
    private Shader cdSweepShader;
    private Shader cassetteShellShader;

    void setConfig(CoverStyleConfig value) {
        config = value == null ? CoverStyleConfig.defaults(CoverStyleConfig.DEFAULT_STYLE) : value;
        invalidateShaders();
        invalidateSelf();
    }

    void setBackground(int color) {
        backgroundColor = color;
        invalidateShaders();
        invalidateSelf();
    }

    void setArtwork(@Nullable Bitmap bitmap) {
        artwork = bitmap;
        invalidateShaders();
        invalidateSelf();
    }

    void setRotation(float degrees) {
        rotation = degrees;
        invalidateSelf();
    }

    boolean spins() {
        return config.spinSpeed() > 0f
                && (config.style().equals("vinyl") || config.style().equals("cd")
                        || config.style().equals("cassette")
                        || config.style().equals("phonograph"));
    }

    long spinDurationMs() {
        return (long) (9000f / Math.max(0.1f, config.spinSpeed()));
    }

    @Override
    public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        if (size <= 0f) {
            return;
        }
        float left = bounds.left + (bounds.width() - size) / 2f;
        float top = bounds.top + (bounds.height() - size) / 2f;
        ensureShaders(left, top, size);
        switch (config.style()) {
            case "film" -> drawFilm(canvas, left, top, size);
            case "vinyl" -> drawVinyl(canvas, left, top, size);
            case "polaroid" -> drawPolaroid(canvas, left, top, size);
            case "cassette" -> drawCassette(canvas, left, top, size);
            case "cd" -> drawCd(canvas, left, top, size);
            case "poster", "framed" -> drawFramed(canvas, left, top, size, config.style().equals("poster"));
            case "phonograph" -> drawPhonograph(canvas, left, top, size);
            case "ipod" -> drawIpod(canvas, left, top, size);
            case "fisheye" -> drawFisheye(canvas, left, top, size);
            case "recordbox" -> drawRecordBox(canvas, left, top, size);
            case "emboss" -> drawEmboss(canvas, left, top, size);
            case "projector" -> drawProjector(canvas, left, top, size);
            default -> drawPlain(canvas, left, top, size);
        }
    }

    private void drawPlain(Canvas canvas, float left, float top, float size) {
        float radius = Dim.dp(config.cornerRadiusDp());
        RectF dst = new RectF(left, top, left + size, top + size);
        drawArtwork(canvas, dst, false);
        paint.setShader(null);
        if (config.borderWidthDp() > 0) {
            float width = Dim.dp(config.borderWidthDp());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
            paint.setColor(CoverStyleConfig.color(config.frameColor(), 0xFF1A1A1E));
            float inset = width / 2f;
            canvas.drawRoundRect(new RectF(dst.left + inset, dst.top + inset,
                    dst.right - inset, dst.bottom - inset), radius, radius, paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x24));
        canvas.drawRoundRect(new RectF(dst.left + Dim.dp(1), dst.top + Dim.dp(1),
                dst.right - Dim.dp(1), dst.bottom - Dim.dp(1)), radius, radius, paint);
    }

    private void drawFilm(Canvas canvas, float left, float top, float size) {
        int tone = CoverStyleConfig.color(config.filmTone(), 0xFF1A1A1E);
        float radius = Dim.dp(Math.max(3, config.cornerRadiusDp()));
        RectF base = new RectF(left, top, left + size, top + size);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left, top, left + size, top + size,
                lighten(tone, 0.10f), darken(tone, 0.20f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(base, radius, radius, paint);
        paint.setShader(null);

        float edge = Math.max(Dim.dp(8), size * 0.05f);
        float holeRadius = Dim.dp(Math.max(1, config.perforationRadiusDp())) * 1.15f;
        int count = config.perforationCount();
        float holeTop = top + edge * 0.5f;
        float holeBottom = top + size - edge * 0.5f;
        int holeColor = withAlpha(backgroundColor, 0xE6);
        float holeWidth = holeRadius * 1.15f;
        for (int i = 0; i < count; i++) {
            float cx = left + size * (i + 0.5f) / count;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(holeColor);
            canvas.drawRoundRect(cx - holeWidth, holeTop - holeRadius,
                    cx + holeWidth, holeTop + holeRadius, holeRadius, holeRadius, paint);
            canvas.drawRoundRect(cx - holeWidth, holeBottom - holeRadius,
                    cx + holeWidth, holeBottom + holeRadius, holeRadius, holeRadius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, Dim.dp(1) * 0.5f));
            paint.setColor(withAlpha(0xFFFFFFFF, 0x24));
            canvas.drawRoundRect(cx - holeWidth, holeTop - holeRadius,
                    cx + holeWidth, holeTop + holeRadius, holeRadius, holeRadius, paint);
            canvas.drawRoundRect(cx - holeWidth, holeBottom - holeRadius,
                    cx + holeWidth, holeBottom + holeRadius, holeRadius, holeRadius, paint);
        }

        RectF window = new RectF(left + edge, top + edge, left + size - edge, top + size - edge);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(darken(tone, 0.45f));
        canvas.drawRoundRect(new RectF(window.left - Dim.dp(2), window.top - Dim.dp(2),
                window.right + Dim.dp(2), window.bottom + Dim.dp(2)),
                radius, radius, paint);
        drawArtwork(canvas, window, false, Math.max(1, config.cornerRadiusDp() - 2));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x30));
        canvas.drawRoundRect(window, radius, radius, paint);
    }

    private void drawVinyl(Canvas canvas, float left, float top, float size) {
        float cx = left + size / 2f;
        float cy = top + size / 2f;
        float radius = size * 0.48f;
        int disc = resolveDiscColor(config.discColor(), 0xFF141418, 0.45f);
        int accent = CoverStyleConfig.color(config.accentColor(), 0xFFFFFFFF);
        int label = CoverStyleConfig.color(config.labelColor(), 0xFFC6424E);

        canvas.save();
        canvas.rotate(rotation, cx, cy);

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55000000);
        canvas.drawCircle(cx + radius * 0.02f, cy + radius * 0.04f, radius, paint);

        if (vinylDiscShader == null) {
            vinylDiscShader = new RadialGradient(cx, cy, radius,
                    new int[]{darken(disc, 0.10f), disc, darken(disc, 0.32f)},
                    new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP);
        }
        paint.setShader(vinylDiscShader);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        float grooveStep = Math.max(2f, Dim.dp(2));
        int groove = 0;
        for (float r = radius * 0.38f; r < radius * 0.98f; r += grooveStep) {
            paint.setStrokeWidth(groove % 3 == 0 ? Math.max(1f, Dim.dp(1))
                    : Math.max(0.5f, Dim.dp(1) * 0.5f));
            paint.setColor(groove % 2 == 0
                    ? withAlpha(0xFFFFFFFF, 0x14) : withAlpha(0xFF000000, 0x24));
            canvas.drawCircle(cx, cy, r, paint);
            groove++;
        }

        paint.setStyle(Paint.Style.FILL);
        if (vinylSheenShader == null) {
            vinylSheenShader = new RadialGradient(cx - radius * 0.38f, cy - radius * 0.42f,
                    radius * 1.5f,
                    new int[]{0x3AFFFFFF, 0x0FFFFFFF, 0x1A000000},
                    new float[]{0f, 0.5f, 1f}, Shader.TileMode.CLAMP);
        }
        paint.setShader(vinylSheenShader);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x24));
        canvas.drawCircle(cx, cy, radius - Dim.dp(1), paint);

        float labelRadius = radius * 0.35f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawCircle(cx, cy + Dim.dp(1), labelRadius, paint);
        paint.setColor(label);
        canvas.drawCircle(cx, cy, labelRadius, paint);

        if (hasArtwork()) {
            drawArtwork(canvas, new RectF(cx - labelRadius, cy - labelRadius,
                    cx + labelRadius, cy + labelRadius), true);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(accent, 0x66));
        canvas.drawCircle(cx, cy, labelRadius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF000000, 0x88));
        canvas.drawCircle(cx, cy, Math.max(2f, Dim.dp(4)), paint);
        paint.setColor(withAlpha(0xFFFFFFFF, 0x55));
        canvas.drawCircle(cx, cy - Dim.dp(1), Math.max(1f, Dim.dp(2)), paint);

        canvas.restore();
    }

    private void drawPolaroid(Canvas canvas, float left, float top, float size) {
        float centerX = left + size / 2f;
        float centerY = top + size / 2f;
        float margin = size * 0.015f;
        canvas.save();
        canvas.rotate(config.tiltDegrees(), centerX, centerY);

        RectF paper = new RectF(left + margin, top + margin,
                left + size - margin, top + size - margin);
        int paperColor = CoverStyleConfig.color(config.paperColor(), 0xFFFFF6E3);
        float radius = Dim.dp(Math.max(1, config.cornerRadiusDp() / 3));

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(paper.left, paper.top, paper.right, paper.bottom,
                lighten(paperColor, 0.10f), darken(paperColor, 0.06f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(paper, radius, radius, paint);
        paint.setShader(null);

        float side = Math.max(Dim.dp(5), size * 0.025f);
        float bottom = Math.max(Dim.dp(12), size * 0.06f);
        RectF photo = new RectF(paper.left + side, paper.top + side,
                paper.right - side, paper.bottom - bottom);
        paint.setColor(withAlpha(0xFF000000, 0x33));
        canvas.drawRect(photo.left + Dim.dp(1), photo.top + Dim.dp(1),
                photo.right + Dim.dp(1), photo.bottom + Dim.dp(1), paint);
        drawArtwork(canvas, photo, false, 1);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFF000000, 0x22));
        canvas.drawRect(photo, paint);

        float tapeWidth = paper.width() * 0.30f;
        float tapeHeight = Math.max(Dim.dp(6), size * 0.045f);
        float tapeCenterY = paper.top + tapeHeight / 2f;
        canvas.save();
        canvas.rotate(-4f, centerX, paper.top);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFFFFFDF2, 0x66));
        canvas.drawRoundRect(centerX - tapeWidth / 2f, tapeCenterY - tapeHeight / 2f,
                centerX + tapeWidth / 2f, tapeCenterY + tapeHeight / 2f,
                Dim.dp(1), Dim.dp(1), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(withAlpha(0xFFFFFFFF, 0x33));
        canvas.drawRoundRect(centerX - tapeWidth / 2f, tapeCenterY - tapeHeight / 2f,
                centerX + tapeWidth / 2f, tapeCenterY + tapeHeight / 2f,
                Dim.dp(1), Dim.dp(1), paint);
        canvas.restore();

        canvas.restore();
    }

    private void drawCassette(Canvas canvas, float left, float top, float size) {
        int shell = CoverStyleConfig.color(config.shellColor(), 0xFF1A1A1E);
        int label = CoverStyleConfig.color(config.labelColor(), 0xFFFFF6E3);
        int accent = CoverStyleConfig.color(config.accentColor(), 0xFFFFFFFF);
        float radius = Dim.dp(Math.max(6, config.cornerRadiusDp()));
        RectF body = new RectF(left, top, left + size, top + size);

        paint.setStyle(Paint.Style.FILL);
        if (cassetteShellShader == null) {
            cassetteShellShader = new LinearGradient(left, top, left, top + size,
                    lighten(shell, 0.14f), darken(shell, 0.24f), Shader.TileMode.CLAMP);
        }
        paint.setShader(cassetteShellShader);
        canvas.drawRoundRect(body, radius, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x1E));
        canvas.drawRoundRect(new RectF(left + Dim.dp(1), top + Dim.dp(1),
                left + size - Dim.dp(1), top + size - Dim.dp(1)), radius, radius, paint);

        RectF labelRect = new RectF(left + size * 0.08f, top + size * 0.07f,
                left + size * 0.92f, top + size * 0.63f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x33000000);
        canvas.drawRoundRect(new RectF(labelRect.left + Dim.dp(1), labelRect.top + Dim.dp(1),
                labelRect.right + Dim.dp(1), labelRect.bottom + Dim.dp(1)),
                Dim.dp(2), Dim.dp(2), paint);
        paint.setColor(label);
        canvas.drawRoundRect(labelRect, Dim.dp(2), Dim.dp(2), paint);

        if (hasArtwork()) {
            drawArtwork(canvas, new RectF(labelRect.left + size * 0.02f,
                    labelRect.top + size * 0.02f,
                    labelRect.right - size * 0.02f,
                    labelRect.bottom - size * 0.02f), false, 1);
        }

        RectF window = new RectF(left + size * 0.12f, top + size * 0.68f,
                left + size * 0.88f, top + size * 0.89f);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(darken(shell, 0.55f));
        canvas.drawRoundRect(window, size * 0.04f, size * 0.04f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x22));
        canvas.drawRoundRect(window, size * 0.04f, size * 0.04f, paint);

        float reelY = (window.top + window.bottom) / 2f;
        float reelRadius = size * 0.075f;
        float reelLeft = left + size * 0.33f;
        float reelRight = left + size * 0.67f;
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2f, size * 0.014f));
        paint.setColor(withAlpha(0xFF000000, 0x66));
        canvas.drawLine(reelLeft + reelRadius, reelY, reelRight - reelRadius, reelY, paint);

        drawReel(canvas, reelLeft, reelY, reelRadius, accent, shell);
        drawReel(canvas, reelRight, reelY, reelRadius, accent, shell);

        float screw = Math.max(1.5f, size * 0.012f);
        for (int i = 0; i < 4; i++) {
            float sx = (i % 2 == 0) ? left + size * 0.055f : left + size * 0.945f;
            float sy = (i < 2) ? top + size * 0.055f : top + size * 0.945f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(withAlpha(0xFF000000, 0x55));
            canvas.drawCircle(sx, sy, screw, paint);
            paint.setColor(withAlpha(0xFFFFFFFF, 0x33));
            canvas.drawCircle(sx - screw * 0.3f, sy - screw * 0.3f, screw * 0.45f, paint);
        }
    }

    private void drawReel(Canvas canvas, float cx, float cy, float radius, int accent, int shell) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF000000, 0x77));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, radius * 0.18f));
        paint.setColor(withAlpha(accent, 0xAA));
        canvas.drawCircle(cx, cy, radius, paint);

        canvas.save();
        canvas.rotate(rotation, cx, cy);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, radius * 0.16f));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x55));
        int teeth = 6;
        for (int i = 0; i < teeth; i++) {
            double angle = Math.PI * 2 * i / teeth;
            float x1 = cx + (float) Math.cos(angle) * radius * 0.35f;
            float y1 = cy + (float) Math.sin(angle) * radius * 0.35f;
            float x2 = cx + (float) Math.cos(angle) * radius * 0.72f;
            float y2 = cy + (float) Math.sin(angle) * radius * 0.72f;
            canvas.drawLine(x1, y1, x2, y2, paint);
        }
        canvas.restore();

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(shell);
        canvas.drawCircle(cx, cy, radius * 0.34f, paint);
        paint.setColor(withAlpha(accent, 0xDD));
        canvas.drawCircle(cx, cy, radius * 0.18f, paint);
    }

    private void drawCd(Canvas canvas, float left, float top, float size) {
        float cx = left + size / 2f;
        float cy = top + size / 2f;
        float radius = size * 0.48f;
        int disc = resolveDiscColor(config.discColor(), 0xFF4A5568, 0.35f);
        int accent = CoverStyleConfig.color(config.accentColor(), 0xFF6BA8E8);
        int label = CoverStyleConfig.color(config.labelColor(), 0xFFFFF6E3);

        canvas.save();
        canvas.rotate(rotation, cx, cy);

        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x55000000);
        canvas.drawCircle(cx + radius * 0.02f, cy + radius * 0.04f, radius, paint);

        if (cdDiscShader == null) {
            cdDiscShader = new RadialGradient(cx, cy, radius,
                    new int[]{lighten(disc, 0.30f), disc, darken(disc, 0.35f)},
                    new float[]{0f, 0.62f, 1f}, Shader.TileMode.CLAMP);
        }
        paint.setShader(cdDiscShader);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        if (cdSweepShader == null) {
            cdSweepShader = new SweepGradient(cx, cy,
                    new int[]{0x00FFFFFF, withAlpha(accent, 0x55), 0x00FFFFFF,
                            withAlpha(0xFF8A6BC6, 0x44), 0x00FFFFFF,
                            withAlpha(0xFF74C69D, 0x44), 0x00FFFFFF},
                    new float[]{0f, 0.12f, 0.28f, 0.46f, 0.62f, 0.82f, 1f});
        }
        paint.setShader(cdSweepShader);
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        for (int i = 0; i < 3; i++) {
            paint.setStrokeWidth(Math.max(1f, Dim.dp(1) * (0.5f + i * 0.25f)));
            paint.setColor(withAlpha(0xFFFFFFFF, 0x12 + i * 0x08));
            canvas.drawCircle(cx, cy, radius * (0.50f + i * 0.13f), paint);
        }

        paint.setStrokeWidth(Math.max(1f, radius * 0.06f));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x28));
        canvas.drawArc(new RectF(cx - radius * 0.9f, cy - radius * 0.9f,
                cx + radius * 0.9f, cy + radius * 0.9f), -160f, 70f, false, paint);

        float labelRadius = radius * 0.34f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0x44000000);
        canvas.drawCircle(cx, cy + Dim.dp(1), labelRadius, paint);
        paint.setColor(label);
        canvas.drawCircle(cx, cy, labelRadius, paint);

        if (hasArtwork()) {
            drawArtwork(canvas, new RectF(cx - labelRadius, cy - labelRadius,
                    cx + labelRadius, cy + labelRadius), true);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(accent, 0x88));
        canvas.drawCircle(cx, cy, labelRadius, paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(withAlpha(0xFF000000, 0x99));
        canvas.drawCircle(cx, cy, radius * 0.11f, paint);
        paint.setColor(withAlpha(0xFFFFFFFF, 0x33));
        canvas.drawCircle(cx, cy, radius * 0.075f, paint);
        paint.setColor(darken(disc, 0.60f));
        canvas.drawCircle(cx, cy, radius * 0.045f, paint);

        canvas.restore();
    }

    private void drawPhonograph(Canvas canvas, float left, float top, float size) {
        float cx = left + size / 2f;
        float cy = top + size / 2f;
        float radius = Dim.dp(Math.max(4, config.cornerRadiusDp()));
        int wood = CoverStyleConfig.color(config.shellColor(), 0xFF5A3A22);
        int disc = resolveDiscColor(config.discColor(), 0xFF1A1A1E, 0.45f);
        int accent = CoverStyleConfig.color(config.accentColor(), 0xFFC9A227);
        int label = CoverStyleConfig.color(config.labelColor(), 0xFFFFF6E3);
        RectF base = new RectF(left, top, left + size, top + size);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left, top, left + size, top + size,
                lighten(wood, 0.15f), darken(wood, 0.35f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(base, radius, radius, paint);
        paint.setShader(null);

        float platterR = size * 0.40f;
        paint.setColor(0x55000000);
        canvas.drawCircle(cx + Dim.dp(2), cy + Dim.dp(3), platterR, paint);

        canvas.save();
        canvas.rotate(rotation, cx, cy);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new RadialGradient(cx, cy, platterR,
                new int[]{darken(disc, 0.10f), disc, darken(disc, 0.40f)},
                new float[]{0f, 0.60f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, platterR, paint);
        paint.setShader(null);

        paint.setStyle(Paint.Style.STROKE);
        for (float r = platterR * 0.40f; r < platterR * 0.95f; r += Dim.dp(2)) {
            paint.setStrokeWidth(r % (Dim.dp(4)) < 1f ? Math.max(1f, Dim.dp(1)) : Math.max(0.5f, Dim.dp(1) * 0.5f));
            paint.setColor(r % (Dim.dp(6)) < 1f
                    ? withAlpha(0xFFFFFFFF, 0x12) : withAlpha(0xFF000000, 0x20));
            canvas.drawCircle(cx, cy, r, paint);
        }

        float labelR = platterR * 0.32f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(label);
        canvas.drawCircle(cx, cy, labelR, paint);
        if (hasArtwork()) {
            drawArtwork(canvas, new RectF(cx - labelR, cy - labelR, cx + labelR, cy + labelR), true);
        }
        paint.setColor(0xFF000000);
        canvas.drawCircle(cx, cy, Math.max(2f, Dim.dp(3)), paint);
        canvas.restore();

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Dim.dp(3));
        paint.setColor(accent);
        float armX = left + size * 0.82f;
        float armY = top + size * 0.18f;
        canvas.drawLine(armX, armY, cx + platterR * 0.68f, cy - platterR * 0.68f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(darken(accent, 0.25f));
        canvas.drawCircle(cx + platterR * 0.68f, cy - platterR * 0.68f, Dim.dp(4), paint);
    }

    private void drawIpod(Canvas canvas, float left, float top, float size) {
        float cx = left + size / 2f;
        float radius = Dim.dp(Math.max(12, config.cornerRadiusDp()));
        int body = CoverStyleConfig.color(config.paperColor(), 0xFFF2F2F2);
        int screenBg = CoverStyleConfig.color(config.frameColor(), 0xFF1A1A1E);
        int wheel = CoverStyleConfig.color(config.accentColor(), 0xFFB0B0B0);
        int center = CoverStyleConfig.color(config.labelColor(), 0xFFFFFFFF);
        RectF bodyR = new RectF(left, top, left + size, top + size);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left, top, left + size, top + size,
                lighten(body, 0.12f), darken(body, 0.12f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bodyR, radius, radius, paint);
        paint.setShader(null);

        float inset = size * 0.12f;
        RectF screen = new RectF(left + inset, top + inset, left + size - inset, top + size * 0.52f);
        float sr = Dim.dp(Math.max(4, config.cornerRadiusDp() / 2));
        paint.setColor(screenBg);
        canvas.drawRoundRect(screen, sr, sr, paint);
        if (hasArtwork()) {
            drawArtwork(canvas, screen, false, Math.max(1, config.cornerRadiusDp() - 2));
        }

        float wheelCy = top + size * 0.74f;
        float wheelR = size * 0.20f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(wheel);
        canvas.drawCircle(cx, wheelCy, wheelR, paint);
        paint.setColor(darken(wheel, 0.25f));
        canvas.drawCircle(cx, wheelCy, wheelR * 0.55f, paint);
        paint.setColor(center);
        canvas.drawCircle(cx, wheelCy, wheelR * 0.35f, paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Dim.dp(1));
        paint.setColor(withAlpha(0xFF000000, 0x22));
        canvas.drawRoundRect(bodyR, radius, radius, paint);
    }

    private void drawFisheye(Canvas canvas, float left, float top, float size) {
        float cx = left + size / 2f;
        float radius = Dim.dp(Math.max(8, config.cornerRadiusDp()));
        int body = CoverStyleConfig.color(config.shellColor(), 0xFF2A2A2E);
        int bezel = CoverStyleConfig.color(config.frameColor(), 0xFF111113);
        int accent = CoverStyleConfig.color(config.accentColor(), 0xFF6BA8E8);
        RectF bodyR = new RectF(left, top, left + size, top + size);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left, top, left + size, top + size,
                lighten(body, 0.12f), darken(body, 0.30f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(bodyR, radius, radius, paint);
        paint.setShader(null);

        float cy = top + size * 0.45f;
        float scrR = size * 0.34f;
        paint.setColor(bezel);
        canvas.drawCircle(cx, cy, scrR + Dim.dp(6), paint);
        if (hasArtwork()) {
            drawArtwork(canvas, new RectF(cx - scrR, cy - scrR, cx + scrR, cy + scrR), true);
        }

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Dim.dp(2));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x66));
        canvas.drawLine(cx - size * 0.15f, top + Dim.dp(6),
                cx - size * 0.25f, top + Dim.dp(6) - Dim.dp(12), paint);
        canvas.drawLine(cx + size * 0.15f, top + Dim.dp(6),
                cx + size * 0.25f, top + Dim.dp(6) - Dim.dp(12), paint);

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        canvas.drawCircle(left + size * 0.82f, top + size * 0.78f, Dim.dp(6), paint);
        canvas.drawCircle(left + size * 0.82f, top + size * 0.90f, Dim.dp(4), paint);
    }

    private void drawRecordBox(Canvas canvas, float left, float top, float size) {
        float cx = left + size / 2f;
        float cy = top + size / 2f;
        float radius = Dim.dp(Math.max(4, config.cornerRadiusDp()));
        int box = CoverStyleConfig.color(config.shellColor(), 0xFF3A3A40);
        int sleeve = CoverStyleConfig.color(config.frameColor(), 0xFF1A1A1E);
        int disc = resolveDiscColor(config.discColor(), 0xFF1A1A1E, 0.35f);
        int accent = CoverStyleConfig.color(config.accentColor(), 0xFFFFFFFF);
        RectF base = new RectF(left, top, left + size, top + size);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left, top, left + size, top + size,
                lighten(box, 0.14f), darken(box, 0.32f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(base, radius, radius, paint);
        paint.setShader(null);

        float inset = size * 0.09f;
        RectF cover = new RectF(left + inset, top + inset, left + size - inset, top + size - inset);
        float coverR = Dim.dp(Math.max(2, config.cornerRadiusDp() / 2));
        paint.setColor(sleeve);
        canvas.drawRoundRect(cover, coverR, coverR, paint);
        if (hasArtwork()) {
            drawArtwork(canvas, cover, false, Math.max(1, config.cornerRadiusDp() - 2));
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Dim.dp(1));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x28));
        canvas.drawRoundRect(cover, coverR, coverR, paint);
        paint.setColor(withAlpha(0xFF000000, 0x44));
        canvas.drawRoundRect(new RectF(cover.left + Dim.dp(1), cover.top + Dim.dp(1),
                cover.right + Dim.dp(1), cover.bottom + Dim.dp(1)), coverR, coverR, paint);

        // record peeking from the right side
        float discCx = left + size * 0.78f;
        float discCy = cy;
        float discR = size * 0.24f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(darken(disc, 0.15f));
        canvas.drawCircle(discCx, discCy, discR, paint);
        paint.setColor(withAlpha(0x000000, 0x44));
        canvas.drawCircle(discCx + Dim.dp(2), discCy + Dim.dp(2), discR, paint);
        if (hasArtwork()) {
            drawArtwork(canvas, new RectF(discCx - discR * 0.55f, discCy - discR * 0.55f,
                    discCx + discR * 0.55f, discCy + discR * 0.55f), true);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Dim.dp(1));
        paint.setColor(withAlpha(accent, 0x88));
        canvas.drawCircle(discCx, discCy, discR, paint);

        // lid
        RectF lid = new RectF(left + size * 0.06f, top + size * 0.05f,
                left + size * 0.94f, top + size * 0.18f);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(lid.left, lid.top, lid.right, lid.bottom,
                lighten(box, 0.22f), darken(box, 0.10f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(lid, Dim.dp(4), Dim.dp(4), paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Dim.dp(1));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x44));
        canvas.drawRoundRect(lid, Dim.dp(4), Dim.dp(4), paint);
        paint.setColor(withAlpha(0xFF000000, 0x55));
        canvas.drawRoundRect(new RectF(lid.left + Dim.dp(1), lid.top + Dim.dp(1),
                lid.right - Dim.dp(1), lid.bottom - Dim.dp(1)), Dim.dp(4), Dim.dp(4), paint);

        // handle
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Dim.dp(2));
        paint.setColor(accent);
        canvas.drawLine(left + size * 0.38f, top + size * 0.115f,
                left + size * 0.62f, top + size * 0.115f, paint);
    }

    private void drawEmboss(Canvas canvas, float left, float top, float size) {
        float radius = Dim.dp(Math.max(2, config.cornerRadiusDp()));
        RectF dst = new RectF(left, top, left + size, top + size);
        drawArtwork(canvas, dst, false);
        float border = Dim.dp(Math.max(2, config.borderWidthDp()));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(border);
        paint.setColor(withAlpha(0xFFFFFFFF, 0x88));
        canvas.drawRoundRect(new RectF(dst.left + border / 2f, dst.top + border / 2f,
                dst.right - border / 2f, dst.bottom - border / 2f), radius, radius, paint);
        paint.setColor(withAlpha(0xFF000000, 0x66));
        canvas.drawRoundRect(new RectF(dst.left + border * 1.5f, dst.top + border * 1.5f,
                dst.right - border * 1.5f, dst.bottom - border * 1.5f), radius, radius, paint);
    }

    private void drawProjector(Canvas canvas, float left, float top, float size) {
        float radius = Dim.dp(Math.max(4, config.cornerRadiusDp()));
        int body = CoverStyleConfig.color(config.shellColor(), 0xFF2A2A2E);
        int accent = CoverStyleConfig.color(config.accentColor(), 0xFF6BA8E8);
        int filmTone = CoverStyleConfig.color(config.filmTone(), 0xFF1A1A1E);
        RectF base = new RectF(left, top, left + size, top + size);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left, top, left + size, top + size,
                lighten(body, 0.12f), darken(body, 0.35f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(base, radius, radius, paint);
        paint.setShader(null);

        // film reels
        float reelR = size * 0.12f;
        float reelY = top + size * 0.18f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(darken(body, 0.20f));
        canvas.drawCircle(left + size * 0.22f, reelY, reelR, paint);
        canvas.drawCircle(left + size * 0.78f, reelY, reelR, paint);
        paint.setColor(withAlpha(0xFFFFFFFF, 0x22));
        canvas.drawCircle(left + size * 0.22f, reelY, reelR * 0.45f, paint);
        canvas.drawCircle(left + size * 0.78f, reelY, reelR * 0.45f, paint);

        // film gate window
        float edge = Math.max(Dim.dp(6), size * 0.08f);
        RectF window = new RectF(left + edge, top + size * 0.34f, left + size - edge, top + size - edge);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(darken(filmTone, 0.45f));
        canvas.drawRoundRect(new RectF(window.left - Dim.dp(2), window.top - Dim.dp(2),
                window.right + Dim.dp(2), window.bottom + Dim.dp(2)), radius, radius, paint);
        drawArtwork(canvas, window, false, Math.max(1, config.cornerRadiusDp() - 2));
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x30));
        canvas.drawRoundRect(window, radius, radius, paint);

        // lens
        float lensCx = left + size / 2f;
        float lensCy = top + size * 0.28f;
        float lensR = size * 0.10f;
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(accent);
        canvas.drawCircle(lensCx, lensCy, lensR, paint);
        paint.setColor(darken(accent, 0.40f));
        canvas.drawCircle(lensCx, lensCy, lensR * 0.55f, paint);
        paint.setColor(withAlpha(0xFFFFFFFF, 0x66));
        canvas.drawCircle(lensCx - lensR * 0.25f, lensCy - lensR * 0.25f, lensR * 0.25f, paint);
    }

    private void drawFramed(Canvas canvas, float left, float top, float size, boolean poster) {
        int frame = CoverStyleConfig.color(config.frameColor(), 0xFF1A1A1E);
        int accent = CoverStyleConfig.color(config.accentColor(), 0xFFFFFFFF);
        int matColor = CoverStyleConfig.color(config.paperColor(), 0xFFFFF6E3);
        float radius = Dim.dp(Math.max(1, config.cornerRadiusDp() / 2));
        RectF outer = new RectF(left, top, left + size, top + size);

        float border = Dim.dp(Math.max(2, config.borderWidthDp()));
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(left, top, left + size, top + size,
                lighten(frame, poster ? 0.10f : 0.22f),
                darken(frame, poster ? 0.15f : 0.30f), Shader.TileMode.CLAMP));
        canvas.drawRoundRect(outer, radius, radius, paint);
        paint.setShader(null);

        if (poster) {
            RectF art = new RectF(outer.left + border, outer.top + border,
                    outer.right - border, outer.bottom - border);
            paint.setColor(withAlpha(0xFF000000, 0x55));
            canvas.drawRect(art, paint);
            drawArtwork(canvas, art, false, Math.max(1, config.cornerRadiusDp() - 1));
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
            paint.setColor(withAlpha(accent, 0x55));
            canvas.drawRect(art, paint);
            return;
        }

        float bevel = Math.max(1f, border * 0.28f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFFFFFFFF, 0x33));
        canvas.drawRoundRect(new RectF(outer.left + bevel, outer.top + bevel,
                outer.right - bevel, outer.bottom - bevel), radius, radius, paint);
        paint.setColor(withAlpha(0xFF000000, 0x44));
        canvas.drawRoundRect(new RectF(outer.left + bevel * 1.8f, outer.top + bevel * 1.8f,
                outer.right - bevel * 1.8f, outer.bottom - bevel * 1.8f),
                radius, radius, paint);

        RectF mat = new RectF(outer.left + border, outer.top + border,
                outer.right - border, outer.bottom - border);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(matColor);
        canvas.drawRect(mat, paint);

        float inset = Math.max(Dim.dp(4), size * 0.035f);
        RectF art = new RectF(mat.left + inset, mat.top + inset,
                mat.right - inset, mat.bottom - inset);
        paint.setColor(withAlpha(0xFF000000, 0x55));
        canvas.drawRect(art, paint);
        drawArtwork(canvas, art, false, 0);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1f, Dim.dp(1)));
        paint.setColor(withAlpha(0xFF000000, 0x55));
        canvas.drawRect(new RectF(art.left - Dim.dp(1), art.top - Dim.dp(1),
                art.right + Dim.dp(1), art.bottom + Dim.dp(1)), paint);
        paint.setColor(withAlpha(accent, 0x44));
        canvas.drawRect(art, paint);
    }

    private boolean hasArtwork() {
        return artwork != null && !artwork.isRecycled();
    }

    private void ensureShaders(float left, float top, float size) {
        if (shaderSize == size && shaderLeft == left && shaderTop == top) {
            return;
        }
        shaderLeft = left;
        shaderTop = top;
        shaderSize = size;
        vinylDiscShader = null;
        vinylSheenShader = null;
        cdDiscShader = null;
        cdSweepShader = null;
        cassetteShellShader = null;
    }

    private void invalidateShaders() {
        shaderLeft = Float.NaN;
        shaderTop = Float.NaN;
        shaderSize = -1f;
        vinylDiscShader = null;
        vinylSheenShader = null;
        cdDiscShader = null;
        cdSweepShader = null;
        cassetteShellShader = null;
    }

    private void drawArtwork(Canvas canvas, RectF dst, boolean circular) {
        drawArtwork(canvas, dst, circular, config.cornerRadiusDp());
    }

    private void drawArtwork(Canvas canvas, RectF dst, boolean circular, int radiusDp) {
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(backgroundColor);
        if (!hasArtwork()) {
            if (circular) {
                canvas.drawCircle(dst.centerX(), dst.centerY(),
                        Math.min(dst.width(), dst.height()) / 2f, paint);
            } else {
                canvas.drawRect(dst, paint);
            }
            return;
        }
        canvas.save();
        if (circular) {
            circle.reset();
            circle.addCircle(dst.centerX(), dst.centerY(),
                    Math.min(dst.width(), dst.height()) / 2f, Path.Direction.CW);
            canvas.clipPath(circle);
        } else if (radiusDp > 0) {
            float radius = Dim.dp(radiusDp);
            clip.reset();
            clip.addRoundRect(dst, radius, radius, Path.Direction.CW);
            canvas.clipPath(clip);
        }
        float imageWidth = artwork.getWidth();
        float imageHeight = artwork.getHeight();
        float scale = Math.max(dst.width() / imageWidth, dst.height() / imageHeight);
        float srcWidth = dst.width() / scale;
        float srcHeight = dst.height() / scale;
        float left = (imageWidth - srcWidth) / 2f;
        float top = (imageHeight - srcHeight) / 2f;
        Rect src = new Rect((int) left, (int) top,
                (int) Math.min(imageWidth, left + srcWidth),
                (int) Math.min(imageHeight, top + srcHeight));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawBitmap(artwork, src, dst, paint);
        canvas.restore();
    }

    private static int lighten(int color, float amount) {
        int alpha = color >>> 24;
        int r = Math.min(255, Math.round(((color >> 16) & 0xFF) + (255 - ((color >> 16) & 0xFF)) * amount));
        int g = Math.min(255, Math.round(((color >> 8) & 0xFF) + (255 - ((color >> 8) & 0xFF)) * amount));
        int b = Math.min(255, Math.round((color & 0xFF) + (255 - (color & 0xFF)) * amount));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }


    private int resolveDiscColor(String token, int fallback, float darkenAmount) {
        if (token != null && token.equalsIgnoreCase("auto")) {
            int base = LyricsTextColor.resolve(Settings.LYRICS_TEXT_COLOR_SLOT.get(),
                    Settings.LYRICS_FULLSCREEN_TEXT_COLOR.get());
            return darken(base, darkenAmount);
        }
        return CoverStyleConfig.color(token, fallback);
    }

    private static int darken(int color, float amount) {
        int alpha = color >>> 24;
        int r = Math.max(0, Math.round(((color >> 16) & 0xFF) * (1f - amount)));
        int g = Math.max(0, Math.round(((color >> 8) & 0xFF) * (1f - amount)));
        int b = Math.max(0, Math.round((color & 0xFF) * (1f - amount)));
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
