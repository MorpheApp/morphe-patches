/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3673
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.youtube.patches.CustomFontPatch;
import app.morphe.extension.youtube.patches.CustomFontPatch.FontType;

/**
 * Font type list where the custom font can only be selected after a font file is picked.
 */
@SuppressWarnings({"unused", "deprecation"})
public class CustomFontTypePreference extends CustomDialogListPreference {

    public CustomFontTypePreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installChangeGate();
    }

    public CustomFontTypePreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installChangeGate();
    }

    public CustomFontTypePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installChangeGate();
    }

    public CustomFontTypePreference(Context context) {
        super(context);
        installChangeGate();
    }

    private void installChangeGate() {
        setOnPreferenceChangeListener((preference, newValue) -> {
            if (FontType.CUSTOM.name().equals(newValue)
                    && !CustomFontPatch.isFontFileSet(preference.getContext())) {
                Utils.showToastLong(str("morphe_custom_font_pick_file_first"));
                return false;
            }
            return true;
        });
    }
}
