/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3714
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.preference;

import app.morphe.extension.shared.settings.Setting;

/**
 * A preference that shows the value of a {@link Setting} and saves what the user picks to it.
 * <p>
 * The preference does not write the shared preferences itself, so the setting is the only owner of
 * its value. The settings screen calls {@link #updateFromSetting()} whenever a setting is saved, by
 * a preference, an import or other code.
 */
public interface SettingPreference {

    /**
     * Shows the current value of the setting, and if it is available.
     */
    void updateFromSetting();
}
