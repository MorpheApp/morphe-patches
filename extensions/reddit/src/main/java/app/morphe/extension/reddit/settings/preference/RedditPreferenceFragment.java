/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference;

import android.content.Context;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.ListView;

import app.morphe.extension.reddit.settings.preference.categories.AdsPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.LayoutPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.MiscellaneousPreferenceCategory;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Preference fragment for Reddit Morphe settings.
 */
@SuppressWarnings("deprecation")
public class RedditPreferenceFragment extends AbstractPreferenceFragment {

    @Override
    protected void initialize() {
        Context context = getContext();

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(preferenceScreen);

        // Custom categories reference app specific Settings class.
        new AdsPreferenceCategory(context, preferenceScreen);
        new LayoutPreferenceCategory(context, preferenceScreen);
        new MiscellaneousPreferenceCategory(context, preferenceScreen);
    }

    @Override
    public void onResume() {
        super.onResume();

        View rootView = getView();
        if (rootView == null) return;

        ListView listView = rootView.findViewById(android.R.id.list);
        if (listView == null) return;

        // Hide divider.
        listView.setDivider(null);
        listView.setDividerHeight(0);
    }
}
