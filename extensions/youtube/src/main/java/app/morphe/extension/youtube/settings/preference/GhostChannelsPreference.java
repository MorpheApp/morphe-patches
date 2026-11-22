/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

import app.morphe.extension.youtube.patches.ghostplay.GhostChannelManagerDialog;
import app.morphe.extension.youtube.patches.ghostplay.GhostChannelStore;

/**
 * Settings entry that opens the searchable Auto-Ghost channel manager dialog.
 */
@SuppressWarnings({"unused", "deprecation"})
public class GhostChannelsPreference extends Preference implements Preference.OnPreferenceClickListener {

    {
        setSelectable(true);
        setOnPreferenceClickListener(this);
        updateSummary();
    }

    public GhostChannelsPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public GhostChannelsPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public GhostChannelsPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GhostChannelsPreference(Context context) {
        super(context);
    }

    private void updateSummary() {
        int count = GhostChannelStore.count();
        setSummary(str("morphe_ghost_play_manage_channels_summary_count", count));
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        GhostChannelManagerDialog.show(getContext(), this::updateSummary);
        return true;
    }
}
