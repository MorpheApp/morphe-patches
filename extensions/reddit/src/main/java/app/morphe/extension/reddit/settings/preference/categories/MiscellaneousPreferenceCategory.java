/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.OpenLinksDirectlyPatch;
import app.morphe.extension.reddit.patches.OpenLinksExternallyPatch;
import app.morphe.extension.reddit.patches.SanitizeSharingLinksPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.ImportExportPreference;
import app.morphe.extension.shared.settings.preference.about.MorpheAboutPreference;
import app.morphe.extension.shared.settings.preference.SortedListPreference;

@SuppressWarnings("deprecation")
public class MiscellaneousPreferenceCategory extends ConditionalPreferenceCategory {
    public MiscellaneousPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_miscellaneous_title"));
    }

    @Override
    public boolean getSettingsStatus() {
        return OpenLinksDirectlyPatch.isPatchIncluded() ||
                OpenLinksExternallyPatch.isPatchIncluded() ||
                SanitizeSharingLinksPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        MorpheAboutPreference.showVancedAsPastContributor(false);
        Preference about = new MorpheAboutPreference(context);
        about.setTitle(str("morphe_about_title"));
        about.setSummary(str("morphe_about_summary"));
        addPreference(about);

        ImportExportPreference importPref = new ImportExportPreference(context);
        importPref.setTitle(str("morphe_pref_import_export_title"));
        importPref.setSummary(str("morphe_pref_import_export_summary"));
        addPreference(importPref);

        SortedListPreference language = new SortedListPreference(context, BaseSettings.MORPHE_LANGUAGE);
        addPreference(language);

        if (OpenLinksDirectlyPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.OPEN_LINKS_DIRECTLY
            ));
        }
        if (OpenLinksExternallyPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.OPEN_LINKS_EXTERNALLY
            ));
        }
        if (SanitizeSharingLinksPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SANITIZE_SHARING_LINKS
            ));
        }
    }
}
