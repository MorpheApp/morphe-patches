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

import app.morphe.extension.youtube.patches.ghostplay.GhostHistoryDialog;
import app.morphe.extension.youtube.patches.ghostplay.GhostHistoryStore;

/**
 * Settings entry that opens the on-device Ghost mode history dialog and shows live usage.
 */
@SuppressWarnings({"unused", "deprecation"})
public class GhostHistoryPreference extends Preference implements Preference.OnPreferenceClickListener {

    {
        setSelectable(true);
        setOnPreferenceClickListener(this);
        updateSummary();
    }

    public GhostHistoryPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public GhostHistoryPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public GhostHistoryPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GhostHistoryPreference(Context context) {
        super(context);
    }

    private void updateSummary() {
        int count = GhostHistoryStore.count();
        if (count > 0) {
            String size = GhostHistoryStore.getFormattedSize();
            setSummary(str("morphe_ghost_play_view_history_summary_count", count, size));
        } else {
            setSummary(str("morphe_ghost_play_view_history_summary_empty"));
        }
    }

    @Override
    public boolean onPreferenceClick(Preference preference) {
        GhostHistoryDialog.showHistoryDialog(getContext(), this::updateSummary);
        return true;
    }
}
