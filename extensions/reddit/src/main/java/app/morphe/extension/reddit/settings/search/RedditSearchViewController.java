/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.search;

import android.app.Activity;
import android.view.Window;
import android.widget.FrameLayout;
import android.widget.Toolbar;

import app.morphe.extension.reddit.settings.preference.RedditPreferenceFragment;
import app.morphe.extension.reddit.settings.preference.RedditSettingsStyle;
import app.morphe.extension.shared.settings.search.BaseSearchViewController;

/**
 * Search view controller of the Reddit settings dialog.
 */
public class RedditSearchViewController extends BaseSearchViewController {

    private final Runnable closeSettings;

    public RedditSearchViewController(Activity activity, Window window, Toolbar toolbar,
                                      FrameLayout searchContainer, FrameLayout overlayParent,
                                      RedditPreferenceFragment fragment, Runnable closeSettings) {
        super(activity, window, toolbar, searchContainer, overlayParent, fragment);
        this.closeSettings = closeSettings;

        if (RedditSettingsStyle.isModernStyle()) {
            overlayContainer.setBackgroundColor(RedditSettingsStyle.getBackgroundColor());
            // Same color as the back button.
            searchMenuItem.getIcon().setTint(RedditSettingsStyle.getTextColor());
        }
    }

    @Override
    protected void closeSettings() {
        closeSettings.run();
    }
}
