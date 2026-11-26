/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.search;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.Toolbar;

import app.morphe.extension.reddit.settings.preference.RedditPreferenceFragment;
import app.morphe.extension.reddit.settings.preference.RedditSettingsStyle;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;

/**
 * Reddit-specific search view controller implementation.
 */
@SuppressWarnings("deprecation")
public class RedditSearchViewController extends BaseSearchViewController {

    private final Runnable closeSettingsAction;

    public static RedditSearchViewController addSearchViewComponents(Activity activity,
                                                                     Window window,
                                                                     Toolbar toolbar,
                                                                     FrameLayout overlayParent,
                                                                     RedditPreferenceFragment fragment,
                                                                     Runnable closeSettings) {
        return new RedditSearchViewController(activity, window, toolbar, overlayParent, fragment, closeSettings);
    }

    private RedditSearchViewController(Activity activity,
                                       Window window,
                                       Toolbar toolbar,
                                       FrameLayout overlayParent,
                                       RedditPreferenceFragment fragment,
                                       Runnable closeSettings) {
        super(activity, window, toolbar, overlayParent, new PreferenceFragmentAdapter(fragment));
        this.closeSettingsAction = closeSettings;

        if (RedditSettingsStyle.isModernStyle()) {
            overlayContainer.setBackgroundColor(RedditSettingsStyle.getBackgroundColor());
            Drawable searchIcon = searchMenuItem.getIcon();
            if (searchIcon != null) {
                searchIcon.setTint(RedditSettingsStyle.getTextColor());
            }
        }
    }

    @Override
    protected void closeSettings() {
        closeSettingsAction.run();
    }

    private record PreferenceFragmentAdapter(RedditPreferenceFragment fragment) implements BasePreferenceFragment {
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
