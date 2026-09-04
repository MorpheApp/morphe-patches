/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2712
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 * https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/4881
 * https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/5806
 * https://gitlab.com/ReVanced/revanced-patches/-/merge_requests/5838
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.settings.search;

import android.graphics.Color;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.SwitchPreference;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;

import androidx.annotation.ColorInt;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ColorPickerPreference;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.settings.preference.URLLinkPreference;
import app.morphe.extension.shared.theme.ThemeUtils;

/**
 * Abstract base class for search result items.
 * <p>
 * An item is only an index entry. Nothing about the current appearance is stored here, so
 * preferences that update themselves at runtime stay correct in the results.
 */
public abstract class BaseSearchResultItem {
    public enum ViewType {
        REGULAR,
        SWITCH,
        LIST,
        COLOR_PICKER,
        GROUP_HEADER,
        NO_RESULTS,
        URL_LINK;

        public int getLayoutResourceId() {
            return switch (this) {
                case REGULAR, URL_LINK ->   getResourceIdentifier("morphe_preference_search_result_regular");
                case SWITCH ->              getResourceIdentifier("morphe_preference_search_result_switch");
                case LIST   ->              getResourceIdentifier("morphe_preference_search_result_list");
                case COLOR_PICKER ->        getResourceIdentifier("morphe_preference_search_result_color");
                case GROUP_HEADER ->        getResourceIdentifier("morphe_preference_search_result_group_header");
                case NO_RESULTS   ->        getResourceIdentifier("morphe_preference_search_no_result");
            };
        }

        private static int getResourceIdentifier(String name) {
            return ResourceUtils.getIdentifierOrThrow(ResourceType.LAYOUT, name);
        }
    }

    final String navigationPath;
    final List<String> navigationKeys;
    final ViewType preferenceType;

    BaseSearchResultItem(String navPath, List<String> navKeys, ViewType type) {
        this.navigationPath = navPath;
        this.navigationKeys = new ArrayList<>(navKeys != null ? navKeys : Collections.emptyList());
        this.preferenceType = type;
    }

    abstract boolean matchesQuery(String query);

    /**
     * @param queryPattern The query to highlight, or null to show the text as is.
     */
    abstract CharSequence getDisplayTitle(@Nullable Pattern queryPattern);

