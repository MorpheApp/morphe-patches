/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

@SuppressWarnings("deprecation")
public final class CustomFontTogglePreference extends BooleanSettingPreference {

    public CustomFontTogglePreference(Context context) {
        super(context, Settings.CUSTOM_FONT);

        setOnPreferenceChangeListener((preference, newValue) -> {
            boolean enabling = Boolean.TRUE.equals(newValue);
            if (!enabling) {
                AbstractPreferenceFragment.showRestartDialog(context);
                return true;
            }

            // Enabling custom fonts without a selected file would be a no-op at runtime.
            String configuredPath = Settings.CUSTOM_FONT_FILE_PATH.get();
            if (configuredPath == null || configuredPath.trim().isEmpty()) {
                if (ResourceUtils.getStringIdentifier("morphe_custom_font_pick_file_first") != 0) {
                    Utils.showToastLong(str("morphe_custom_font_pick_file_first"));
                } else {
                    Utils.showToastLong("Pick a custom font file first.");
                }
                return false;
            }

            if (Settings.FORCE_SYSTEM_FONT.get()) {
                // Keep modes mutually exclusive without triggering nested listener side effects.
                AbstractPreferenceFragment.settingImportInProgress = true;
                try {
                    Settings.FORCE_SYSTEM_FONT.save(false);
                } finally {
                    AbstractPreferenceFragment.settingImportInProgress = false;
                }
            }

            AbstractPreferenceFragment.showRestartDialog(context);
            return true;
        });
    }
}