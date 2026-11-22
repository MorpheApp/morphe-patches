/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.youtube.patches.ghostplay.GhostHistoryStore;

/**
 * ListPreference for local Ghost history retention limit that dynamically displays
 * the current entry count, storage usage, and a sizing guide even when empty.
 */
@SuppressWarnings({"unused", "deprecation"})
public class GhostHistoryLimitPreference extends CustomDialogListPreference {

    public GhostHistoryLimitPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public GhostHistoryLimitPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public GhostHistoryLimitPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public GhostHistoryLimitPreference(Context context) {
        super(context);
    }

    @Override
    public void updateFromSetting() {
        super.updateFromSetting();
        refreshDynamicSummary();
    }

    @Override
    protected boolean persistString(String value) {
        boolean result = super.persistString(value);
        GhostHistoryStore.enforceLimit(GhostHistoryStore.parseLimit(value));
        refreshDynamicSummary();
        return result;
    }

    @Override
    public void setValue(String value) {
        super.setValue(value);
        refreshDynamicSummary();
    }

    private void refreshDynamicSummary() {
        CharSequence[] entries = getEntries();
        int idx = findIndexOfValue(getValue());
        String limitLabel = (entries != null && idx >= 0 && idx < entries.length)
                ? entries[idx].toString()
                : String.valueOf(GhostHistoryStore.getConfiguredLimit());

        int count = GhostHistoryStore.count();
        if (count > 0) {
            String sizeText = GhostHistoryStore.formatSize(GhostHistoryStore.getApproxSizeBytes());
            setSummary(str("morphe_ghost_play_history_limit_summary_active", limitLabel, count, sizeText));
        } else {
            setSummary(str("morphe_ghost_play_history_limit_summary_empty", limitLabel));
        }
    }
}
