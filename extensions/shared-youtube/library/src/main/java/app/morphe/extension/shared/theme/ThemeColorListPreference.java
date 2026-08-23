/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2524
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.theme;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.IconListPreference;
import app.morphe.extension.shared.ui.ColorDot;

/**
 * Shows the color of every background next to its name, the same way the app icons are shown.
 */
@SuppressWarnings({"unused", "deprecation"})
public class ThemeColorListPreference extends IconListPreference {

    public ThemeColorListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ThemeColorListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ThemeColorListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ThemeColorListPreference(Context context) {
        super(context);
    }

    @NonNull
    @Override
    protected Drawable[] resolveIconDrawables() {
        CharSequence[] values = getEntryValues();
        if (values == null) {
            return new Drawable[0];
        }

        final boolean dark = SharedYouTubeSettings.THEME_COLOR_DARK.key.equals(getKey());

        Context context = getContext();
        Drawable[] drawables = new Drawable[values.length];
        for (int i = 0, length = values.length; i < length; i++) {
            drawables[i] = ColorDot.createColorDotDrawable(
                    ThemeColorPatch.getThemeColor(context, dark, i));
        }

        return drawables;
    }
}
