/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowInsets;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.Toolbar;

import androidx.annotation.Nullable;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.categories.AdsPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.FiltersPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.LayoutPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.MiscellaneousPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.NavigationBarPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.PostButtonsPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.SidebarPreferenceCategory;
import app.morphe.extension.reddit.settings.search.RedditSearchViewController;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;

/**
 * Preference fragment for Reddit Morphe settings.
 */
@SuppressWarnings("deprecation")
public class RedditPreferenceFragment extends AbstractPreferenceFragment
        implements BaseSearchViewController.BasePreferenceFragment {

    /**
     * Restyles the rows if the app uses the Compose settings.
     */
    @Nullable
    private ViewTreeObserver.OnPreDrawListener styleListener;

    @Nullable
    private RedditSearchViewController searchViewController;

    private final SharedPreferences.OnSharedPreferenceChangeListener glassSummaryListener =
            (sharedPreferences, key) -> {
                if (Settings.GLASS_NAVIGATION_BAR.key.equals(key)) {
                    final boolean enabled = sharedPreferences.getBoolean(
                            key, Settings.GLASS_NAVIGATION_BAR.defaultValue);
                    Utils.runOnMainThread(() -> updateGlassScreenSummary(enabled));
                }
            };

    @Override
    protected void initialize() {
        Context context = getContext();

        PreferenceScreen preferenceScreen = getPreferenceManager().createPreferenceScreen(context);
        setPreferenceScreen(preferenceScreen);

        // Custom categories reference app specific Settings class.
        new AdsPreferenceCategory(context, preferenceScreen);
        new FiltersPreferenceCategory(context, preferenceScreen);
        new NavigationBarPreferenceCategory(context, preferenceScreen);
        new SidebarPreferenceCategory(context, preferenceScreen);
        new PostButtonsPreferenceCategory(context, preferenceScreen);
        new LayoutPreferenceCategory(context, preferenceScreen);
        new MiscellaneousPreferenceCategory(context, preferenceScreen);

        if (RedditSettingsStyle.isModernStyle()) {
            setPreferenceScreenToolbar(preferenceScreen);
        }
    }

    public void setSearchViewController(RedditSearchViewController controller) {
        searchViewController = controller;
    }

    @Override
    public PreferenceScreen getPreferenceScreenForSearch() {
        return getPreferenceScreen();
    }

    @Override
    public void onStart() {
        super.onStart();
        try {
            if (RedditSettingsStyle.isModernStyle()) {
                getPreferenceManager().getSharedPreferences()
                        .registerOnSharedPreferenceChangeListener(glassSummaryListener);
                updateGlassScreenSummary();
            }
            if (searchViewController != null) {
                searchViewController.initializeSearchData();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onStart failure", ex);
        }
    }

    @Override
    public void onStop() {
        try {
            if (RedditSettingsStyle.isModernStyle()) {
                getPreferenceManager().getSharedPreferences()
                        .unregisterOnSharedPreferenceChangeListener(glassSummaryListener);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onStop failure", ex);
        }
        super.onStop();
    }

    private void updateGlassScreenSummary() {
        updateGlassScreenSummary(Settings.GLASS_NAVIGATION_BAR.get());
    }

    private void updateGlassScreenSummary(boolean enabled) {
        Preference glassScreen = findPreference(NavigationBarPreferenceCategory.GLASS_SCREEN_KEY);
        if (glassScreen != null) {
            NavigationBarPreferenceCategory.updateGlassScreenSummary(glassScreen, enabled);
        }
    }

    private void setPreferenceScreenToolbar(PreferenceGroup parentGroup) {
        for (int i = 0, count = parentGroup.getPreferenceCount(); i < count; i++) {
            Preference child = parentGroup.getPreference(i);
            if (child instanceof PreferenceGroup subGroup) {
                setPreferenceScreenToolbar(subGroup);
                if (child instanceof PreferenceScreen childScreen) {
                    childScreen.setOnPreferenceClickListener(preference -> {
                        decorateNestedScreenDialog((PreferenceScreen) preference);
                        return false;
                    });
                }
            }
        }
    }

    private void decorateNestedScreenDialog(PreferenceScreen screen) {
        try {
            Dialog dialog = screen.getDialog();
            if (dialog == null) return;

            RedditSettingsStyle.styleWindow(dialog.getWindow());

            View content = dialog.findViewById(android.R.id.content);
            if (content == null || !(content.getParent() instanceof ViewGroup rootView)) return;

            rootView.setBackgroundColor(RedditSettingsStyle.getBackgroundColor());
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                rootView.setOnApplyWindowInsetsListener((v, insets) -> {
                    Insets statusInsets = insets.getInsets(WindowInsets.Type.statusBars());
                    Insets navInsets = insets.getInsets(WindowInsets.Type.navigationBars());
                    Insets cutoutInsets = insets.getInsets(WindowInsets.Type.displayCutout());

                    v.setPadding(
                            cutoutInsets.left,
                            Math.max(statusInsets.top, cutoutInsets.top),
                            cutoutInsets.right,
                            navInsets.bottom
                    );
                    return insets;
                });
                rootView.requestApplyInsets();
            }

            CharSequence title = screen.getTitle();
            Toolbar toolbar = RedditSettingsStyle.createToolbar(
                    screen.getContext(),
                    title != null ? title : "",
                    dialog::dismiss
            );
            RedditSettingsStyle.applyToolbarElevation(toolbar);
            rootView.addView(toolbar, 0);

            ListView listView = dialog.findViewById(android.R.id.list);
            if (listView != null) {
                ViewTreeObserver.OnPreDrawListener preDrawListener = RedditSettingsStyle.styleList(listView);
                // PreferenceScreen owns the dialog's OnDismissListener, so detach cleans up the pre-draw listener.
                listView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                    @Override
                    public void onViewAttachedToWindow(View v) {}

                    @Override
                    public void onViewDetachedFromWindow(View v) {
                        v.getViewTreeObserver().removeOnPreDrawListener(preDrawListener);
                        v.removeOnAttachStateChangeListener(this);
                        try {
                            updateGlassScreenSummary(Settings.GLASS_NAVIGATION_BAR.get());
                            if (getPreferenceScreen().getRootAdapter() instanceof BaseAdapter adapter) {
                                adapter.notifyDataSetChanged();
                            }
                        } catch (Exception ex) {
                            Logger.printException(() -> "onViewDetachedFromWindow failure", ex);
                        }
                    }
                });
            }

            if (searchViewController != null && searchViewController.isSearchActive()) {
                toolbar.post(searchViewController::closeSearch);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "decorateNestedScreenDialog failure", ex);
        }
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
