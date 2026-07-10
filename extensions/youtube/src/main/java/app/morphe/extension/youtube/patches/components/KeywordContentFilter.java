/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original first edition code:
 * https://github.com/ReVanced/revanced-integrations/pull/584
 * https://github.com/ReVanced/revanced-integrations/commit/0cbad9820577c476f1f29b6ac77611b38afbb950
 * https://github.com/ReVanced/revanced-integrations/commit/1ee99aa6f0b4af15eeca25c7e21e8a0f5e9d189a
 * https://github.com/ReVanced/revanced-integrations/commit/c3bfa77d62b15dedfed8f697583f2f0805f0c2c1
 * https://github.com/ReVanced/revanced-integrations/commit/75fa5797f70123f68d4676201503cf35dcef46dc
 * https://github.com/ReVanced/revanced-integrations/commit/3a3ceec4b596354dcccbf3516ef1634bd8819b90
 * https://github.com/ReVanced/revanced-integrations/commit/cda1f3160c12d239df1183799ead39526cbac20f
 * https://github.com/ReVanced/revanced-integrations/commit/d8d2a852d3879060bd95cc43d66c7cf195e82b43
 * https://github.com/ReVanced/revanced-integrations/commit/2f2eeea5a722b6b7053eb2825d16fa37938b4e9e
 * https://github.com/ReVanced/revanced-integrations/commit/5314dd90d16dc8565331c4cddce114956d85a173
 * https://github.com/MorpheApp/morphe-patches/commit/f5371ca998c019609c2b5558b3408ab1fec065c8
 * https://github.com/MorpheApp/morphe-patches/commit/017eac71a3f9542b8ad6221e3600797d6b97fae4
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.shared.NavigationBar.NavigationButton;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.TrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferHideStatsTracker;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * <pre>
 * Allows hiding home feed and search results based on video title keywords and/or channel names.
 *
 * Limitations:
 * - Searching for a keyword phrase will give no search results.
 *   This is because the buffer for each video contains the text the user searched for, and everything
 *   will be filtered away (even if that video title/channel does not contain any keywords).
 * - Filtering a channel name can still show Shorts from that channel in the search results.
 *   The most common Shorts layouts do not include the channel name, so they will not be filtered.
 * - Some layout component residue will remain, such as the video chapter previews for some search results.
 *   These components do not include the video title or channel name, and they
 *   appear outside the filtered components so they are not caught.
 * - Keywords are case-sensitive, but some casing variation is manually added.
 *   (ie: "mr beast" automatically filters "Mr Beast" and "MR BEAST").
 * - Keywords present in the layout or video data cannot be used as filters, otherwise all videos
 *   will always be hidden.  This patch checks for some words of these words.
 * - When using whole word syntax, some keywords may need additional pluralized variations.
 */
@SuppressWarnings({"unused", "unchecked"})
public final class KeywordContentFilter extends BufferPhraseFilter {

    /**
     * Minimum keyword/phrase length to prevent excessively broad content filtering.
     * Only applies when not using whole word syntax.
     */
    private static final int MINIMUM_KEYWORD_LENGTH = 3;

    private final StringFilterGroup commentsFilter = new StringFilterGroup(
            Settings.HIDE_KEYWORD_CONTENT_COMMENTS,
            "comment_thread.eml"
    );

    /**
     * The last value of {@link Settings#HIDE_KEYWORD_CONTENT_PHRASES}
     * parsed and loaded into {@link #bufferSearch}.
     * Allows changing the keywords without restarting the app.
     */
    private volatile String lastKeywordPhrasesParsed;

    private volatile ByteTrieSearch bufferSearch;

    private static boolean phraseUsesWholeWordSyntax(String phrase) {
        return phrase.startsWith("\"") && phrase.endsWith("\"");
    }

    private static String stripWholeWordSyntax(String phrase) {
        return phrase.substring(1, phrase.length() - 1);
    }

