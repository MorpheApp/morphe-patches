/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.settings.search;

import android.app.Activity;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.view.View;
import android.widget.Toolbar;

import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.preference.NewSettingsIndicator;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategoryPreference;

/**
 * Shared search view controller base class for YouTube and YouTube Music.
 */
@SuppressWarnings("deprecation")
public abstract class BaseYouTubeSearchViewController extends BaseSearchViewController {

    protected BaseYouTubeSearchViewController(Activity activity, Toolbar toolbar, BasePreferenceFragment fragment) {
        super(activity, toolbar, fragment);
    }

    @Override
    protected void closeSettings() {
        BaseActivityHook.closeSettings(activity);
    }

    @Override
    protected void onSwitchToggled(SwitchPreference preference) {
        NewSettingsIndicator.markSeen(preference);
    }

    @Override
    protected void bindColorDot(View colorDot, Preference preference) {
        SegmentCategoryPreference.bindColorTarget(colorDot,
                preference instanceof SegmentCategoryPreference categoryPref ? categoryPref : null);
    }
}