    /**
     * Existing spans of the text are kept, so bullet points and similar formatting survive.
     */
    static CharSequence highlightSearchQuery(CharSequence text, @Nullable Pattern queryPattern) {
        if (TextUtils.isEmpty(text) || queryPattern == null) return text;

        final int adjustedColor = Utils.adjustColorBrightness(
                ThemeUtils.getAppBackgroundColor(), 0.95f, 1.20f);
        SpannableStringBuilder spannable = new SpannableStringBuilder(text);

        Matcher matcher = queryPattern.matcher(text);
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if (start == end) continue; // Skip zero matches.
            // A span object can exist only once in a Spannable, so every match needs its own.
            spannable.setSpan(new BackgroundColorSpan(adjustedColor), start, end,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return spannable;
    }

    /**
     * Search result item for group headers (navigation path only).
     */
    public static class GroupHeaderItem extends BaseSearchResultItem {
        GroupHeaderItem(String navPath, List<String> navKeys) {
            super(navPath, navKeys, ViewType.GROUP_HEADER);
        }

        @Override
        boolean matchesQuery(String query) {
            return false; // Headers are not directly searchable.
        }

        @Override
        CharSequence getDisplayTitle(@Nullable Pattern queryPattern) {
            return navigationPath;
        }
    }

    /**
     * Search result item for preferences.
     */
    @SuppressWarnings("deprecation")
    public static class PreferenceSearchItem extends BaseSearchResultItem {
        public final Preference preference;
        final String searchableText;

        PreferenceSearchItem(Preference pref, String navPath, List<String> navKeys) {
            super(navPath, navKeys, determineType(pref));
            this.preference = pref;
            this.searchableText = buildSearchableText(pref);
        }

        private static ViewType determineType(Preference pref) {
            if (pref instanceof SwitchPreference) return ViewType.SWITCH;
            if (pref instanceof ListPreference) return ViewType.LIST;
            if (pref instanceof ColorPickerPreference) return ViewType.COLOR_PICKER;
            if (pref instanceof URLLinkPreference) return ViewType.URL_LINK;
            if ("no_results_placeholder".equals(pref.getKey())) return ViewType.NO_RESULTS;
            return ViewType.REGULAR;
        }

        /**
         * Built once so that results do not shuffle while a preference changes under the user.
         */
        private String buildSearchableText(Preference pref) {
            StringBuilder searchBuilder = new StringBuilder();
            String key = pref.getKey();
            String normalizedKey = "";
            if (key != null) {
                // Normalize preference key by removing the common "morphe_" prefix
                // so that users can search by the meaningful part only.
                normalizedKey = key.startsWith("morphe_")
                        ? key.substring("morphe_".length())
                        : key;
            }
            appendText(searchBuilder, normalizedKey);
            appendText(searchBuilder, pref.getTitle());
            appendText(searchBuilder, pref.getSummary());

            // Add type-specific searchable content.
            if (pref instanceof ListPreference listPref) {
                CharSequence[] entries = listPref.getEntries();
                if (entries != null) {
                    for (CharSequence entry : entries) {
                        appendText(searchBuilder, entry);
                    }
                }
            } else if (pref instanceof ColorPickerPreference) {
                appendText(searchBuilder, ColorPickerPreference.getColorString(getColor(), false));
            }

            // Include navigation path in searchable text.
            appendText(searchBuilder, navigationPath);

            return searchBuilder.toString();
        }

        /**
         * Uses full Unicode normalization for accurate search across all languages.
         */
        private void appendText(StringBuilder builder, CharSequence text) {
            if (!TextUtils.isEmpty(text)) {
                if (builder.length() > 0) {
                    builder.append(" ");
                }
                builder.append(Utils.normalizeTextToLowercase(text));
            }
        }

        /**
         * Matching is case insensitive and ignores punctuation.
         */
        @Override
        boolean matchesQuery(String query) {
            return searchableText.contains(Utils.normalizeTextToLowercase(query));
        }

        @Override
        CharSequence getDisplayTitle(@Nullable Pattern queryPattern) {
            CharSequence title = preference.getTitle();
            return highlightSearchQuery(title != null ? title : "", queryPattern);
        }

        CharSequence getDisplaySummary(@Nullable Pattern queryPattern) {
            return highlightSearchQuery(getLiveSummary(), queryPattern);
        }

        /**
         * A list shows its selected entry rather than its static summary, matching how the
         * preference renders on its own screen.
         */
        private CharSequence getLiveSummary() {
            if (preference instanceof CustomDialogListPreference customPref) {
                String staticSum = customPref.getStaticSummary();
                if (staticSum != null) {
                    return staticSum;
                }
            }
            if (preference instanceof ListPreference listPref) {
                String value = listPref.getValue();
                CharSequence[] entries = listPref.getEntries();
                CharSequence[] entryValues = listPref.getEntryValues();
                if (value != null && entries != null && entryValues != null) {
                    for (int i = 0, length = Math.min(entries.length, entryValues.length); i < length; i++) {
                        if (value.equals(entryValues[i].toString()) && entries[i] != null) {
                            return entries[i];
                        }
                    }
                }
            }
            CharSequence summary = preference.getSummary();
            return summary != null ? summary : "";
        }

        /**
         * @return The dialog entries with the query highlighted, or null if there is nothing to highlight.
         */
        @Nullable
        CharSequence[] getHighlightedEntries(@Nullable Pattern queryPattern) {
            if (queryPattern == null || !(preference instanceof ListPreference listPref)) {
                return null;
            }
            CharSequence[] entries = listPref.getEntries();
            if (entries == null) {
                return null;
            }

            CharSequence[] highlighted = new CharSequence[entries.length];
            for (int i = 0, length = entries.length; i < length; i++) {
                highlighted[i] = entries[i] == null
                        ? null
                        : highlightSearchQuery(entries[i], queryPattern);
            }
            return highlighted;
        }

        @ColorInt
        int getColor() {
            if (preference instanceof ColorPickerPreference colorPref) {
                String colorString = colorPref.getText();
                if (!TextUtils.isEmpty(colorString)) {
                    try {
                        return Color.parseColor(colorString);
                    } catch (IllegalArgumentException ex) {
                        // Imported settings can carry an invalid color, and this runs on every bind.
                        Logger.printDebug(() -> "Parse color error: " + colorString, ex);
                    }
                }
            }
            return 0;
        }
    }
}
