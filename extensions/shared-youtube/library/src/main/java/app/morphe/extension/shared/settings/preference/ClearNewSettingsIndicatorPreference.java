/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4162
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.util.AttributeSet;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Asks to confirm, then removes the dot of all settings that {@link NewSettingsIndicator} shows as new.
 */
@SuppressWarnings({"unused", "deprecation"})
public class ClearNewSettingsIndicatorPreference extends Preference {

    {
        setPersistent(false);
    }

    public ClearNewSettingsIndicatorPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ClearNewSettingsIndicatorPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ClearNewSettingsIndicatorPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ClearNewSettingsIndicatorPreference(Context context) {
        super(context);
    }

    @Override
    protected void onClick() {
        try {
            CustomDialog.create(getContext(),
                    str("morphe_settings_new_indicator_clear_dialog_title"),
                    str("morphe_settings_new_indicator_clear_dialog_message"),
                    null,
                    null,
                    this::clear,
                    () -> { },
                    null,
                    null,
                    true).first.show();
        } catch (Exception ex) {
            Logger.printException(() -> "onClick failure", ex);
        }
    }

    private void clear() {
        PreferenceGroup root = getParent();
        if (root == null) {
            Logger.printException(() -> "Preference is not in a screen");
            return;
        }
        while (root.getParent() != null) {
            root = root.getParent();
        }

        NewSettingsIndicator.markAllSeen((PreferenceScreen) root);
    }
}
