/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

final class OffsetRulerView extends View {

    private static final int RANGE_MS = 30000;

    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private int currentOffsetMs;

    @Nullable
    private Long recordTimeMs;

    OffsetRulerView(Context context) {
        super(context);
        valuePaint.setColor(Color.WHITE);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        setVisibility(GONE);
    }

    void setOffsetMs(int ms) {
        currentOffsetMs = Math.max(-RANGE_MS, Math.min(RANGE_MS, ms));
        invalidate();
    }

    void setRecordTimeMs(@Nullable Long ms) {
        recordTimeMs = ms;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = (int) (28 * getResources().getDisplayMetrics().density);
        setMeasuredDimension(w, h);
    }

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        float density = getResources().getDisplayMetrics().density;

        String offsetText = (currentOffsetMs >= 0 ? "+" : "") + currentOffsetMs + "ms";
        String text = recordTimeMs == null
                ? offsetText
                : SponsorBlockMusicSubmitDialog.formatTimeMs(recordTimeMs) + " " + offsetText;
        valuePaint.setTextSize(13 * density);
        canvas.drawText(text, w / 2f, h / 2f + 5 * density, valuePaint);
    }
}
