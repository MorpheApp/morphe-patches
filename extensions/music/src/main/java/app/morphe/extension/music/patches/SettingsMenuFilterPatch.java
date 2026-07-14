/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2029
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.patches.BaseSettingsMenuFilter;

@SuppressWarnings("unused")
public final class SettingsMenuFilterPatch extends BaseSettingsMenuFilter {

    private static final SettingsMenuFilterPatch INSTANCE = new SettingsMenuFilterPatch();

    private SettingsMenuFilterPatch() {
        super(Settings.SETTINGS_MENU_FILTER_STRINGS,
                Settings.SETTINGS_MENU_FILTER_DISCOVERED);
    }

    public static String[] getNeedles() {
        return INSTANCE.needles();
    }
}
