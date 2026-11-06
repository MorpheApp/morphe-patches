/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4162
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Draws a small dot after the last character of a text, to show a setting or list entry is new.
 * <p>
 * The dot is drawn in place of a word joiner added to the end of the text. The span must not
 * cover a character of the text itself: that splits the text into separate runs, which breaks
 * the joined letters of Arabic and moves the last character of a left to right word shown in
 * right to left text. The word joiner is invisible, and keeps the dot on the same line as the
 * last word. Code that uses the text as data, such as settings search, sorting or the copied
 * preference path, uses {@link #removeFrom(CharSequence)} to get the text without it.
 */
public class NewIndicatorSpan extends ReplacementSpan {

    /**
     * Dot radius and the gap before the dot, as a fraction of the text size.
     */
    private static final float DOT_RADIUS_TEXT_SIZE_RATIO = 0.18f;
    private static final float DOT_GAP_TEXT_SIZE_RATIO = 0.3f;

    private static final String WORD_JOINER = "⁠";

    /**
     * @return A copy of the text with the dot added, or the text itself if it is empty.
     */
    public static CharSequence addTo(@Nullable CharSequence text, @ColorInt int color) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }

        // The end of right to left text is on the left.
        final boolean rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(text, 0, text.length());
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        final int start = builder.length();
        builder.append(WORD_JOINER);
        builder.setSpan(new NewIndicatorSpan(color, rtl), start, builder.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return new SpannableString(builder);
    }

    /**
     * @return If the text has a dot.
     */
    public static boolean isIn(@Nullable CharSequence text) {
        return text instanceof Spanned spanned
                && spanned.getSpans(0, spanned.length(), NewIndicatorSpan.class).length > 0;
    }

    /**
     * @return A copy of the text without the dot, or the text itself if it has no dot.
     *         A String if the text has no other spans.
     */
    @Nullable
    public static CharSequence removeFrom(@Nullable CharSequence text) {
        if (!isIn(text)) {
            return text;
        }

        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        for (NewIndicatorSpan span : builder.getSpans(0, builder.length(), NewIndicatorSpan.class)) {
            final int start = builder.getSpanStart(span);
            final int end = builder.getSpanEnd(span);
            builder.removeSpan(span);
            builder.delete(start, end);
        }
        return builder.getSpans(0, builder.length(), Object.class).length == 0
                ? builder.toString()
                : new SpannableString(builder);
    }

    @ColorInt
    private final int color;
    private final boolean rtl;

    private NewIndicatorSpan(@ColorInt int color, boolean rtl) {
        this.color = color;
        this.rtl = rtl;
    }

    private static float dotWidth(Paint paint) {
        return paint.getTextSize() * (DOT_GAP_TEXT_SIZE_RATIO + 2 * DOT_RADIUS_TEXT_SIZE_RATIO);
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt fm) {
        if (fm != null) {
            paint.getFontMetricsInt(fm);
        }
        return Math.round(dotWidth(paint));
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, Paint paint) {
        final float radius = paint.getTextSize() * DOT_RADIUS_TEXT_SIZE_RATIO;
        // The gap is on the side of the text.
        final float dotCenterX = rtl
                ? x + radius
                : x + dotWidth(paint) - radius;
        // Vertically centered on the letters.
        final float dotCenterY = y + (paint.ascent() + paint.descent()) / 2;

        final int originalColor = paint.getColor();
        final Paint.Style originalStyle = paint.getStyle();
        paint.setColor(color);
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(dotCenterX, dotCenterY, radius, paint);
        paint.setColor(originalColor);
        paint.setStyle(originalStyle);
    }
}
