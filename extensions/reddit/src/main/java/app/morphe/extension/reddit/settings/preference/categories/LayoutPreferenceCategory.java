package app.morphe.extension.reddit.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.SettingsStatus;
import app.morphe.extension.reddit.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class LayoutPreferenceCategory extends ConditionalPreferenceCategory {
    public LayoutPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Layout");
    }

    @Override
    public boolean getSettingsStatus() {
        return SettingsStatus.layoutCategoryEnabled();
    }

    @Override
    public void addPreferences(Context context) {
        if (SettingsStatus.screenshotPopupEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Disable screenshot popup",
                    "Disables the popup that appears when taking a screenshot.",
                    Settings.DISABLE_SCREENSHOT_POPUP
            ));
        }
        if (SettingsStatus.navigationButtonsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide Chat button",
                    "Hides the Chat button in the navigation bar.",
                    Settings.HIDE_CHAT_BUTTON
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide Create button",
                    "Hides the Create button in the navigation bar.",
                    Settings.HIDE_CREATE_BUTTON
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide Discover or Communities button",
                    "Hides the Discover or Communities button in the navigation bar.",
                    Settings.HIDE_DISCOVER_BUTTON
            ));
        }
        if (SettingsStatus.aboutShelfEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide About shelf",
                    "Hides the About shelf in the sidebar.",
                    Settings.HIDE_ABOUT_SHELF
            ));
        }
        if (SettingsStatus.gamesOnRedditShelfEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide Games on Reddit shelf",
                    "Hides the Games on Reddit shelf in the sidebar.",
                    Settings.HIDE_GAMES_ON_REDDIT_SHELF
            ));
        }
        if (SettingsStatus.recentlyVisitedShelfEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide Recently Visited shelf",
                    "Hides the Recently Visited shelf in the sidebar.",
                    Settings.HIDE_RECENTLY_VISITED_SHELF
            ));
        }
        if (SettingsStatus.resourcesShelfEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide Resources shelf",
                    "Hides the Resources shelf in the sidebar.",
                    Settings.HIDE_RESOURCES_SHELF
            ));
        }
        if (SettingsStatus.redditProShelfEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide Reddit Pro shelf",
                    "Hides the Reddit Pro shelf in the sidebar.",
                    Settings.HIDE_REDDIT_PRO_SHELF
            ));
        }
        if (SettingsStatus.recommendedCommunitiesShelfEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide recommended communities",
                    "Hides the recommended communities shelves in subreddits.",
                    Settings.HIDE_RECOMMENDED_COMMUNITIES_SHELF
            ));
        }
        if (SettingsStatus.toolBarButtonEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide toolbar button",
                    "Hide toolbar button",
                    Settings.HIDE_TOOLBAR_BUTTON
            ));
        }
        if (SettingsStatus.trendingTodayShelfEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide Trending Today shelf",
                    "Hides the Trending Today shelf from search suggestions.\n\nLimitation: Visual spacers are not hidden.",
                    Settings.HIDE_TRENDING_TODAY_SHELF
            ));
        }
        if (SettingsStatus.subRedditDialogEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Remove NSFW warning dialog",
                    "Removes the NSFW warning dialog that appears when visiting a subreddit by accepting it automatically.",
                    Settings.REMOVE_NSFW_DIALOG
            ));
            addPreference(new TogglePreference(
                    context,
                    "Remove notification suggestion dialog",
                    "Removes the notifications suggestion dialog that appears when visiting a subreddit by dismissing it automatically.",
                    Settings.REMOVE_NOTIFICATION_DIALOG
            ));
        }
    }
}
