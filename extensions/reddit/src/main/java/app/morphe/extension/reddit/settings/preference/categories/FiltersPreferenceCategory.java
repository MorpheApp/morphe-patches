/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceCategory;
import android.preference.PreferenceScreen;
import app.morphe.extension.reddit.patches.ContentFilterPatch;
import static app.morphe.extension.shared.StringRef.str;

@SuppressWarnings("deprecation")
public final class FiltersPreferenceCategory extends PreferenceCategory {
    public FiltersPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context);
        if (ContentFilterPatch.registeredFilters().isEmpty()) return;
        setTitle(str("morphe_content_filters_title"));
        screen.addPreference(this);
        for (ContentFilterPatch.Filter filter : ContentFilterPatch.registeredFilters()) {
            filter.addPreferences(context, this);
        }
    }
}
