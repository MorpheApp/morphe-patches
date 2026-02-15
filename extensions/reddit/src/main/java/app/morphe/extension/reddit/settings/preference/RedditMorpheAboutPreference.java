package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.StringKeyLookup;

import android.content.Context;

import java.util.Map;

import app.morphe.extension.shared.settings.preference.MorpheAboutPreference;

@SuppressWarnings("deprecation")
public class RedditMorpheAboutPreference extends MorpheAboutPreference {
    private static final StringKeyLookup strings = new StringKeyLookup(
            Map.of("morphe_settings_about_links_body_version_current",
                    "You are using the latest Morphe Patches version <i>%s</i>",

                    "morphe_settings_about_links_body_version_outdated",
                    "You are using Morphe Patches version <i>%1$s</i><br><br><b>" +
                            "Update available: <i>%2$s</i></b><br><br>" +
                            "To update your patches, use Morphe to repatch this app",

                    "morphe_settings_about_links_dev_header",
                    "Note",

                    "morphe_settings_about_links_dev_body",
                    "This version is a pre-release and you may experience unexpected issues",

                    "morphe_settings_about_links_header",
                    "Official links"
            )
    );

    public RedditMorpheAboutPreference(Context context) {
        super(context);

        this.setTitle("About");
        this.setSummary("About Reddit Morphe");
    }

    protected String getString(String key, Object... args) {
        return strings.getString(key, args);
    }
}

