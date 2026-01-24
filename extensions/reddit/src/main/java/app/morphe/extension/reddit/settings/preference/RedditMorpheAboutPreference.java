package app.morphe.extension.reddit.settings.preference;

import android.content.Context;

import java.util.HashMap;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.MorpheAboutPreference;

public class RedditMorpheAboutPreference extends MorpheAboutPreference {
    private static final Map<String, String> stringMap = new HashMap<>();

    static {
        // Resource patching is not available due to apktool limitations.
        // Strings must be hard coded with no localization.
        stringMap.put("morphe_settings_about_links_body_version_current",
                "You are using the latest Morphe Patches version <i>%s</i>");
        stringMap.put("morphe_settings_about_links_body_version_outdated",
                "You are using Morphe Patches version <i>%1$s</i><br><br><b>" +
                        "Update available: <i>%2$s</i></b><br><br>" +
                        "To update your patches, use Morphe to repatch this app");
        stringMap.put("morphe_settings_about_links_dev_header",
                "Note");
        stringMap.put("morphe_settings_about_links_dev_body",
                "This version is a pre-release and you may experience unexpected issues");
        stringMap.put("morphe_settings_about_links_header",
                "Official links");
    }

    public RedditMorpheAboutPreference(Context context) {
        super(context);

        this.setTitle("About");
        this.setSummary("About Reddit Morphe.");
    }

    protected String getString(String key, Object... args) {
        String str = stringMap.get(key);
        if (str == null) {
            Logger.printException(() -> "Unknown string key: " + key);
            return key;
        }

        return String.format(str, args);
    }
}
