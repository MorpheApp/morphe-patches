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

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.settings.BaseActivityHook;
import app.morphe.extension.shared.settings.preference.NewSettingsIndicator;
import app.morphe.extension.shared.sponsorblock.objects.SegmentCategoryPreference;

/**
 * Search view controller of the YouTube and YouTube Music settings activity.
 */
@SuppressWarnings("deprecation")
public abstract class BaseYouTubeSearchViewController extends BaseSearchViewController {

    private static final int ID_MORPHE_SEARCH_VIEW_CONTAINER = ResourceUtils.getIdentifierOrThrow(
            ResourceType.ID, "morphe_search_view_container");
    private static final int ID_MORPHE_SETTINGS_FRAGMENTS = ResourceUtils.getIdentifierOrThrow(
            ResourceType.ID, "morphe_settings_fragments");

    protected BaseYouTubeSearchViewController(Activity activity, Toolbar toolbar, BasePreferenceFragment fragment) {
        super(activity, activity.getWindow(), toolbar,
                activity.findViewById(ID_MORPHE_SEARCH_VIEW_CONTAINER),
                activity.findViewById(ID_MORPHE_SETTINGS_FRAGMENTS),
                fragment);
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
