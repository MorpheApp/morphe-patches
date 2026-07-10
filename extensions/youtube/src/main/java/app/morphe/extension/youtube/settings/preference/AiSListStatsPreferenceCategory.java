/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceManager;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter.Source;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.components.AiSListFilter;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Populated at runtime with four rows:
 *   • videos hidden in the last 24 hours (total + per-source breakdown; tap resets the 24h tracker)
 *   • videos hidden all time (total + per-source breakdown; tap resets counters and 24h tracker)
 *   • blocklist channels loaded
 *   • warnlist channels loaded
 */
@SuppressWarnings({"deprecation", "unused"})
public class AiSListStatsPreferenceCategory extends PreferenceCategory {

    private final SharedPreferences.OnSharedPreferenceChangeListener listener = (prefs, key) -> {
        if (Settings.AISLIST_HIDE_COUNT_HOME.key.equals(key)
                || Settings.AISLIST_HIDE_COUNT_SEARCH.key.equals(key)
                || Settings.AISLIST_HIDE_COUNT_COMMENTS.key.equals(key)
                || Settings.AISLIST_HIDES_24H.key.equals(key)
                || Settings.AISLIST_BLOCKLIST_CACHE.key.equals(key)
                || Settings.AISLIST_WARNLIST_CACHE.key.equals(key)) {
            Utils.runOnMainThread(this::refresh);
        }
    };

    public AiSListStatsPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public AiSListStatsPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public AiSListStatsPreferenceCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public AiSListStatsPreferenceCategory(Context context) {
        super(context);
    }

    @Override
    protected void onAttachedToHierarchy(PreferenceManager preferenceManager) {
        super.onAttachedToHierarchy(preferenceManager);
        buildRows();
        Setting.preferences.preferences.registerOnSharedPreferenceChangeListener(listener);
    }

    @Override
    protected void onPrepareForRemoval() {
        super.onPrepareForRemoval();
        Setting.preferences.preferences.unregisterOnSharedPreferenceChangeListener(listener);
    }

    private void refresh() {
        removeAll();
        buildRows();
    }

    private void buildRows() {
        Context context = getContext();

        int homeCount24h = AiSListFilter.hidesInLast24Hours(Source.HOME);
        int searchCount24h = AiSListFilter.hidesInLast24Hours(Source.SEARCH);
        int commentsCount24h = AiSListFilter.hidesInLast24Hours(Source.COMMENTS);
        int total24h = AiSListFilter.hidesInLast24Hours();

        Preference hidden24h = new Preference(context);
        hidden24h.setTitle(str("morphe_hide_aislist_stats_hidden_24h_title", total24h));
        hidden24h.setSummary(str("morphe_hide_aislist_stats_hidden_breakdown",
                homeCount24h, searchCount24h, commentsCount24h));
        hidden24h.setOnPreferenceClickListener(pref -> {
            showResetDialog(
                    str("morphe_hide_aislist_stats_hidden_24h_reset_title"),
                    AiSListFilter::resetHidesTracker);
            return true;
        });
        addPreference(hidden24h);

        long homeAll = Settings.AISLIST_HIDE_COUNT_HOME.get();
        long searchAll = Settings.AISLIST_HIDE_COUNT_SEARCH.get();
        long commentsAll = Settings.AISLIST_HIDE_COUNT_COMMENTS.get();
        long totalAll = homeAll + searchAll + commentsAll;

        Preference hiddenAllTime = new Preference(context);
        hiddenAllTime.setTitle(str("morphe_hide_aislist_stats_hidden_all_title", totalAll));
        hiddenAllTime.setSummary(str("morphe_hide_aislist_stats_hidden_breakdown",
                homeAll, searchAll, commentsAll));
        hiddenAllTime.setOnPreferenceClickListener(pref -> {
            showResetDialog(
                    str("morphe_hide_aislist_stats_hidden_all_reset_title"),
                    () -> {
                        Settings.AISLIST_HIDE_COUNT_HOME.resetToDefault();
                        Settings.AISLIST_HIDE_COUNT_SEARCH.resetToDefault();
                        Settings.AISLIST_HIDE_COUNT_COMMENTS.resetToDefault();
                        AiSListFilter.resetHidesTracker();
                    });
            return true;
        });
        addPreference(hiddenAllTime);

        Preference blocklistPref = new Preference(context);
        blocklistPref.setTitle(str("morphe_hide_aislist_stats_blocklist_title",
                countHandles(Settings.AISLIST_BLOCKLIST_CACHE.get())));
        blocklistPref.setSelectable(false);
        addPreference(blocklistPref);

        Preference warnlistPref = new Preference(context);
        warnlistPref.setTitle(str("morphe_hide_aislist_stats_warnlist_title",
                countHandles(Settings.AISLIST_WARNLIST_CACHE.get())));
        warnlistPref.setSelectable(false);
        addPreference(warnlistPref);
    }

    private void showResetDialog(String title, Runnable onConfirm) {
        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                getContext(),
                title,
                null,
                null,
                null,
                onConfirm,
                () -> {},
                null,
                null,
                true
        );
        dialogPair.first.show();
    }

    private static int countHandles(String raw) {
        if (raw == null || raw.isBlank()) return 0;
        int count = 0;
        for (String line : raw.split("\n")) {
            if (!line.isEmpty() && line.charAt(0) == '@') count++;
        }
        return count;
    }
}
