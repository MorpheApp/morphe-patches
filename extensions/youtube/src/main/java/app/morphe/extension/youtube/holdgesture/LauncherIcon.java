/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5134
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * An icon of the launcher, drawn with lines like the lock of the speed.
 */
final class LauncherIcon extends Drawable {

    enum Glyph {
        /** A screen with a padlock on it. */
        SCREEN_LOCK,
        /** Two arrows that follow each other around. */
        LOOP,
        CAMERA,
        CLOCK,
        /** Two links of a chain. */
        LINK
    }

    /** The glyphs are laid out on a square of this size. */
    private static final float GRID = 24;
    private static final float LINE = 2;

    private final Glyph glyph;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int color = 0xFFFFFFFF;
    private int alpha = 255;

    LauncherIcon(Glyph glyph) {
        this.glyph = glyph;
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        final Rect bounds = getBounds();
        if (bounds.isEmpty() || alpha == 0) return;

        final int inked = ((color >>> 24) * alpha / 255) << 24 | (color & 0x00FFFFFF);
        stroke.setColor(inked);
        stroke.setStrokeWidth(LINE);
        fill.setColor(inked);

        canvas.save();
        canvas.translate(bounds.left, bounds.top);
        canvas.scale(bounds.width() / GRID, bounds.height() / GRID);
        path.rewind();
        switch (glyph) {
            case SCREEN_LOCK -> {
                canvas.drawRoundRect(2.5f, 4.5f, 21.5f, 19.5f, 2.5f, 2.5f, stroke);
                canvas.drawRoundRect(9, 11.5f, 15, 16, 1, 1, fill);
                stroke.setStrokeWidth(1.6f);
                path.moveTo(10.2f, 11.5f);
                path.lineTo(10.2f, 10);
                path.arcTo(10.2f, 8.2f, 13.8f, 11.8f, 180, 180, false);
                path.lineTo(13.8f, 11.5f);
                canvas.drawPath(path, stroke);
            }
            case LOOP -> {
                // The upper line runs to the right, the lower one back to the left.
                path.moveTo(4, 11);
                path.lineTo(4, 9.5f);
                path.arcTo(4, 6, 11, 13, 180, 90, false);
                path.lineTo(19.5f, 6);
                path.moveTo(16.5f, 3);
                path.lineTo(19.5f, 6);
                path.lineTo(16.5f, 9);
                path.moveTo(20, 13);
                path.lineTo(20, 14.5f);
                path.arcTo(13, 11, 20, 18, 0, 90, false);
                path.lineTo(4.5f, 18);
                path.moveTo(7.5f, 15);
                path.lineTo(4.5f, 18);
                path.lineTo(7.5f, 21);
                canvas.drawPath(path, stroke);
            }
            case CAMERA -> {
                path.moveTo(8.2f, 7);
                path.lineTo(9.8f, 4.5f);
                path.lineTo(14.2f, 4.5f);
                path.lineTo(15.8f, 7);
                canvas.drawPath(path, stroke);
                canvas.drawRoundRect(2.5f, 7, 21.5f, 19.5f, 2.5f, 2.5f, stroke);
                canvas.drawCircle(12, 13.2f, 3.3f, stroke);
            }
            case CLOCK -> {
                canvas.drawCircle(12, 12, 8.8f, stroke);
                path.moveTo(12, 7.2f);
                path.lineTo(12, 12);
                path.lineTo(15.2f, 14);
                canvas.drawPath(path, stroke);
            }
            case LINK -> {
                path.moveTo(10, 8);
                path.lineTo(7, 8);
                path.arcTo(3, 8, 11, 16, 270, -180, false);
                path.lineTo(10, 16);
                path.moveTo(14, 8);
                path.lineTo(17, 8);
                path.arcTo(13, 8, 21, 16, 270, 180, false);
                path.lineTo(14, 16);
                path.moveTo(8.5f, 12);
                path.lineTo(15.5f, 12);
                canvas.drawPath(path, stroke);
            }
        }
        canvas.restore();
    }

    @Override
    public void setTint(int tintColor) {
        if (color != tintColor) {
            color = tintColor;
            invalidateSelf();
        }
    }

    @Override
    public void setAlpha(int alpha) {
        if (this.alpha != alpha) {
            this.alpha = alpha;
            invalidateSelf();
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        // Colored with setTint.
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
