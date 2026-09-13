/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.AppIconPickerPatch;

/**
 * Settings category for the custom app icon picker.
 * Appears in the Morphe settings screen for Reddit.
 * Only shown when the AppIconPickerPatch is included.
 */
@SuppressWarnings("deprecation")
public class AppIconPreferenceCategory extends ConditionalPreferenceCategory {

    public AppIconPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("App Icon");
    }

    @Override
    public boolean getSettingsStatus() {
        return AppIconPickerPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        Preference iconPicker = new Preference(context);
        iconPicker.setTitle("Change App Icon");
        iconPicker.setSummary("Choose a custom icon for the Reddit app");
        iconPicker.setOnPreferenceClickListener(pref -> {
            AppIconPickerPatch.showIconPicker(context);
            return true;
        });
        addPreference(iconPicker);
    }
}