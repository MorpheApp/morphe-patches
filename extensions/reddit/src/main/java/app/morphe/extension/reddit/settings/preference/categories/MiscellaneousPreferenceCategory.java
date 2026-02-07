package app.morphe.extension.reddit.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.OpenLinksDirectlyPatch;
import app.morphe.extension.reddit.patches.OpenLinksExternallyPatch;
import app.morphe.extension.reddit.patches.SanitizeUrlQueryPatch;
import app.morphe.extension.reddit.settings.preference.RedditImportExportPreference;
import app.morphe.extension.reddit.settings.preference.RedditMorpheAboutPreference;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;

@SuppressWarnings("deprecation")
public class MiscellaneousPreferenceCategory extends ConditionalPreferenceCategory {
    public MiscellaneousPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Miscellaneous");
    }

    @Override
    public boolean getSettingsStatus() {
        return OpenLinksDirectlyPatch.isPatchIncluded() ||
                OpenLinksExternallyPatch.isPatchIncluded() ||
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
                    "Skips over redirection URLs in external links."
            ));
        }
        if (OpenLinksExternallyPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.OPEN_LINKS_EXTERNALLY,
                    "Open links externally",
                    "Opens links in your browser instead of in the in-app-browser."
            ));
        }
        if (SanitizeUrlQueryPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SANITIZE_URL_QUERY,
                    "Sanitize sharing links",
                    "Sanitizes sharing links by removing tracking query parameters."
            ));
        }
    }
}
