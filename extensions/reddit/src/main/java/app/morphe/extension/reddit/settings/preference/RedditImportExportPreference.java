package app.morphe.extension.reddit.settings.preference;

import static app.morphe.extension.shared.StringRef.StringKeyLookup;

import android.content.Context;

import java.util.Map;

import app.morphe.extension.shared.settings.preference.ImportExportPreference;

public class RedditImportExportPreference extends ImportExportPreference {
    private static final StringKeyLookup strings = new StringKeyLookup(
            Map.of(
                    "morphe_pref_import_export_title",
                    "Import / Export",

                    "morphe_settings_import",
                    "Import",

                    "morphe_settings_import_copy",
                    "Copy")
    );

    public RedditImportExportPreference(Context context) {
        super(context);

        this.setTitle("Import / Export");
        this.setSummary("Import / Export Morphe settings.");
    }

    protected String getString(String key, Object... args) {
        return strings.getString(key, args);
    }
}
