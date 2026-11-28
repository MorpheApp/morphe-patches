/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5022
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.reddit.patches.HidePostButtonsPatch;
import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.reddit.settings.preference.BooleanSettingPreference;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("deprecation")
public class PostButtonsPreferenceCategory extends ConditionalPreferenceCategory {
    public PostButtonsPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen, "morphe_post_buttons_screen",
                "morphe_post_buttons_screen_title", "morphe_post_buttons_screen_summary");
    }

    @Override
    public boolean getSettingsStatus() {
        return HidePostButtonsPatch.isPatchIncluded();
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_POST_AWARD_BUTTON
        ));
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_POST_COMMENT_BUTTON
        ));
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_POST_JOIN_BUTTON
        ));
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_POST_MOD_BUTTON
        ));
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_POST_REPOST_BUTTON
        ));
        addPreference(new BooleanSettingPreference(
                context,
                Settings.HIDE_POST_SHARE_BUTTON
        ));
        if (HidePostButtonsPatch.isActionBarIncluded()) {
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_POST_VOTE_BUTTONS
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_POST_SHARE_COUNT
            ));
            addPreference(new BooleanSettingPreference(
                    context,
                    Settings.HIDE_POST_VOTE_COUNT
            ));
        }

        Utils.setPreferenceTitlesToMultiLine(this);
    }
}
