/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceGroup;
import android.preference.PreferenceScreen;
import android.view.ContextThemeWrapper;

import app.morphe.extension.reddit.patches.DisableBottomBarAutoHidePatch;
import app.morphe.extension.reddit.patches.GlassNavigationBarPatch;
import app.morphe.extension.reddit.patches.HideNavigationButtonsPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.reddit.settings.preference.RedditSettingsStyle;
import app.morphe.extension.reddit.settings.preference.SeekBarSettingPreference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;

@SuppressWarnings("deprecation")
public class NavigationBarPreferenceCategory extends ConditionalPreferenceCategory {
    public static final String GLASS_SCREEN_KEY = "morphe_glass_navigation_bar_screen";

    public NavigationBarPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle(str("morphe_screen_navigation_bar_title"));
    }

    public static void updateGlassScreenSummary(Preference screen) {
        updateGlassScreenSummary(screen, Settings.GLASS_NAVIGATION_BAR.get());
    }

    public static void updateGlassScreenSummary(Preference screen, boolean enabled) {
        screen.setSummary(str(enabled
                ? "morphe_glass_navigation_bar_screen_summary_on"
                : "morphe_glass_navigation_bar_screen_summary_off"));
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
        final boolean modernStyle = RedditSettingsStyle.isModernStyle();
        PreferenceGroup mainGroup = this;
        PreferenceGroup lookGroup = this;
        PreferenceGroup sizeGroup = this;
        PreferenceGroup buttonsGroup = this;

        if (modernStyle) {
            // Sub-page dialog inherits its theme from the PreferenceScreen context rather than the main DialogFragment.
            Context screenContext = new ContextThemeWrapper(context, Utils.isDarkModeEnabled()
                    ? android.R.style.Theme_DeviceDefault_NoActionBar
                    : android.R.style.Theme_DeviceDefault_Light_NoActionBar);
            PreferenceScreen glassScreen = getPreferenceManager().createPreferenceScreen(screenContext);
            glassScreen.setKey(GLASS_SCREEN_KEY);
            glassScreen.setTitle(str("morphe_glass_navigation_bar_title"));
            updateGlassScreenSummary(glassScreen);
            addPreference(glassScreen);

            mainGroup = glassScreen;
        }

        mainGroup.addPreference(new BooleanSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR
        ));

        if (modernStyle) {
            PreferenceCategory lookCategory = new PreferenceCategory(context);
            lookCategory.setTitle(str("morphe_glass_navigation_bar_category_look"));
            mainGroup.addPreference(lookCategory);
            lookGroup = lookCategory;
        }

        lookGroup.addPreference(new CustomDialogListPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_COLOR
        ));
        lookGroup.addPreference(new CustomDialogListPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_STYLE
        ));
        if (modernStyle) {
            addGlassBlurPreference(context, lookGroup, true);
        }
        lookGroup.addPreference(new CustomDialogListPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_STRENGTH
        ));
        if (!modernStyle) {
            addGlassBlurPreference(context, lookGroup, false);
        }
        lookGroup.addPreference(new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_OPACITY
        ));
        lookGroup.addPreference(new BooleanSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_ROUND_ENDS
        ));

        if (modernStyle) {
            PreferenceCategory sizeCategory = new PreferenceCategory(context);
            sizeCategory.setTitle(str("morphe_glass_navigation_bar_category_size"));
            mainGroup.addPreference(sizeCategory);
            sizeGroup = sizeCategory;
        }

        sizeGroup.addPreference(new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_HEIGHT
        ));
        sizeGroup.addPreference(new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_WIDTH
        ));
        sizeGroup.addPreference(new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_POSITION
        ));

        if (modernStyle) {
            PreferenceCategory buttonsCategory = new PreferenceCategory(context);
            buttonsCategory.setTitle(str("morphe_glass_navigation_bar_category_buttons"));
            mainGroup.addPreference(buttonsCategory);
            buttonsGroup = buttonsCategory;
        }

        buttonsGroup.addPreference(new BooleanSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_SHOW_LABELS
        ));
        if (GlassNavigationBarPatch.isSearchButtonSupported()) {
            buttonsGroup.addPreference(new BooleanSettingPreference(
                    context,
                    Settings.GLASS_NAVIGATION_BAR_SEARCH_BUTTON
            ));
        }
    }

    private static void addGlassBlurPreference(Context context, PreferenceGroup group, boolean indent) {
        SeekBarSettingPreference blur = new SeekBarSettingPreference(
                context,
                Settings.GLASS_NAVIGATION_BAR_BLUR
        );
        if (indent) {
            RedditSettingsStyle.setIndented(blur);
        }
        group.addPreference(blur);
    }
}
