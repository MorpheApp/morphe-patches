/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.FlairFiltersPatch;
import app.morphe.extension.reddit.patches.KeywordFiltersPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.reddit.settings.preference.FlairFiltersPreference;
import app.morphe.extension.reddit.settings.preference.KeywordFiltersPreference;
import app.morphe.extension.reddit.settings.preference.SeenFlairsPreference;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("deprecation")
public class FiltersPreferenceCategory extends ConditionalPreferenceCategory {
    public FiltersPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_filters_title"));
    }

    @Override
    public boolean getSettingsStatus() {
        return FlairFiltersPatch.isPatchIncluded() ||
                KeywordFiltersPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        if (FlairFiltersPatch.isPatchIncluded()) {
            addPreference(new FlairFiltersPreference(context));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.FLAIR_FILTERS_REMEMBER_SEEN
            ));
            addPreference(new SeenFlairsPreference(context));
        }
        if (KeywordFiltersPatch.isPatchIncluded()) {
            addPreference(new KeywordFiltersPreference(context));
        }

        Utils.setPreferenceTitlesToMultiLine(this);
    }
}
