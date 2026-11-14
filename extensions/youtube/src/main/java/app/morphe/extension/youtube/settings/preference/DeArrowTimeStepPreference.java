/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ResettableEditTextPreference;

/**
 * Step of the fine time adjustment buttons of the DeArrow new thumbnail dialog.
 * Only a positive number of milliseconds is accepted.
 */
@SuppressWarnings({"unused", "deprecation"})
public class DeArrowTimeStepPreference extends ResettableEditTextPreference {

    public DeArrowTimeStepPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        installValidator();
    }

    public DeArrowTimeStepPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        installValidator();
    }

    public DeArrowTimeStepPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        installValidator();
    }

    public DeArrowTimeStepPreference(Context context) {
        super(context);
        installValidator();
    }

    private void installValidator() {
        setOnPreferenceChangeListener((p, newValue) -> {
            try {
                if (Integer.parseInt(newValue.toString()) > 0) {
                    return true;
                }
            } catch (NumberFormatException ex) {
                Logger.printInfo(() -> "Invalid DeArrow time step", ex);
            }
            Utils.showSnackbarLong(str("morphe_dearrow_vote_time_step_invalid"));
            return false;
        });
    }
}