    private synchronized void parseKeywords() { // Must be synchronized since Litho is multithreaded.
        String rawKeywords = Settings.HIDE_KEYWORD_CONTENT_PHRASES.get();

        //noinspection StringEquality
        if (rawKeywords == lastKeywordPhrasesParsed) {
            Logger.printDebug(() -> "Using previously initialized search");
            return; // Another thread won the race, and search is already initialized.
        }

        ByteTrieSearch search = new ByteTrieSearch();
        String[] split = rawKeywords.split("\n");
        if (split.length != 0) {
            // Linked Set so log statement are more organized and easier to read.
            // Map is: Phrase -> isWholeWord
            Map<String, Boolean> keywords = new LinkedHashMap<>(10 * split.length);

            for (String phrase : split) {
                // Remove any trailing spaces the user may have accidentally included.
                phrase = phrase.stripTrailing();
                if (phrase.isBlank()) continue;

                final boolean wholeWordMatching;
                if (phraseUsesWholeWordSyntax(phrase)) {
                    if (phrase.length() == 2) {
                        continue; // Empty "" phrase
                    }
                    phrase = stripWholeWordSyntax(phrase);
                    wholeWordMatching = true;
                } else if (phrase.length() < MINIMUM_KEYWORD_LENGTH && !isLanguageWithNoSpaces(phrase)) {
                    // Allow phrases of 1 and 2 characters if using a
                    // language that does not use spaces between words.

                    // Do not reset the setting. Keep the invalid keywords so the user can fix the mistake.
                    Utils.showToastLong(str("morphe_hide_keyword_toast_invalid_length", phrase, MINIMUM_KEYWORD_LENGTH));
                    continue;
                } else {
                    wholeWordMatching = false;
                }

                // Common casing that might appear.
                //
                // This could be simplified by adding case-insensitive search to the prefix search,
                // which is very simple to add to StringTreSearch for Unicode and ByteTrieSearch for ASCII.
                //
                // But to support Unicode with ByteTrieSearch would require major changes because
                // UTF-8 characters can be different byte lengths, which does
                // not allow comparing two different byte arrays using simple plain array indexes.
                //
                // Instead, use all common case variations of the words.
                Locale defaultLocale = Locale.getDefault();
                String[] phraseVariations = {
                        phrase,
                        // Use both root locale and device locale, to cover
                        // English rules and device locale specific rules.
                        phrase.toLowerCase(Locale.ROOT),
                        phrase.toLowerCase(defaultLocale),
                        titleCaseFirstWordOnly(phrase),
                        capitalizeAllFirstLetters(phrase),
                        phrase.toUpperCase(Locale.ROOT),
                        phrase.toUpperCase(defaultLocale)
                };

                if (phrasesWillHideAllVideos(phraseVariations, wholeWordMatching)) {
                    String toastMessage;
                    // If whole word matching is off, but would pass with on, then show a different toast.
                    if (!wholeWordMatching && !phrasesWillHideAllVideos(phraseVariations, true)) {
                        toastMessage = "morphe_hide_keyword_toast_invalid_common_whole_word_required";
                    } else {
                        toastMessage = "morphe_hide_keyword_toast_invalid_common";
                    }

                    Utils.showToastLong(str(toastMessage, phrase));
                    continue;
                }

                for (String variation : phraseVariations) {
                    // Check if the same phrase is declared both with and without quotes.
                    Boolean existing = keywords.get(variation);
                    if (existing == null) {
                        keywords.put(variation, wholeWordMatching);
                    } else if (existing != wholeWordMatching) {
                        Utils.showToastLong(str("morphe_hide_keyword_toast_invalid_conflicting", phrase));
                        break;
                    }
                }
            }

            for (Map.Entry<String, Boolean> entry : keywords.entrySet()) {
                String keyword = entry.getKey();
                //noinspection ExtractMethodRecommender
                final boolean isWholeWord = entry.getValue();

                TrieSearch.TriePatternMatchedCallback<byte[]> callback =
                        (textSearched, startIndex, matchLength, callbackParameter) -> {
                            if (isWholeWord && !keywordMatchIsWholeWord(textSearched, startIndex, matchLength)) {
                                return false;
                            }

                            Logger.printDebug(() -> (isWholeWord ? "Matched whole keyword: '"
                                    : "Matched keyword: '") + keyword + "'");
                            // noinspection unchecked
                            ((MutableReference<String>) callbackParameter).value = keyword;
                            return true;
                        };
                byte[] stringBytes = keyword.getBytes(StandardCharsets.UTF_8);
                search.addPattern(stringBytes, callback);
            }

            Logger.printDebug(() -> "Search using: (" + search.getEstimatedMemorySize() + " KB) keywords: " + keywords.keySet());
        }

        bufferSearch = search;
        lastKeywordPhrasesParsed = rawKeywords; // Must set last.
    }

    public KeywordContentFilter() {
        super(); // commentsFilter is registered below because instance fields initialize after super().
        addPathCallbacks(commentsFilter);
        // Keywords are parsed on first call to isFiltered().
    }

    @Override
    protected void reparseIfNeeded() {
        // Field is intentionally compared using reference equality.
        //noinspection StringEquality
        if (Settings.HIDE_KEYWORD_CONTENT_PHRASES.get() != lastKeywordPhrasesParsed) {
            // User changed the keywords or whole word setting.
            parseKeywords();
        }
    }

