/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ListView;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.preference.categories.AdsPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.LayoutPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.MiscellaneousPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.NavigationBarPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.SidebarPreferenceCategory;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/**
 * Preference fragment for Reddit Morphe settings.
 */
@SuppressWarnings("deprecation")
public class RedditPreferenceFragment extends AbstractPreferenceFragment {

    /**
     * Restyles the rows if the app uses the Compose settings.
     */
    @Nullable
    private ViewTreeObserver.OnPreDrawListener styleListener;

    @Override
    protected void initialize() {
        Context context = getContext();

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(preferenceScreen);

        // Custom categories reference app specific Settings class.
        new AdsPreferenceCategory(context, preferenceScreen);
        new NavigationBarPreferenceCategory(context, preferenceScreen);
        new SidebarPreferenceCategory(context, preferenceScreen);
        new LayoutPreferenceCategory(context, preferenceScreen);
        new MiscellaneousPreferenceCategory(context, preferenceScreen);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        if (RedditSettingsStyle.isModernStyle()) {
            ListView listView = view.findViewById(android.R.id.list);
            if (listView != null) {
                styleListener = RedditSettingsStyle.styleList(listView);
            }
        }
    }

    @Override
    public void onDestroyView() {
        View rootView = getView();
        if (styleListener != null && rootView != null) {
            rootView.getViewTreeObserver().removeOnPreDrawListener(styleListener);
        }
        styleListener = null;
        super.onDestroyView();
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

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == CustomFontFilePreference.READ_FONT_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            CustomFontFilePreference.handleActivityResult(getContext(), resultCode, data);
        } else if (requestCode == DownloadLocationPreference.PICK_FOLDER_REQUEST_CODE) {
            DownloadLocationPreference.handleActivityResult(resultCode, data);
        }
    }
}
