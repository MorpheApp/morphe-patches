/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference;

import android.content.Context;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

@SuppressWarnings("deprecation")
public final class ForceSystemFontPreference extends BooleanSettingPreference {

    public ForceSystemFontPreference(Context context) {
        super(context, Settings.FORCE_SYSTEM_FONT);

        setOnPreferenceChangeListener((preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue) && Settings.CUSTOM_FONT.get()) {
                // Force system font disables custom font mode to avoid conflicting runtime hooks.
                AbstractPreferenceFragment.settingImportInProgress = true;
                try {
                    Settings.CUSTOM_FONT.save(false);
                } finally {
                    AbstractPreferenceFragment.settingImportInProgress = false;
                }
            }

            AbstractPreferenceFragment.showRestartDialog(context);
            return true;
        });
    }
}