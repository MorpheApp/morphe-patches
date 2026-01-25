package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.reddit.patches.VersionCheckPatch.is_2025_52_or_greater;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.NavigationButtonsPatch;
import app.morphe.extension.reddit.patches.RecentlyVisitedShelfPatch;
import app.morphe.extension.reddit.patches.RecommendedCommunitiesPatch;
import app.morphe.extension.reddit.patches.RemoveSubRedditDialogPatch;
import app.morphe.extension.reddit.patches.ScreenshotPopupPatch;
import app.morphe.extension.reddit.patches.SidebarComponentsPatch;
import app.morphe.extension.reddit.patches.ToolBarButtonPatch;
import app.morphe.extension.reddit.patches.TrendingTodayShelfPatch;
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
        return ScreenshotPopupPatch.patchEnabled ||
                //NavigationButtonsPatch.patchEnabled || // FIXME
                SidebarComponentsPatch.patchEnabled ||
                RecentlyVisitedShelfPatch.patchEnabled ||
                RecommendedCommunitiesPatch.patchEnabled ||
                ToolBarButtonPatch.patchEnabled ||
                TrendingTodayShelfPatch.patchEnabled ||
                RemoveSubRedditDialogPatch.patchEnabled;
    }

    @Override
    public void addPreferences(Context context) {
        if (ScreenshotPopupPatch.patchEnabled) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_SCREENSHOT_POPUP,
                    "Disable screenshot popup",
                    "Disables the popup that appears when taking a screenshot."
            ));
        }

        // FIXME: Navigation buttons patch not yet supported.
        if (false && NavigationButtonsPatch.patchEnabled) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_CHAT_BUTTON,
                    "Hide Chat button",
                    "Hides the Chat button in the navigation bar."
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_CREATE_BUTTON,
                    "Hide Create button",
                    "Hides the Create button in the navigation bar."
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_DISCOVER_BUTTON,
                    "Hide Discover or Communities button",
                    "Hides the Discover or Communities button in the navigation bar."
            ));
        }

        if (SidebarComponentsPatch.patchEnabled) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_GAMES_ON_REDDIT_SHELF,
                    "Hide Games on Reddit shelf",
                    "Hides the Games on Reddit shelf in the sidebar."
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_REDDIT_PRO_SHELF,
                    "Hide Reddit Pro shelf",
                    "Hides the Reddit Pro shelf in the sidebar."
            ));

            if (is_2025_52_or_greater) {
                addPreference(new BooleanSettingPreference(
                        context,
                        Settings.HIDE_ABOUT_SHELF,
                        "Hide About shelf",
                        "Hides the About shelf in the sidebar."
                ));
                addPreference(new BooleanSettingPreference(
                        context,
                        Settings.HIDE_RESOURCES_SHELF,
                        "Hide Resources shelf",
                        "Hides the Resources shelf in the sidebar."
                ));
            }
        }

        if (RecentlyVisitedShelfPatch.patchEnabled) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_RECENTLY_VISITED_SHELF,
                    "Hide Recently Visited shelf",
                    "Hides the Recently Visited shelf in the sidebar."
            ));
        }

        if (RecommendedCommunitiesPatch.patchEnabled) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_RECOMMENDED_COMMUNITIES_SHELF,
                    "Hide recommended communities",
                    "Hides the recommended communities shelves in subreddits."
            ));
        }

        if (ToolBarButtonPatch.patchEnabled) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_TOOLBAR_BUTTON,
                    "Hide toolbar button",
                    "Hide toolbar button"
            ));
        }

        if (TrendingTodayShelfPatch.patchEnabled) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_TRENDING_TODAY_SHELF,
                    "Hide Trending Today shelf",
                    "Hides the Trending Today shelf from search suggestions.\n\nLimitation: Visual spacers are not hidden."
            ));
        }

        if (RemoveSubRedditDialogPatch.patchEnabled) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NSFW_DIALOG,
                    "Remove NSFW warning dialog",
                    "Removes the NSFW warning dialog that appears when visiting a subreddit by accepting it automatically."
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NOTIFICATION_DIALOG,
                    "Remove notification suggestion dialog",
                    "Removes the notifications suggestion dialog that appears when visiting a subreddit by dismissing it automatically."
            ));
        }
    }
}
