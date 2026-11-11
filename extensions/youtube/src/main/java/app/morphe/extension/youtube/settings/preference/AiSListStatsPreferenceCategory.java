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

import java.text.NumberFormat;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter.Source;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.BulletPointPreference;
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

    private static final Set<String> REFRESH_KEYS = Set.of(
            Settings.AISLIST_HIDE_COUNT_HOME.key,
            Settings.AISLIST_HIDE_COUNT_SEARCH.key,
            Settings.AISLIST_HIDES_24H.key,
            Settings.AISLIST_BLOCKLIST_CACHE.key,
            Settings.AISLIST_WARNLIST_CACHE.key,
            Settings.HIDE_AISLIST_BLOCKLIST_HOME.key,
            Settings.HIDE_AISLIST_BLOCKLIST_SEARCH.key,
            Settings.HIDE_AISLIST_WARNLIST_HOME.key,
            Settings.HIDE_AISLIST_WARNLIST_SEARCH.key
    );

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (prefs, key) -> {
                if (REFRESH_KEYS.contains(key)) {
                    Utils.runOnMainThread(this::refresh);
                }
            };

    private final NumberFormat nf = NumberFormat.getInstance(Settings.MORPHE_LANGUAGE.get().getLocale());

    static {
        // Fix bad imported data
        List.of(Settings.AISLIST_HIDE_COUNT_HOME, Settings.AISLIST_HIDE_COUNT_SEARCH).forEach(
                setting -> {
                    if (setting.get() < 0) {
                        setting.resetToDefault();
                    }
                }
        );
    }

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
        Logger.printDebug(() -> "refresh");
        removeAll();
        buildRows();
    }

    private void buildRows() {
        Context context = getContext();

        final String homeCount24h = nf.format(AiSListFilter.hidesInLast24Hours(Source.HOME));
        final String searchCount24h = nf.format(AiSListFilter.hidesInLast24Hours(Source.SEARCH));
        final String total24h = nf.format(AiSListFilter.hidesInLast24Hours());

        Preference hidden24h = new BulletPointPreference(context);
        hidden24h.setTitle(str("morphe_hide_stats_hidden_24h_title"));
        hidden24h.setSummary(str("morphe_hide_aislist_stats_hidden_breakdown",
                homeCount24h, searchCount24h, total24h));
        hidden24h.setOnPreferenceClickListener(pref -> {
            showResetDialog(
                    str("morphe_hide_stats_hidden_24h_reset_title"),
                    AiSListFilter::resetHidesTracker);
            return true;
        });
        addPreference(hidden24h);

        final String homeAll = nf.format(Settings.AISLIST_HIDE_COUNT_HOME.get());
        final String searchAll = nf.format(Settings.AISLIST_HIDE_COUNT_SEARCH.get());
        final String totalAll = nf.format(Settings.AISLIST_HIDE_COUNT_HOME.get()
                + Settings.AISLIST_HIDE_COUNT_SEARCH.get());

        Preference hiddenAllTime = new Preference(context);
        hiddenAllTime.setTitle(str("morphe_hide_stats_hidden_all_title"));
        hiddenAllTime.setSummary(str("morphe_hide_aislist_stats_hidden_breakdown",
                homeAll, searchAll, totalAll));
        hiddenAllTime.setOnPreferenceClickListener(pref -> {
            showResetDialog(
                    str("morphe_hide_stats_hidden_all_reset_title"),
                    () -> {
                        Settings.AISLIST_HIDE_COUNT_HOME.resetToDefault();
                        Settings.AISLIST_HIDE_COUNT_SEARCH.resetToDefault();
                        AiSListFilter.resetHidesTracker();
                    });
            return true;
        });
        addPreference(hiddenAllTime);

        Preference blocklistPref = new Preference(context);
        blocklistPref.setTitle(str("morphe_hide_aislist_stats_blocklist_channels_title"));
        blocklistPref.setSummary(channelsSummary(Settings.AISLIST_BLOCKLIST_CACHE.get()));
        blocklistPref.setSelectable(false);
        addPreference(blocklistPref);

        Preference warnlistPref = new Preference(context);
        warnlistPref.setTitle(str("morphe_hide_aislist_stats_warnlist_channels_title"));
        warnlistPref.setSummary(channelsSummary(Settings.AISLIST_WARNLIST_CACHE.get()));
        warnlistPref.setSelectable(false);
        addPreference(warnlistPref);
    }

    /**
     * The count is the source of truth when the cache is populated. Otherwise, the summary
     * explains the reason it is empty: no toggle is on (nothing has been requested) or the
     * fetch is still in flight after the first toggle flip.
     */
    private String channelsSummary(String cache) {
        if (cache != null && !cache.isBlank()) {
            return nf.format(countHandles(cache));
        }
        if (anyToggleEnabled()) {
            return str("morphe_hide_aislist_stats_channels_loading_summary");
        }
        return str("morphe_hide_aislist_stats_channels_disabled_summary");
    }

    private static boolean anyToggleEnabled() {
        return Settings.HIDE_AISLIST_BLOCKLIST_HOME.get()
                || Settings.HIDE_AISLIST_BLOCKLIST_SEARCH.get()
                || Settings.HIDE_AISLIST_WARNLIST_HOME.get()
                || Settings.HIDE_AISLIST_WARNLIST_SEARCH.get();
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
