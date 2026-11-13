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
 * https://github.com/MorpheApp/morphe-patches/pull/4530
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.components;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.BufferHideStatsTracker;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.shared.patches.components.StringFilterGroup;
import app.morphe.extension.shared.settings.LongSetting;
import app.morphe.extension.youtube.innertube.ElementTexts.CommentData;
import app.morphe.extension.youtube.innertube.ElementTexts.Element;
import app.morphe.extension.youtube.innertube.ElementTexts.ElementModel;
import app.morphe.extension.youtube.innertube.ElementTexts.LockupMetadataViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.MetadataPart;
import app.morphe.extension.youtube.innertube.ElementTexts.MetadataRow;
import app.morphe.extension.youtube.innertube.ElementTexts.ShortsLockupMetadata;
import app.morphe.extension.youtube.innertube.ElementTexts.ShortsLockupViewModel;
import app.morphe.extension.youtube.innertube.ElementTexts.VideoData;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.NavigationBar;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * <pre>
 * Allows hiding home feed and search results based on video title keywords and/or channel names,
 * and hiding comments based on the comment text and/or the name of the author.
 *
 * The buffer of a video or a comment is its element, which includes the model of the element.
 * Only the texts of the model that are shown are searched: the title of the video, the line below
 * the title (channel name, views and date), the text of the comment and the name of the author.
 * Other texts of the buffer are not searched, such as the menu items, the accessibility labels,
 * the search text of the search results and the texts of the comment buttons.
 *
 * Keywords match any casing. Keywords that start with @ also match the channel handle of the videos.
 *
 * Limitations:
 * - Shorts in the Home and Subscriptions feeds do not show the channel name,
 *   so they are not filtered by channel name.
 * - Videos of the compact layout of older app versions do not include the channel name,
 *   so they are only filtered by title.
 * - Some layout component residue will remain, such as the video chapter previews for some search results.
 *   These components do not include the video title or channel name, and they
 *   appear outside the filtered components so they are not caught.
 * - When using whole word syntax, some keywords may need additional pluralized variations.
 * </pre>
 */
@SuppressWarnings("unused")
public final class KeywordContentFilter extends BufferPhraseFilter {

    /**
     * Minimum keyword/phrase length to prevent excessively broad content filtering.
     * Only applies when not using whole word syntax.
     */
    private static final int MINIMUM_KEYWORD_LENGTH = 3;

    /**
     * Start of the keywords that also match the channel handle of a video, such as "@handle".
     */
    private static final String HANDLE_PREFIX = "@";

    private final StringFilterGroup commentsFilter = new StringFilterGroup(
            Settings.HIDE_KEYWORD_CONTENT_COMMENTS,
            "comment_thread.eml"
    );

    /**
     * The last value of {@link Settings#HIDE_KEYWORD_CONTENT_PHRASES}
     * parsed and loaded into {@link #keywordSearch}.
     * Allows changing the keywords without restarting the app.
     */
    private volatile String lastKeywordPhrasesParsed;

    /**
     * Keywords in lower case, searched in the texts in lower case.
     */
    @Nullable
    private volatile StringTrieSearch keywordSearch;

    /**
     * Parameter of a search of the keywords.
     */
    private static final class KeywordMatch {
        /**
         * If the searched text is a channel handle, which only the keywords that start with
         * {@link #HANDLE_PREFIX} match.
         */
        boolean searchingHandle;
        /**
         * The keyword as entered in the settings, if a keyword matched.
         */
        @Nullable
        String keyword;
    }

    private record Keyword(String phrase, boolean wholeWord) {
    }

    private static boolean phraseUsesWholeWordSyntax(String phrase) {
        return phrase.startsWith("\"") && phrase.endsWith("\"");
    }

    private static String stripWholeWordSyntax(String phrase) {
        return phrase.substring(1, phrase.length() - 1);
    }

    /**
     * Keywords and texts are compared in lower case, so keywords match any casing.
     */
    private static String foldCase(String text) {
        return text.toLowerCase(Locale.ROOT);
    }

