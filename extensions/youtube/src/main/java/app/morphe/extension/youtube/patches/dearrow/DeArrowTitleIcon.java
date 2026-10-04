/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3510
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.morphe.extension.youtube.settings.Settings;

/**
 * Shows the DeArrow icon before the titles submitted to DeArrow,
 * so they can be told apart from the titles of the uploader.
 * <p>
 * The icon is drawn with the color of the title, so it matches the theme
 * without using any resources.
 */
public final class DeArrowTitleIcon {

    private static final boolean SHOW_ICON = Settings.DEARROW_TITLES.get() && Settings.DEARROW_TITLES_ICON.get();

    /**
     * Bullseye character that the icon is drawn over. The text of the title is not used to find the icon,
     * and the bullseye looks similar to the icon if the icon span is removed, such as when the title is copied.
     */
    private static final String ICON_PLACEHOLDER = "◎";

    /**
     * Separates the icon from the title.
     */
    private static final String ICON_SEPARATOR = " ";

    private DeArrowTitleIcon() {
    }

    /**
     * @return If the icon is shown before the title.
     */
    public static boolean isShown(@Nullable String title) {
        return SHOW_ICON && title != null && DeArrowTitleRequest.isDeArrowTitle(title.trim());
    }

    /**
     * @return If the text starts with the icon added by this class.
     */
    public static boolean hasIcon(@Nullable CharSequence text) {
        //noinspection SizeReplaceableByIsEmpty
        return text instanceof Spanned spanned && spanned.length() > 0
                && spanned.getSpans(0, 1, IconSpan.class).length > 0;
    }

    /**
     * The icon is shown only after {@link #setIconSpan(Spannable)} is called with the text,
     * so spans of the entire title can be applied to the icon first.
     *
     * @return The title with the character that is replaced by the icon.
     */
    public static String addIcon(String title) {
        return ICON_PLACEHOLDER + ICON_SEPARATOR + title;
    }

    /**
     * Shows the icon over the placeholder character.
     *
     * @param text Text returned by {@link #addIcon(String)}, with the spans of the title.
     */
    public static void setIconSpan(Spannable text) {
        text.setSpan(new IconSpan(), 0, ICON_PLACEHOLDER.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * Draws the DeArrow logo, a ring with a dot in the center.
     * https://github.com/ajayyy/DeArrow/blob/4d9e85b41382de0cc8ec2053789455b374b7a70d/public/icons/logo.svg
     * Logo copyright 2023 Ajay Ramachandran <dev@ajay.app>
     */
    private static final class IconSpan extends ReplacementSpan {
        /**
         * Size of the icon relative to the text size.
         */
        private static final float ICON_SIZE = 0.85f;
        private static final float RING_WIDTH = 0.14f;
        private static final float DOT_RADIUS = 0.15f;

        @Override
        public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                           @Nullable Paint.FontMetricsInt fontMetrics) {
            // The line height is the same as the line height of the text.
            if (fontMetrics != null) {
                paint.getFontMetricsInt(fontMetrics);
            }
            return Math.round(paint.getTextSize() * ICON_SIZE);
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                         float x, int top, int y, int bottom, @NonNull Paint paint) {
            final float size = paint.getTextSize() * ICON_SIZE;
            final float radius = size / 2;
            final float ringWidth = size * RING_WIDTH;
            Paint.FontMetrics metrics = paint.getFontMetrics();
            // Centered on the text, which is above the baseline.
            final float centerX = x + radius;
            final float centerY = y + (metrics.ascent + metrics.descent) / 2;

            final int color = paint.getColor();
            final Paint.Style style = paint.getStyle();
            final float strokeWidth = paint.getStrokeWidth();
            final boolean antiAlias = paint.isAntiAlias();

            paint.setColor(textColor(text, start, color));
            paint.setAntiAlias(true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(ringWidth);
            canvas.drawCircle(centerX, centerY, radius - ringWidth / 2, paint);
            paint.setStyle(Paint.Style.FILL);
            canvas.drawCircle(centerX, centerY, size * DOT_RADIUS, paint);

            paint.setColor(color);
            paint.setStyle(style);
            paint.setStrokeWidth(strokeWidth);
            paint.setAntiAlias(antiAlias);
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
}
