/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.settings;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.preference.PreferenceFragment;
import android.view.View;
import android.widget.Toolbar;

import app.morphe.extension.music.settings.preference.MusicPreferenceFragment;
import app.morphe.extension.music.settings.search.MusicSearchViewController;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Hooks {@code com.google.android.gms.common.api.GoogleApiActivity}
 * to inject a custom {@link MusicPreferenceFragment} with a toolbar and search.
 */
@SuppressWarnings({"deprecation", "RedundantSuppression"})
public class MusicActivityHook extends BaseActivityHook {

    private static final String GOOGLE_API_ACTIVITY_CLASS = "com.google.android.gms.common.api.GoogleApiActivity";

    private static final String YOUTUBE_MUSIC_SETTINGS_ACTIVITY_CLASS =
            "com.google.android.apps.youtube.music.settings.SettingsCompatActivity";
    private static final String YOUTUBE_MUSIC_SETTINGS_FRAGMENT_CLASS =
            "com.google.android.apps.youtube.music.settings.fragment.SettingsHeadersFragment";
    private static final String EXTRA_SHOW_FRAGMENT = ":android:show_fragment";
    private static final String EXTRA_NO_HEADERS = ":android:no_headers";

    @SuppressLint("StaticFieldLeak")
    public static MusicSearchViewController searchViewController;

    private static final boolean USE_BOLD_ICONS = Settings.SETTINGS_INITIALIZED.get()
            && !SpoofAppVersionPatch.isSpoofingToLessThan("8.40.00");

    static {
        Utils.setAppIsUsingBoldIcons(USE_BOLD_ICONS);
    }

    /**
     * Injection point.
     * <p>
     * Otherwise the dark mode status falls back to the device theme until the settings are
     * opened, and the extension draws with dark colors on the dark app.
     */
    @SuppressWarnings("unused")
    public static void onMainActivityCreate() {
        Utils.setIsDarkModeEnabled(true);
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("unused")
    public static void initialize(Activity parentActivity) {
        // Prevent opening multiple settings activities if menu is double tapped quickly.
        if (Utils.isFastClick()) {
            parentActivity.finish();
            return;
        }

        // Must touch the Music settings to ensure the settings class is loaded and
        // the values can be found when setting the UI preferences.
        // Logging anything under non debug ensures this is set.
        Logger.printInfo(() -> "Remember repeat state enabled: " + Settings.REMEMBER_REPEAT_STATE.get());

        // YT Music always uses dark mode.
        Utils.setIsDarkModeEnabled(true);

        BaseActivityHook.initialize(new MusicActivityHook(), parentActivity);
    }

    /**
     * Opens the Morphe settings.
     */
    public static void openSettings(Context context) {
        try {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setData(Uri.parse(MORPHE_SETTINGS_INTENT));
            intent.setClassName(context, GOOGLE_API_ACTIVITY_CLASS);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to open Morphe settings", ex);
        }
    }

    /**
     * Opens the YouTube Music settings, with the extras the app uses to open them.
     */
    public static void openYouTubeMusicSettings(Context context) {
        try {
            Intent intent = new Intent();
            intent.setClassName(context, YOUTUBE_MUSIC_SETTINGS_ACTIVITY_CLASS);
            intent.putExtra(EXTRA_SHOW_FRAGMENT, YOUTUBE_MUSIC_SETTINGS_FRAGMENT_CLASS);
            intent.putExtra(EXTRA_NO_HEADERS, true);
            context.startActivity(intent);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to open YouTube Music settings", ex);
        }
    }

    /**
     * Sets the fixed theme for the activity.
     */
    @Override
    protected void customizeActivityTheme(Activity activity) {
        // Override the default YouTube Music theme to increase start padding of list items.
        // Custom style located in resources/music/values/style.xml
        activity.setTheme(ResourceUtils.getIdentifierOrThrow(
                ResourceType.STYLE, "Theme.Morphe.YouTubeMusic.Settings"));
    }

    /**
     * Returns the fixed background color for the toolbar.
     */
    @Override
    protected int getToolbarBackgroundColor() {
        return ResourceUtils.getColor("ytm_color_black");
    }

    /**
     * Returns the navigation icon with a color filter applied.
     */
    @Override
    protected Drawable getNavigationIcon() {
        Drawable navigationIcon = MusicPreferenceFragment.getBackButtonDrawable();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            navigationIcon.setColorFilter(new BlendModeColorFilter(
                    ThemeUtils.getAppForegroundColor(), BlendMode.SRC_IN));
        } else {
            navigationIcon.setColorFilter(ThemeUtils.getAppForegroundColor(), PorterDuff.Mode.SRC_IN);
        }

        return navigationIcon;
    }

    /**
     * Returns the click listener that finishes the activity when the navigation icon is clicked.
     */
    @Override
    protected View.OnClickListener getNavigationClickListener(Activity activity) {
        return view -> {
            if (searchViewController != null && searchViewController.isSearchActive()) {
                searchViewController.closeSearch();
            } else {
                activity.finish();
            }
        };
    }

    /**
     * Adds search view components to the toolbar for {@link MusicPreferenceFragment}.
     *
     * @param activity The activity hosting the toolbar.
     * @param toolbar  The configured toolbar.
     * @param fragment The PreferenceFragment associated with the activity.
     */
    @Override
    protected void onPostToolbarSetup(Activity activity, Toolbar toolbar, PreferenceFragment fragment) {
        if (fragment instanceof MusicPreferenceFragment) {
            searchViewController = MusicSearchViewController.addSearchViewComponents(
                    activity, toolbar, (MusicPreferenceFragment) fragment);
        }
    }

    /**
     * Creates a new {@link MusicPreferenceFragment} for the activity.
     */
    @Override
    protected PreferenceFragment createPreferenceFragment() {
        return new MusicPreferenceFragment();
    }

    /**
     * Injection point.
     * <p>
     * Overrides {@link Activity#finish()} of the injection Activity.
     *
     * @return if the original activity finish method should be allowed to run.
     */
    @SuppressWarnings("unused")
    public static boolean handleFinish(Activity activity) {
        return MusicSearchViewController.handleFinish(searchViewController)
                || AbstractPreferenceFragment.showRestartDialogIfNeeded(activity);
    }

    /**
     * Injection point.
     */
    @SuppressWarnings("unused")
    public static boolean useBoldIcons(boolean original) {
        return USE_BOLD_ICONS;
    }
}