    /**
     * @return If the match is not surrounded by other letters.
     *         A match surrounded by numbers/symbols/punctuation is a whole word.
     */
    private static boolean isWholeWord(CharSequence text, int startIndex, int length) {
        if (startIndex > 0 && Character.isLetter(Character.codePointBefore(text, startIndex))) {
            return false;
        }
        final int endIndex = startIndex + length;
        return endIndex >= text.length() || !Character.isLetter(Character.codePointAt(text, endIndex));
    }

    private synchronized void parseKeywords() { // Must be synchronized since Litho is multithreaded.
        String rawKeywords = Settings.HIDE_KEYWORD_CONTENT_PHRASES.get();

        //noinspection StringEquality
        if (rawKeywords == lastKeywordPhrasesParsed) {
            Logger.printDebug(() -> "Using previously initialized search");
            return; // Another thread won the race, and search is already initialized.
        }

        // Keyword in lower case -> keyword. Linked map so log statements are in the order of the setting.
        Map<String, Keyword> keywords = new LinkedHashMap<>();
        for (String phrase : rawKeywords.split("\n")) {
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
                Utils.showSnackbarLong(str("morphe_hide_keyword_toast_invalid_length", phrase, MINIMUM_KEYWORD_LENGTH));
                continue;
            } else {
                wholeWordMatching = false;
            }

            // If the phrase is declared both with and without quotes, use without quotes since it is broader.
            String key = foldCase(phrase);
            Keyword existing = keywords.get(key);
            if (existing == null) {
                keywords.put(key, new Keyword(phrase, wholeWordMatching));
            } else if (existing.wholeWord() && !wholeWordMatching) {
                keywords.put(key, new Keyword(phrase, false));
            }
        }

        StringTrieSearch search = new StringTrieSearch();
        for (Map.Entry<String, Keyword> entry : keywords.entrySet()) {
            Keyword keyword = entry.getValue();
            final boolean isWholeWord = keyword.wholeWord();
            final boolean matchesHandles = keyword.phrase().startsWith(HANDLE_PREFIX);

            search.addPattern(entry.getKey(), (textSearched, startIndex, matchLength, callbackParameter) -> {
                KeywordMatch match = (KeywordMatch) callbackParameter;
                if (match.searchingHandle && !matchesHandles) {
                    return false;
                }
                if (isWholeWord && !isWholeWord(textSearched, startIndex, matchLength)) {
                    return false;
                }

                Logger.printDebug(() -> (isWholeWord ? "Matched whole keyword: '"
                        : "Matched keyword: '") + keyword.phrase() + "'");
                match.keyword = keyword.phrase();
                return true;
            });
        }

        Logger.printDebug(() -> "Search using: (" + search.getEstimatedMemorySize() + " KB) keywords: " + keywords.keySet());

