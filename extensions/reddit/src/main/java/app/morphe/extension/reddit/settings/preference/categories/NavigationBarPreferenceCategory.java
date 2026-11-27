/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.DisableBottomBarAutoHidePatch;
import app.morphe.extension.reddit.patches.GlassNavigationBarPatch;
import app.morphe.extension.reddit.patches.HideNavigationButtonsPatch;
import app.morphe.extension.reddit.patches.HighContrastNavigationButtonsPatch;
import app.morphe.extension.reddit.patches.SubredditCreateButtonPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.reddit.settings.preference.RedditSettingsStyle;
import app.morphe.extension.reddit.settings.preference.SeekBarSettingPreference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;

@SuppressWarnings("deprecation")
public class NavigationBarPreferenceCategory extends ConditionalPreferenceCategory {
    /**
     * Updates the On/Off summary of the glass navigation bar page.
     * Assigned in {@link #addPreferences(Context)}, which runs in the super constructor before
     * field initializers, and kept here because SharedPreferences holds its listeners weakly.
     */
    private SharedPreferences.OnSharedPreferenceChangeListener glassPageSummaryListener;

    public NavigationBarPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen, "morphe_navigation_bar_screen",
                "morphe_navigation_bar_screen_title", "morphe_navigation_bar_screen_summary");
    }

    @Override
    public boolean getSettingsStatus() {
        return DisableBottomBarAutoHidePatch.isPatchIncluded() ||
                GlassNavigationBarPatch.isPatchIncluded() ||
                HideNavigationButtonsPatch.isPatchIncluded() ||
                HighContrastNavigationButtonsPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        if (DisableBottomBarAutoHidePatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.DISABLE_BOTTOM_BAR_AUTO_HIDE
            ));
        }

        if (HighContrastNavigationButtonsPatch.isPatchIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIGH_CONTRAST_NAVIGATION_BUTTONS
            ));
        }

        if (GlassNavigationBarPatch.isPatchIncluded()) {
            addGlassPreferences(context);
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
            if (SubredditCreateButtonPatch.isPatchIncluded()) {
                addIndentedPreference(this, new BooleanSettingPreference(
                        context,
                        Settings.SUBREDDIT_CREATE_BUTTON
                ));
            }
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

    private void addGlassPreferences(Context context) {
        // The modern style shows the settings on their own page, the legacy settings a flat list.
        final boolean modernStyle = RedditSettingsStyle.isModernStyle();
        PreferenceGroup page = this;

        if (modernStyle) {
            PreferenceScreen glassPage = RedditSettingsStyle.createSubPage(
                    getPreferenceManager(),
                    context,
                    "morphe_glass_navigation_bar_screen",
                    str("morphe_glass_navigation_bar_title"),
                    null
            );
            addPreference(glassPage);
            updateGlassPageSummary(glassPage);
            glassPageSummaryListener = (sharedPreferences, key) -> {
                if (Settings.GLASS_NAVIGATION_BAR.key.equals(key)) {
                    updateGlassPageSummary(glassPage);
                }
            };
            getSharedPreferences().registerOnSharedPreferenceChangeListener(glassPageSummaryListener);
            page = glassPage;
        }

        page.addPreference(new BooleanSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR
        ));

        PreferenceGroup look = addPageCategory(context, page, modernStyle,
                "morphe_glass_navigation_bar_category_look");
        look.addPreference(new CustomDialogListPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_COLOR
        ));
        look.addPreference(new CustomDialogListPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_STYLE
        ));
        addIndentedPreference(look, new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_BLUR
        ));
        look.addPreference(new CustomDialogListPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_STRENGTH
        ));
        look.addPreference(new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_OPACITY
        ));
        look.addPreference(new BooleanSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_ROUND_ENDS
        ));

        PreferenceGroup size = addPageCategory(context, page, modernStyle,
                "morphe_glass_navigation_bar_category_size");
        size.addPreference(new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_HEIGHT
        ));
        size.addPreference(new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_WIDTH
        ));
        size.addPreference(new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_POSITION
        ));

        PreferenceGroup buttons = addPageCategory(context, page, modernStyle,
                "morphe_glass_navigation_bar_category_buttons");
        buttons.addPreference(new BooleanSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_SHOW_LABELS
        ));
        if (GlassNavigationBarPatch.isSearchButtonSupported()) {
            buttons.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_SEARCH_BUTTON
            ));
            buttons.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_TOP_TABS
            ));
        }
    }

    /**
     * @return A new titled category on the page with the modern style, otherwise the page itself.
     */
    private static PreferenceGroup addPageCategory(Context context, PreferenceGroup page,
                                                   boolean modernStyle, String titleKey) {
        if (!modernStyle) return page;

        PreferenceCategory category = new PreferenceCategory(context);
        category.setTitle(str(titleKey));
        page.addPreference(category);
        return category;
    }

    private static void updateGlassPageSummary(Preference glassPage) {
        glassPage.setSummary(str(Settings.GLASS_NAVIGATION_BAR.get()
                ? "morphe_glass_navigation_bar_screen_summary_on"
                : "morphe_glass_navigation_bar_screen_summary_off"));
    }
}
