/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2712
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 * https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/4881
 * https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/5806
 * https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/5838
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.settings.search;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.Toolbar;

import app.morphe.extension.music.settings.preference.MusicPreferenceFragment;
import app.morphe.extension.shared.settings.search.BaseSearchResultsAdapter;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;

/**
 * Music-specific search view controller implementation.
 */
@SuppressWarnings("deprecation")
public class MusicSearchViewController extends BaseSearchViewController {

    public static MusicSearchViewController addSearchViewComponents(Activity activity, Toolbar toolbar,
                                                                    MusicPreferenceFragment fragment) {
        return new MusicSearchViewController(activity, toolbar, fragment);
    }

    private MusicSearchViewController(Activity activity, Toolbar toolbar, MusicPreferenceFragment fragment) {
        super(activity, toolbar, new PreferenceFragmentAdapter(fragment));
    }

    @Override
    protected BaseSearchResultsAdapter createSearchResultsAdapter() {
        return new MusicSearchResultsAdapter(activity, filteredSearchItems, fragment, this);
    }

    @Override
    protected boolean isSpecialPreferenceGroup(Preference preference) {
        // Music doesn't have SponsorBlock, so no special groups.
        return false;
    }

    // Static method for handling Activity finish
    public static boolean handleFinish(MusicSearchViewController searchViewController) {
        if (searchViewController != null && searchViewController.isSearchActive()) {
            searchViewController.closeSearch();
            return true;
        }
        return false;
    }

    // Adapter to wrap MusicPreferenceFragment to BasePreferenceFragment interface.
    private record PreferenceFragmentAdapter(MusicPreferenceFragment fragment) implements BasePreferenceFragment {

        @Override
        public PreferenceScreen getPreferenceScreenForSearch() {
            return fragment.getPreferenceScreenForSearch();
        }

        @Override
        public View getView() {
            return fragment.getView();
        }

        @Override
        public Activity getActivity() {
            return fragment.getActivity();
        }
    }
}
