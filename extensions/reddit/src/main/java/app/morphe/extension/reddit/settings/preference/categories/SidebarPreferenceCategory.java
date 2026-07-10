/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.reddit.patches.VersionCheckPatch.is_2025_52_or_greater;
import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.HideSidebarComponentsPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;

@SuppressWarnings("deprecation")
public class SidebarPreferenceCategory extends ConditionalPreferenceCategory {
    public SidebarPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_sidebar_title"));
    }

    @Override
    public boolean getSettingsStatus() {
        return HideSidebarComponentsPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        if (HideSidebarComponentsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_RECENTLY_VISITED_SHELF
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_GAMES_ON_REDDIT_SHELF
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_REDDIT_PRO_SHELF
            ));

            if (is_2025_52_or_greater) {
                addPreference(new BooleanSettingPreference(
                        context,
                        Settings.HIDE_ABOUT_SHELF
                ));
                addPreference(new BooleanSettingPreference(
                        context,
                        Settings.HIDE_RESOURCES_SHELF
                ));
            }
        }

    }
}
