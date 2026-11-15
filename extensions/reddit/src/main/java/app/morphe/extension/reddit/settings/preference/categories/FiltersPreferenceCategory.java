/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4867
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.KeywordFiltersPatch;
import app.morphe.extension.reddit.settings.preference.KeywordFiltersPreference;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("deprecation")
public class FiltersPreferenceCategory extends ConditionalPreferenceCategory {
    public FiltersPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_filters_title"));
    }

    @Override
    public boolean getSettingsStatus() {
        return KeywordFiltersPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new KeywordFiltersPreference(context));

        Utils.setPreferenceTitlesToMultiLine(this);
    }
}
