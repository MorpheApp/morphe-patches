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
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter.Source;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.youtube.patches.components.KeywordContentFilter;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Populated at runtime with two rows for keyword filter stats:
 *   • videos hidden in the last 24 hours (total + 4-source breakdown; tap resets the 24h tracker)
 *   • videos hidden all time (total + 4-source breakdown; tap resets counters and 24h tracker)
 */
@SuppressWarnings({"deprecation", "unused"})
public class KeywordContentStatsPreferenceCategory extends PreferenceCategory {

    private static final Set<String> REFRESH_KEYS = Set.of(
            Settings.KEYWORD_HIDE_COUNT_HOME.key,
            Settings.KEYWORD_HIDE_COUNT_SUBSCRIPTIONS.key,
            Settings.KEYWORD_HIDE_COUNT_SEARCH.key,
            Settings.KEYWORD_HIDE_COUNT_COMMENTS.key,
            Settings.KEYWORD_HIDES_24H.key
    );

    private final SharedPreferences.OnSharedPreferenceChangeListener listener =
            (prefs, key) -> {
                if (REFRESH_KEYS.contains(key)) {
                    Utils.runOnMainThread(this::refresh);
                }
            };

    private final NumberFormat nf = NumberFormat.getInstance(Settings.MORPHE_LANGUAGE.get().getLocale());

    public KeywordContentStatsPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public KeywordContentStatsPreferenceCategory(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public KeywordContentStatsPreferenceCategory(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public KeywordContentStatsPreferenceCategory(Context context) {
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

        final String homeCount24h = nf.format(KeywordContentFilter.hidesInLast24Hours(Source.HOME));
        final String subsCount24h = nf.format(KeywordContentFilter.hidesInLast24Hours(Source.SUBSCRIPTIONS));
        final String searchCount24h = nf.format(KeywordContentFilter.hidesInLast24Hours(Source.SEARCH));
        final String commentsCount24h = nf.format(KeywordContentFilter.hidesInLast24Hours(Source.COMMENTS));
        final String total24h = nf.format(KeywordContentFilter.hidesInLast24Hours());

        Preference hidden24h = new Preference(context);
        hidden24h.setTitle(str("morphe_hide_stats_hidden_24h_title"));
        hidden24h.setSummary(str("morphe_hide_keyword_content_stats_hidden_breakdown",
                homeCount24h, subsCount24h, searchCount24h, commentsCount24h, total24h));
        hidden24h.setOnPreferenceClickListener(pref -> {
            showResetDialog(
                    str("morphe_hide_stats_hidden_24h_reset_title"),
                    KeywordContentFilter::resetHidesTracker);
            return true;
        });
        addPreference(hidden24h);

        long homeAll = Settings.KEYWORD_HIDE_COUNT_HOME.get();
        long subsAll = Settings.KEYWORD_HIDE_COUNT_SUBSCRIPTIONS.get();
        long searchAll = Settings.KEYWORD_HIDE_COUNT_SEARCH.get();
        long commentsAll = Settings.KEYWORD_HIDE_COUNT_COMMENTS.get();
        long totalAll = homeAll + subsAll + searchAll + commentsAll;

        Preference hiddenAllTime = new Preference(context);
        hiddenAllTime.setTitle(str("morphe_hide_stats_hidden_all_title"));
        hiddenAllTime.setSummary(str("morphe_hide_keyword_content_stats_hidden_breakdown",
                nf.format(homeAll), nf.format(subsAll), nf.format(searchAll), nf.format(commentsAll),
                nf.format(totalAll)));
        hiddenAllTime.setOnPreferenceClickListener(pref -> {
            showResetDialog(
                    str("morphe_hide_stats_hidden_all_reset_title"),
                    () -> {
                        Settings.KEYWORD_HIDE_COUNT_HOME.resetToDefault();
                        Settings.KEYWORD_HIDE_COUNT_SUBSCRIPTIONS.resetToDefault();
                        Settings.KEYWORD_HIDE_COUNT_SEARCH.resetToDefault();
                        Settings.KEYWORD_HIDE_COUNT_COMMENTS.resetToDefault();
                        KeywordContentFilter.resetHidesTracker();
                    });
            return true;
        });
        addPreference(hiddenAllTime);
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
}
