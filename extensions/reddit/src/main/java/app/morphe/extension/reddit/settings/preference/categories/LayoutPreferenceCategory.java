/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.DisableModernHomePatch;
import app.morphe.extension.reddit.patches.DisableScreenshotPopupPatch;
import app.morphe.extension.reddit.patches.HideAskButtonPatch;
import app.morphe.extension.reddit.patches.HideRecommendedCommunitiesShelf;
import app.morphe.extension.reddit.patches.HideTrendingTodayShelfPatch;
import app.morphe.extension.reddit.patches.RemoveSubRedditDialogPatch;
import app.morphe.extension.reddit.patches.ShowViewCountPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;

@SuppressWarnings("deprecation")
public class LayoutPreferenceCategory extends ConditionalPreferenceCategory {
    public LayoutPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_layout_title"));
    }

    @Override
    public boolean getSettingsStatus() {
        return DisableModernHomePatch.isPatchIncluded() ||
                DisableScreenshotPopupPatch.isPatchIncluded() ||
                HideAskButtonPatch.isPatchIncluded() ||
                HideRecommendedCommunitiesShelf.isPatchIncluded() ||
                HideTrendingTodayShelfPatch.isPatchIncluded() ||
                RemoveSubRedditDialogPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        if (DisableModernHomePatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_MODERN_HOME
            ));
        }

        if (DisableScreenshotPopupPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_SCREENSHOT_POPUP
            ));
        }

        if (HideAskButtonPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_ASK_BUTTON
            ));
        }

        if (HideRecommendedCommunitiesShelf.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_RECOMMENDED_COMMUNITIES_SHELF
            ));
        }

        if (HideTrendingTodayShelfPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_TRENDING_TODAY_SHELF
            ));
        }

        if (RemoveSubRedditDialogPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NSFW_DIALOG
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.REMOVE_NOTIFICATION_DIALOG
            ));
        }

        if (ShowViewCountPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.SHOW_VIEW_COUNT
            ));
        }
    }
}
