/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 */
package app.morphe.extension.reddit.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.OpenLinksDirectlyPatch;
import app.morphe.extension.reddit.patches.SanitizeUrlQueryPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.reddit.settings.preference.RedditImportExportPreference;
import app.morphe.extension.reddit.settings.preference.RedditMorpheAboutPreference;

@SuppressWarnings("deprecation")
public class MiscellaneousPreferenceCategory extends ConditionalPreferenceCategory {
    public MiscellaneousPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Miscellaneous");
    }

    @Override
    public boolean getSettingsStatus() {
        return OpenLinksDirectlyPatch.isPatchIncluded() ||
                SanitizeUrlQueryPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new RedditMorpheAboutPreference(getContext()));
        addPreference(new RedditImportExportPreference(getContext()));

        if (OpenLinksDirectlyPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.OPEN_LINKS_DIRECTLY,
                    "Open links directly",
                    "Skips over redirection URLs in external links"
            ));
        }
        if (SanitizeUrlQueryPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SANITIZE_URL_QUERY,
                    "Sanitize sharing links",
                    "Sanitizes sharing links by removing tracking query parameters"
            ));
        }
    }
}
