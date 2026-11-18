/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;

import app.morphe.extension.shared.ui.Dim;

/**
 * Frosted translucent glass capsule for section and singer tags.
 */
final class PillDrawable extends GradientDrawable {

    private static final float PILL_FILL_ALPHA = 0.16f;
    private static final float PILL_STROKE_ALPHA = 0.28f;

    PillDrawable() {
        setShape(GradientDrawable.RECTANGLE);
        int baseRgb = LyricsColors.lineTextColor() & 0x00FFFFFF;
        if (baseRgb == 0) {
            baseRgb = 0x00FFFFFF;
        }
        int r = (baseRgb >> 16) & 0xFF;
        int g = (baseRgb >> 8) & 0xFF;
        int b = baseRgb & 0xFF;
        setColor(Color.argb(Math.round(255f * PILL_FILL_ALPHA), r, g, b));
        setStroke(Math.max(1, Dim.dp(1)), Color.argb(Math.round(255f * PILL_STROKE_ALPHA), r, g, b));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        setCornerRadius(bounds.height() / 2f);
    }
}
