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
import android.text.Spanned;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.text.style.ReplacementSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

/**
 * Draws a small dot after the last character of a text, to show a setting or list entry is new.
 * <p>
 * The span covers the existing last character and draws it together with the dot,
 * so the text itself does not change. Code that reads the text, such as settings search,
 * sorting or the copied preference path, does not see the dot.
 */
public class NewIndicatorSpan extends ReplacementSpan {

    /**
     * Dot radius and the gap before the dot, as a fraction of the text size.
     */
    private static final float DOT_RADIUS_TEXT_SIZE_RATIO = 0.18f;
    private static final float DOT_GAP_TEXT_SIZE_RATIO = 0.3f;

    /**
     * @return A copy of the text with the dot added, or the text itself if it is empty.
     */
    public static CharSequence addTo(@Nullable CharSequence text, @ColorInt int color) {
        if (TextUtils.isEmpty(text)) {
            return text;
        }

        SpannableString spannable = new SpannableString(text);
        final int end = spannable.length();
        final int start = Character.offsetByCodePoints(spannable, end, -1);
        // The last character of right to left text is shown on the left.
        final boolean rtl = TextDirectionHeuristics.FIRSTSTRONG_LTR.isRtl(spannable, 0, end);
        spannable.setSpan(new NewIndicatorSpan(color, rtl), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return spannable;
    }

    @ColorInt
    private final int color;
    private final boolean rtl;

    private NewIndicatorSpan(@ColorInt int color, boolean rtl) {
        this.color = color;
        this.rtl = rtl;
    }

    private static float dotWidth(Paint paint) {
        final float textSize = paint.getTextSize();
        return textSize * (DOT_GAP_TEXT_SIZE_RATIO + 2 * DOT_RADIUS_TEXT_SIZE_RATIO);
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
                       @Nullable Paint.FontMetricsInt fm) {
        if (fm != null) {
            paint.getFontMetricsInt(fm);
        }
        return Math.round(paint.measureText(text, start, end) + dotWidth(paint));
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
                     float x, int top, int y, int bottom, Paint paint) {
        final float textWidth = paint.measureText(text, start, end);
        final float dotWidth = dotWidth(paint);
        final float radius = paint.getTextSize() * DOT_RADIUS_TEXT_SIZE_RATIO;

        final float dotCenterX;
        if (rtl) {
            canvas.drawText(text, start, end, x + dotWidth, y, paint);
            dotCenterX = x + radius;
        } else {
            canvas.drawText(text, start, end, x, y, paint);
            dotCenterX = x + textWidth + dotWidth - radius;
        }
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
