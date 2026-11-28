/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;

/**
 * A list preference that shows an explanation below the selected entry.
 * The explanation is the string with the key of the preference and the suffix "_summary".
 */
@SuppressWarnings({"unused", "deprecation"})
public class ExplainedListPreference extends CustomDialogListPreference {

    public ExplainedListPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ExplainedListPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ExplainedListPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ExplainedListPreference(Context context) {
        super(context);
    }

    @Override
    public void setSummary(CharSequence entry) {
        String key = getKey();
        if (key == null || entry == null) {
            super.setSummary(entry);
            return;
        }
        super.setSummary(entry + "\n" + str(key + "_summary"));
    }
}
