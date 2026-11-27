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
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
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

import app.morphe.extension.reddit.settings.preference.categories.AdsPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.FiltersPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.LayoutPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.MiscellaneousPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.NavigationBarPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.PostButtonsPreferenceCategory;
import app.morphe.extension.reddit.settings.preference.categories.SidebarPreferenceCategory;
import app.morphe.extension.reddit.settings.search.RedditSearchViewController;
import app.morphe.extension.shared.Logger;
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
            addSubPageToolbars(preferenceScreen);
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
        if (searchViewController != null) {
            searchViewController.initializeSearchData();
        }
    }

    /**
     * Styles the dialog of every sub-page when it opens, and adds a toolbar to it.
     */
    private void addSubPageToolbars(PreferenceGroup group) {
        for (int i = 0, count = group.getPreferenceCount(); i < count; i++) {
            if (group.getPreference(i) instanceof PreferenceGroup subGroup) {
                addSubPageToolbars(subGroup);
                if (subGroup instanceof PreferenceScreen subPage) {
                    // Runs after the dialog of the page is shown.
                    subPage.setOnPreferenceClickListener(preference -> {
                        decorateSubPageDialog(subPage);
                        return false;
                    });
                }
            }
        }
    }

    private void decorateSubPageDialog(PreferenceScreen subPage) {
        try {
            Dialog dialog = subPage.getDialog();
            RedditSettingsStyle.styleWindow(dialog.getWindow());

            ViewGroup rootView = (ViewGroup) dialog.findViewById(android.R.id.content).getParent();
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

            Toolbar toolbar = RedditSettingsStyle.createToolbar(
                    subPage.getContext(), subPage.getTitle(), dialog::dismiss);
            RedditSettingsStyle.applyToolbarElevation(toolbar);
            rootView.addView(toolbar, 0);

            // The list view and its pre-draw listener go away with the dialog.
            RedditSettingsStyle.styleList(dialog.findViewById(android.R.id.list));

            // Rows of any open screen that change while a sub-page covers it, such as a
            // nested page summary, are not redrawn until its adapter is notified.
            dialog.setOnDismissListener(dialogInterface -> {
                subPage.onDismiss(dialogInterface);
                PreferenceScreen rootScreen = getPreferenceScreen();
                if (rootScreen != null) {
                    notifyOpenAdapters(rootScreen);
                }
            });

            // Search navigates to a setting of a sub-page by opening the page.
            if (searchViewController != null && searchViewController.isSearchActive()) {
                toolbar.post(searchViewController::closeSearch);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "decorateSubPageDialog failure", ex);
        }
    }

    private static void notifyOpenAdapters(PreferenceScreen rootScreen) {
        if (rootScreen.getRootAdapter() instanceof BaseAdapter adapter) {
            adapter.notifyDataSetChanged();
        }
        notifyOpenSubPageAdapters(rootScreen);
    }

    private static void notifyOpenSubPageAdapters(PreferenceGroup group) {
        for (int i = 0, count = group.getPreferenceCount(); i < count; i++) {
            if (group.getPreference(i) instanceof PreferenceGroup subGroup) {
                if (subGroup instanceof PreferenceScreen subPage) {
                    Dialog dialog = subPage.getDialog();
                    if (dialog != null && dialog.isShowing()
                            && subPage.getRootAdapter() instanceof BaseAdapter adapter) {
                        adapter.notifyDataSetChanged();
                    }
                }
                notifyOpenSubPageAdapters(subGroup);
            }
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