        keywordSearch = search;
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
    protected boolean isActiveForFeedContext(ContextInterface contextInterface) {
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

        NavigationBar.NavigationButton selectedNavButton =
                NavigationBar.NavigationButton.getSelectedNavigationButton(contextInterface);
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
    protected String matchBuffer(byte[] buffer, StringFilterGroup matchedGroup,
                                 ContextInterface contextInterface) {
        StringTrieSearch search = keywordSearch;
        if (search == null || search.numberOfPatterns() == 0) {
            return null;
        }

        try {
            ElementModel model = findModel(Element.parseFrom(buffer));
            if (model == null) {
                return null;
            }

            KeywordMatch match = new KeywordMatch();
            if (!matchesShownTexts(search, model, match)) {
                return null;
            }

            recordHide(matchedGroup, buffer, contextInterface);
            return match.keyword;
        } catch (InvalidProtocolBufferException ex) {
            Logger.printException(() -> "matchBuffer failure", ex);
            return null;
        }
    }

    /**
     * Elements of the components inside a video or a comment include the same model, so they
     * match the same keywords. Elements of the texts and the containers have no model.
     *
     * @return The model of the element or of the first element inside it, in the order of the
     *         elements, that has the model of a video or a comment. Null if none.
     *         Models inside other models are not used, such as the Short of the
     *         Remix menu item of a video.
     */
    @Nullable
    private static ElementModel findModel(Element element) {
        ElementModel model = element.getType().getComponentType().getModel();
        if (model.hasVideoWithContextModel()
                || model.getYoutubeModel().getViewModel().hasShortsLockupViewModel()
                || model.hasCommentModel()
                || model.hasCompactVideoMetadataModel()) {
            return model;
        }

        for (Element child : element.getChildrenList()) {
            ElementModel childModel = findModel(child);
            if (childModel != null) {
                return childModel;
            }
        }

        return null;
    }

    /**
     * @return If a keyword matches a text of the model that is shown,
     *         or the channel handle of a video if the keyword is a handle.
     */
    private static boolean matchesShownTexts(StringTrieSearch search, ElementModel model, KeywordMatch match) {
        if (model.hasVideoWithContextModel()) {
            VideoData video = model.getVideoWithContextModel().getData().getVideoData();
            LockupMetadataViewModel metadata = video.getLockupMetadata().getLockupMetadataViewModel();
            if (matches(search, metadata.getTitle().getContent(), match)) {
                return true;
            }
            for (MetadataRow row : metadata.getMetadata().getContentMetadataViewModel().getMetadataRowsList()) {
                for (MetadataPart part : row.getMetadataPartsList()) {
                    if (matches(search, part.getText().getContent(), match)) {
                        return true;
                    }
                }
            }

            match.searchingHandle = true;
            return matches(search, video.getAvatar().getEndpoint().getInnertubeCommand()
                    .getBrowseEndpoint().getCanonicalBaseUrl(), match);
        }

        if (model.hasCommentModel()) {
            CommentData comment = model.getCommentModel().getData();
            return matches(search, comment.getProperties().getContent().getContent(), match)
                    || matches(search, comment.getAuthor().getDisplayName(), match);
        }

        if (model.hasCompactVideoMetadataModel()) {
            return matches(search, model.getCompactVideoMetadataModel().getMetadata().getTitle(), match);
        }

        ShortsLockupViewModel shorts = model.getYoutubeModel().getViewModel().getShortsLockupViewModel();
        ShortsLockupMetadata metadata = shorts.getMetadata();
        if (matches(search, shorts.getTitle().getText().getContent(), match)
                || matches(search, metadata.getChannelName().getContent(), match)) {
            return true;
        }

        match.searchingHandle = true;
        return matches(search, metadata.getAvatar().getAvatarViewModel().getOnTap().getInnertubeCommand()
                .getBrowseEndpoint().getCanonicalBaseUrl(), match);
    }

    private static boolean matches(StringTrieSearch search, String text, KeywordMatch match) {
        return !text.isEmpty() && search.matches(foldCase(text), match);
    }

    @Override
    protected void onBroadFilterDetected(@Nullable String matched) {
        Utils.showSnackbarLong(str("morphe_hide_keyword_toast_invalid_broad", matched));
    }

    private void recordHide(StringFilterGroup matchedGroup, byte[] buffer, ContextInterface contextInterface) {
        Source source = detectSource(matchedGroup, contextInterface);
        String videoId = getVideoIdForSource(source, buffer);
        // If ID extraction fails the hide still happens; only stats are skipped
        // to avoid double-counting the same card as it re-enters the viewport.
        if (videoId == null) return;

        if (sharedTracker.recordHide(videoId, source, System.currentTimeMillis())) {
            LongSetting counter = allTimeCounterFor(source);
            if (counter != null) counter.save(counter.get() + 1);
        }
    }

    private Source detectSource(StringFilterGroup matchedGroup, ContextInterface contextInterface) {
        if (matchedGroup == commentsFilter) return Source.COMMENTS;
        // Player fullscreen: treat under-video results as home (mirrors isActiveForFeedContext).
        if (PlayerType.getCurrent().isMaximizedOrFullscreen()) return Source.HOME;
        if (NavigationBar.isSearchBarActive()) return Source.SEARCH;
        NavigationBar.NavigationButton nav = NavigationBar.NavigationButton.getSelectedNavigationButton(contextInterface);
        return nav == NavigationBar.NavigationButton.SUBSCRIPTIONS ? Source.SUBSCRIPTIONS : Source.HOME;
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
