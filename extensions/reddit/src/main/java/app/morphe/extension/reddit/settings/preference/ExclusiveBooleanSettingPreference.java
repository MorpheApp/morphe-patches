/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference;

import android.content.Context;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

@SuppressWarnings("deprecation")
public final class ExclusiveBooleanSettingPreference extends BooleanSettingPreference {

    public ExclusiveBooleanSettingPreference(Context context,
                                             BooleanSetting setting,
                                             @Nullable BooleanSetting settingToDisableWhenEnabled) {
        super(context, setting);

        if (settingToDisableWhenEnabled == null) {
            return;
        }

        setOnPreferenceChangeListener((preference, newValue) -> {
            if (Boolean.TRUE.equals(newValue) && settingToDisableWhenEnabled.get()) {
                AbstractPreferenceFragment.settingImportInProgress = true;
                try {
                    settingToDisableWhenEnabled.save(false);
                } finally {
                    AbstractPreferenceFragment.settingImportInProgress = false;
                }
            }
            return true;
        });
    }
}