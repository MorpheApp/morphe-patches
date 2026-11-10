/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.preference.PreferenceScreen;
import android.widget.Toolbar;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.GmsCoreSupportPatch;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.settings.preference.NewSettingsIndicator;
import app.morphe.extension.shared.settings.preference.ToolbarPreferenceFragment;
import app.morphe.extension.youtube.patches.HideStatusBarPatch;
import app.morphe.extension.youtube.settings.YouTubeActivityHook;

/**
 * Preference fragment for Morphe settings.
 */
@SuppressWarnings("deprecation")
public class YouTubePreferenceFragment extends ToolbarPreferenceFragment {
    /**
     * The main PreferenceScreen used to display the current set of preferences.
     */
    private PreferenceScreen preferenceScreen;

    /**
     * Initializes the preference fragment.
     */
    @Override
    protected void initialize() {
        super.initialize();

        try {
            preferenceScreen = getPreferenceScreen();
            sortPreferenceGroups(preferenceScreen);
            setPreferenceScreenToolbar(preferenceScreen);

            // Clunky work around until preferences are custom classes that manage themselves.
            // A mount (root) install has its branding applied while patching and cannot change
            // it at runtime. But the preferences must be added during patching because of
            // difficulties detecting during patching if it's a root installation. So instead
            // the non-functional preferences are removed during runtime.
            if (GmsCoreSupportPatch.isPackageNameOriginal()) {
                removePreferences(
                        SharedYouTubeSettings.CUSTOM_BRANDING_ICON.key,
                        SharedYouTubeSettings.CUSTOM_BRANDING_NOTIFICATION_ICON.key,
                        SharedYouTubeSettings.CUSTOM_BRANDING_NAME.key);
            }

            NewSettingsIndicator.markNewPreferences(preferenceScreen);

            Activity activity = getActivity();
            Intent intent = activity.getIntent();
            String screenKey = intent.getStringExtra(YouTubeActivityHook.EXTRA_SCREEN_KEY);
            // Removed so recreating the activity, such as for a theme change, shows the main screen.
            intent.removeExtra(YouTubeActivityHook.EXTRA_SCREEN_KEY);
            if (screenKey != null && findPreference(screenKey) instanceof PreferenceScreen screen) {
                performClick(screen, preferenceScreen);
                // Opened from outside the settings, so closing the screen goes straight back.
                Dialog dialog = screen.getDialog();
                if (dialog != null) {
                    dialog.setOnDismissListener(d -> {
                        screen.onDismiss(d);
                        if (!activity.isFinishing()) {
                            YouTubeActivityHook.closeSettings(activity);
                        }
                    });
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * Called when the fragment starts.
     */
    @Override
    public void onStart() {
        super.onStart();
        try {
            // Initialize search controller if needed.
            if (YouTubeActivityHook.searchViewController != null) {
                // Trigger search data collection after fragment is ready.
                YouTubeActivityHook.searchViewController.initializeSearchData();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onStart failure", ex);
        }
    }

    /**
     * Sets toolbar for all nested preference screens.
     */
    @Override
    protected void customizeToolbar(Toolbar toolbar) {
        YouTubeActivityHook.setToolbarLayoutParams(toolbar);
    }

    /**
     * Perform actions after toolbar setup.
     */
    @Override
    protected void onPostToolbarSetup(Toolbar toolbar, Dialog preferenceScreenDialog) {
        HideStatusBarPatch.hideStatusBar(preferenceScreenDialog);

        if (YouTubeActivityHook.searchViewController != null
                && YouTubeActivityHook.searchViewController.isSearchActive()) {
            toolbar.post(() -> YouTubeActivityHook.searchViewController.closeSearch());
        }
    }

    /**
     * Returns the preference screen for external access by SearchViewController.
     */
    public PreferenceScreen getPreferenceScreenForSearch() {
        return preferenceScreen;
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == CustomSeekbarThumbPreference.PICK_THUMB_REQUEST_CODE) {
            CustomSeekbarThumbPreference.handleActivityResult(getContext(), resultCode, data);
        }
    }
}
