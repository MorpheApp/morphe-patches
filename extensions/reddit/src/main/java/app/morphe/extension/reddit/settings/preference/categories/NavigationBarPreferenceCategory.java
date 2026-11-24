/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.DisableBottomBarAutoHidePatch;
import app.morphe.extension.reddit.patches.GlassNavigationBarPatch;
import app.morphe.extension.reddit.patches.HideNavigationButtonsPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.reddit.settings.preference.SeekBarSettingPreference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;

@SuppressWarnings("deprecation")
public class NavigationBarPreferenceCategory extends ConditionalPreferenceCategory {
    public NavigationBarPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_navigation_bar_title"));
    }

    @Override
    public boolean getSettingsStatus() {
        return DisableBottomBarAutoHidePatch.isPatchIncluded() ||
                GlassNavigationBarPatch.isPatchIncluded() ||
                HideNavigationButtonsPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        if (DisableBottomBarAutoHidePatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_BOTTOM_BAR_AUTO_HIDE
            ));
        }

        if (GlassNavigationBarPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR
            ));
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_COLOR
            ));
            addPreference(new CustomDialogListPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_STYLE
            ));
            addPreference(new SeekBarSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_BLUR
            ));
            addPreference(new SeekBarSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_OPACITY
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_ROUND_ENDS
            ));
            addPreference(new SeekBarSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_HEIGHT
            ));
            addPreference(new SeekBarSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_WIDTH
            ));
        }

        if (HideNavigationButtonsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_ANSWERS_BUTTON
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_CHAT_BUTTON
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_CREATE_BUTTON
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_DISCOVER_BUTTON
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_GAMES_BUTTON
            ));
        }

        Utils.setPreferenceTitlesToMultiLine(this);
    }
}
