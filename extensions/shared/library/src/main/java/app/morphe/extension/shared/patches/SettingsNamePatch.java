/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2691
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.SharedSettings;

/**
 * Resolves the name shown for the Morphe settings, both the entry added to the
 * app settings screen and the toolbar of the Morphe settings itself.
 */
public final class SettingsNamePatch {

    /**
     * A preset stores its entry value and not its text, so the name follows the app language.
     * Its text is the string of the same index, since both arrays are positional.
     */
    private static final String ENTRY_VALUES_KEY = "morphe_settings_name_entry_values";
    private static final String ENTRY_KEY_PREFIX = "morphe_settings_name_entry_";

    /**
     * Injection point.
     * <p>
     * Null keeps the name declared during patching, which is 'Settings' for the YouTube
     * Cairo layout and the Morphe name everywhere else.
     */
    @Nullable
    public static String getCustomSettingsName() {
        try {
            String value = SharedSettings.SETTINGS_NAME.get().trim();
            if (value.isEmpty() || value.equals(SharedSettings.SETTINGS_NAME.defaultValue)) {
                return null;
            }

            String[] entryValues = ResourceUtils.getStringArray(ENTRY_VALUES_KEY);
            for (int i = 0, length = entryValues.length; i < length; i++) {
                if (entryValues[i].equals(value)) {
                    String entryKey = ENTRY_KEY_PREFIX + i;
                    if (ResourceUtils.getStringIdentifier(entryKey) == 0) {
                        break;
                    }
                    // Resolved per call, so the app language wins over the Morphe language.
                    return ResourceUtils.getString(entryKey);
                }
            }

            // Anything that is not a preset is a name the user typed.
            return value;
        } catch (Exception ex) {
            Logger.printException(() -> "getCustomSettingsName failure", ex);
        }

        return null;
    }

    /**
     * The name to show where nothing was declared during patching, such as the toolbar.
     */
    public static String getSettingsName() {
        String customName = getCustomSettingsName();
        return customName != null
                ? customName
                : ResourceUtils.getString("morphe_settings_title");
    }

    private SettingsNamePatch() {
    }
}
