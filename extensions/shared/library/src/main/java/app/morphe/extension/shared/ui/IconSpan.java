/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.ui;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws a drawable inline with text, tinted with the color of the text.
 * The drawable is replaced over the characters the span is applied to.
 */
public final class IconSpan extends ReplacementSpan {

    private final Drawable drawable;
    private final float sizeRelativeToText;
    private final float opacity;

    /**
     * @param drawable           Drawable that is tinted, so it should be drawn in a single color.
     *                           The drawable is shared by all uses of this span, and is only drawn on the main thread.
     * @param sizeRelativeToText Width and height of the icon relative to the text size.
     * @param opacity            Opacity of the icon relative to the opacity of the text.
     */
    public IconSpan(Drawable drawable, float sizeRelativeToText, float opacity) {
        this.drawable = drawable;
        this.sizeRelativeToText = sizeRelativeToText;
        this.opacity = opacity;
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt fontMetrics) {
        // The line height is the same as the line height of the text.
        if (fontMetrics != null) {
            paint.getFontMetricsInt(fontMetrics);
        }
        return Math.round(paint.getTextSize() * sizeRelativeToText);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, @NonNull Paint paint) {
        final int size = Math.round(paint.getTextSize() * sizeRelativeToText);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        // Centered on the text, which is above the baseline.
        final int left = Math.round(x);
        final int iconTop = Math.round(y + (metrics.ascent + metrics.descent) / 2 - size / 2f);

        final int textColor = textColor(text, start, paint.getColor());
        // The tint is opaque, and the opacity of the icon is set separately.
        drawable.setTint(textColor | 0xFF000000);
        drawable.setAlpha(Math.round(Color.alpha(textColor) * opacity));
        drawable.setBounds(left, iconTop, left + size, iconTop + size);
        drawable.draw(canvas);
    }

    /**
     * The color of the text can be set by a span, which is not applied to the paint of this span.
     */
    private static int textColor(CharSequence text, int start, int paintColor) {
        if (text instanceof Spanned spanned) {
            ForegroundColorSpan[] colors = spanned.getSpans(start, start + 1, ForegroundColorSpan.class);
            if (colors.length > 0) {
                return colors[colors.length - 1].getForegroundColor();
            }
        }
        return paintColor;
    }
}
