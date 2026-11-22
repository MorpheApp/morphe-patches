/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5152
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.settings.preference;

import android.content.Context;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;

/**
 * Choosing a panel width also forgets the width the panel was dragged to.
 */
@SuppressWarnings("deprecation")
public final class PostSidePanelWidthPreference extends CustomDialogListPreference {

    public PostSidePanelWidthPreference(Context context) {
        super(context, Settings.POST_SIDE_PANEL_WIDTH);

        setOnPreferenceChangeListener((preference, newValue) -> {
            Settings.POST_SIDE_PANEL_DRAGGED_WIDTH.resetToDefault();
            return true;
        });
    }
}