    @Override
    protected boolean isActiveForFeedContext() {
        // Must check player type first, as search bar can be active behind the player.
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) {
            // For now, consider the under video results the same as the home feed.
            return Settings.HIDE_KEYWORD_CONTENT_HOME.get();
        }

        // Must check second, as search can be from any tab.
        if (NavigationBar.isSearchBarActive()) {
            return Settings.HIDE_KEYWORD_CONTENT_SEARCH.get();
        }

        // Avoid checking navigation button status if all other settings are off.
        final boolean hideHome = Settings.HIDE_KEYWORD_CONTENT_HOME.get();
        final boolean hideSubscriptions = Settings.HIDE_KEYWORD_CONTENT_SUBSCRIPTIONS.get();
        if (!hideHome && !hideSubscriptions) {
            return false;
        }

        NavigationButton selectedNavButton = NavigationButton.getSelectedNavigationButton();
        if (selectedNavButton == null) {
            return hideHome; // Unknown tab, treat the same as home.
        }

        return switch (selectedNavButton) {
            case HOME -> hideHome;
            case SUBSCRIPTIONS -> hideSubscriptions;
            // User is in the Library or notifications.
            default -> false;
        };
    }

    @Override
    @Nullable
    protected String matchBuffer(byte[] buffer, StringFilterGroup matchedGroup) {
        ByteTrieSearch search = bufferSearch;
        if (search == null) return null;
        MutableReference<String> matchRef = new MutableReference<>();
        if (!search.matches(buffer, matchRef)) return null;
        recordHide(matchedGroup, buffer);
        return matchRef.value;
    }

    @Override
    protected void onBroadFilterDetected(@Nullable String matched) {
        Utils.showToastLong(str("morphe_hide_keyword_toast_invalid_broad", matched));
    }

    private void recordHide(StringFilterGroup matchedGroup, byte[] buffer) {
        Source source = detectSource(matchedGroup);
        String videoId = getVideoIdForSource(source, buffer);
        // If ID extraction fails the hide still happens; only stats are skipped
        // to avoid double-counting the same card as it re-enters the viewport.
        if (videoId == null) return;

        if (sharedTracker.recordHide(videoId, source, System.currentTimeMillis())) {
            LongSetting counter = allTimeCounterFor(source);
            if (counter != null) counter.save(counter.get() + 1);
        }
    }

    private Source detectSource(StringFilterGroup matchedGroup) {
        if (matchedGroup == commentsFilter) return Source.COMMENTS;
        // Player fullscreen: treat under-video results as home (mirrors isActiveForFeedContext).
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) return Source.HOME;
        if (NavigationBar.isSearchBarActive()) return Source.SEARCH;
        NavigationButton nav = NavigationButton.getSelectedNavigationButton();
        return nav == NavigationButton.SUBSCRIPTIONS ? Source.SUBSCRIPTIONS : Source.HOME;
    }

    @Nullable
    private static String getVideoIdForSource(Source source, byte[] buffer) {
        if (source == Source.COMMENTS) {
            // Comment threads carry no thumbnail URL for the parent video; the currently
            // open player's video ID is authoritative.
            String id = VideoInformation.getVideoId();
            return id.isEmpty() ? null : id;
        }
        return extractVideoIdFromBuffer(buffer);
    }

    private static LongSetting allTimeCounterFor(Source source) {
        return switch (source) {
            case HOME -> Settings.KEYWORD_HIDE_COUNT_HOME;
            case SUBSCRIPTIONS -> Settings.KEYWORD_HIDE_COUNT_SUBSCRIPTIONS;
            case SEARCH -> Settings.KEYWORD_HIDE_COUNT_SEARCH;
            case COMMENTS -> Settings.KEYWORD_HIDE_COUNT_COMMENTS;
        };
    }

    /** Returns the total 24h hide count across all sources. */
    public static int hidesInLast24Hours() {
        return sharedTracker.totalSize(System.currentTimeMillis());
    }

    /** Returns the 24h hide count for a given source. */
    public static int hidesInLast24Hours(Source source) {
        return sharedTracker.sourceSize(source, System.currentTimeMillis());
    }

    /** Clears the 24h tracker. Called from the reset dialogs. */
    public static void resetHidesTracker() {
        sharedTracker.reset();
    }

    /** Shared static reference so the UI can query without holding a filter instance. */
    private static final BufferHideStatsTracker sharedTracker =
            new BufferHideStatsTracker(Settings.KEYWORD_HIDES_24H);
}
