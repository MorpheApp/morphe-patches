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

final class PillDrawable extends GradientDrawable {

    private static final float PILL_ALPHA = 0.4f;

    PillDrawable() {
        setColor(Color.argb(Math.round(255 * PILL_ALPHA), 255, 255, 255));
    }

    @Override
    protected void onBoundsChange(Rect bounds) {
        super.onBoundsChange(bounds);
        setCornerRadius(bounds.height() / 2f);
    }
}
