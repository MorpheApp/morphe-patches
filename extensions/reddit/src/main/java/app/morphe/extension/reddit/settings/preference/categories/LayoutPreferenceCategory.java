package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.reddit.patches.VersionCheckPatch.is_2025_52_or_greater;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.DisableScreenshotPopupPatch;
import app.morphe.extension.reddit.patches.HideNavigationButtonsPatch;
import app.morphe.extension.reddit.patches.HideRecommendedCommunitiesShelf;
import app.morphe.extension.reddit.patches.HideSidebarComponentsPatch;
import app.morphe.extension.reddit.patches.HideTrendingTodayShelfPatch;
import app.morphe.extension.reddit.patches.RemoveSubRedditDialogPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;

@SuppressWarnings("deprecation")
public class LayoutPreferenceCategory extends ConditionalPreferenceCategory {
    public LayoutPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Layout");
    }

    @Override
    public boolean getSettingsStatus() {
        return DisableScreenshotPopupPatch.isPatchIncluded() ||
                HideNavigationButtonsPatch.isPatchIncluded() ||
                HideSidebarComponentsPatch.isPatchIncluded() ||
                HideRecommendedCommunitiesShelf.isPatchIncluded() ||
                HideTrendingTodayShelfPatch.isPatchIncluded() ||
                RemoveSubRedditDialogPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        if (DisableScreenshotPopupPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_SCREENSHOT_POPUP,
                    "Disable screenshot popup",
                    "Disables the popup that appears when taking a screenshot"
            ));
        }

        if (HideNavigationButtonsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_ANSWERS_BUTTON,
                    "Hide Answers button",
                    "Hides the Answers button in the navigation bar"
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_CHAT_BUTTON,
                    "Hide Chat button",
                    "Hides the Chat button in the navigation bar"
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_CREATE_BUTTON,
                    "Hide Create button",
                    "Hides the Create button in the navigation bar"
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_DISCOVER_BUTTON,
                    "Hide Discover or Communities button",
                    "Hides the Discover or Communities button in the navigation bar"
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_GAMES_BUTTON,
                    "Hide Games button",
                    "Hides the Games button in the navigation bar"
            ));
        }

        if (HideSidebarComponentsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_RECENTLY_VISITED_SHELF,
                    "Hide Recently Visited shelf",
                    "Hides the Recently Visited shelf in the sidebar"
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_GAMES_ON_REDDIT_SHELF,
                    "Hide Games on Reddit shelf",
                    "Hides the Games on Reddit shelf in the sidebar"
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_REDDIT_PRO_SHELF,
                    "Hide Reddit Pro shelf",
                    "Hides the Reddit Pro shelf in the sidebar"
            ));

            if (is_2025_52_or_greater) {
                addPreference(new BooleanSettingPreference(
                        context,
                        Settings.HIDE_ABOUT_SHELF,
                        "Hide About shelf",
                        "Hides the About shelf in the sidebar"
                ));
                addPreference(new BooleanSettingPreference(
                        context,
                        Settings.HIDE_RESOURCES_SHELF,
                        "Hide Resources shelf",
                        "Hides the Resources shelf in the sidebar"
                ));
            }
        }

        if (HideRecommendedCommunitiesShelf.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_RECOMMENDED_COMMUNITIES_SHELF,
                    "Hide recommended communities",
                    "Hides the recommended communities shelves in subreddits"
            ));
        }

        if (HideTrendingTodayShelfPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_TRENDING_TODAY_SHELF,
                    "Hide Trending Today shelf",
                    "Hides the Trending Today shelf from search suggestions\n\nLimitation: Visual spacers may not be hidden"
            ));
        }

        if (RemoveSubRedditDialogPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NSFW_DIALOG,
                    "Remove NSFW warning dialog",
                    "Removes the NSFW warning dialog that appears when visiting a subreddit by accepting it automatically"
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NOTIFICATION_DIALOG,
                    "Remove notification suggestion dialog",
                    "Removes the notifications suggestion dialog that appears when visiting a subreddit by dismissing it automatically"
            ));
        }
    }
}
