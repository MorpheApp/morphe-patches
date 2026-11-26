/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.shared;

import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.Nullable;

public final class LithoDrawables {

    private LithoDrawables() {
    }

    /**
     * @return The view the drawable is drawn in, or null if the drawable is not mounted.
     */
    @Nullable
    public static View getDrawableView(Drawable drawable) {
        Drawable.Callback callback = drawable.getCallback();
        while (callback instanceof Drawable parent) {
            callback = parent.getCallback();
        }
        return callback instanceof View view ? view : null;
    }
}
