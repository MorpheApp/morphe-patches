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

package app.morphe.extension.shared.patches.components;

import static java.lang.Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS;
import static java.lang.Character.UnicodeBlock.HIRAGANA;
import static java.lang.Character.UnicodeBlock.KATAKANA;
import static java.lang.Character.UnicodeBlock.KHMER;
import static java.lang.Character.UnicodeBlock.LAO;
import static java.lang.Character.UnicodeBlock.MYANMAR;
import static java.lang.Character.UnicodeBlock.THAI;
import static java.lang.Character.UnicodeBlock.TIBETAN;

import androidx.annotation.Nullable;

import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringTrieSearch;
import app.morphe.extension.shared.Utils;

/**
 * Abstract base for Litho filters that scan the protocol buffer for phrase matches
 * inside feed/search video cards. Owns the shared path filter groups, exception
 * trie, broad-filter detection, and UTF-8 utilities. Subclasses supply the
 * per-invocation phrase-matching logic and per-tab activation gating.
 */
@SuppressWarnings("unused")
public abstract class BufferPhraseFilter extends Filter {

    /**
     * Strings found in the buffer for every video. Full strings should be specified.
     * <p>
     * This list does not include every common buffer string, and this can be added/changed as needed.
     * Words must be entered with the exact casing as found in the buffer.
     */
    public static final String[] STRINGS_IN_EVERY_BUFFER = {
            // Video playback data.
            "googlevideo.com/initplayback?source=youtube", // Video url.
            "ANDROID", // Video url parameter.
            "https://i.ytimg.com/vi/", // Thumbnail url.
            "mqdefault.jpg",
            "hqdefault.jpg",
            "sddefault.jpg",
            "hq720.jpg",
            "webp",
            "_custom_", // Custom thumbnail set by video creator.
            // Video decoders.
            "OMX.ffmpeg.vp9.decoder",
            "OMX.Intel.sw_vd.vp9",
            "OMX.MTK.VIDEO.DECODER.SW.VP9",
            "OMX.google.vp9.decoder",
            "OMX.google.av1.decoder",
            "OMX.sprd.av1.decoder",
            "c2.android.av1.decoder",
            "c2.android.av1-dav1d.decoder",
            "c2.android.vp9.decoder",
            "c2.mtk.sw.vp9.decoder",
            // Analytics.
            "searchR",
            "browse-feed",
            "FEwhat_to_watch",
            "FEsubscriptions",
            "search_vwc_description_transition_key",
            "g-high-recZ",
            // Text and litho components found in the buffer that belong to path filters.
            "expandable_metadata.e",
            "thumbnail.e",
            "avatar.e",
            "overflow_button.e",
            "shorts-lockup-image",
            "shorts-lockup.overlay-metadata.secondary-text",
            "YouTubeSans-SemiBold",
            "sans-serif"
    };

    /**
     * Threshold for {@link #filteredVideosPercentage} that indicates all or nearly all
     * videos have been filtered. Close to 100% to reduce false positives.
     */
    private static final float ALL_VIDEOS_FILTERED_THRESHOLD = 0.95f;

    private static final float ALL_VIDEOS_FILTERED_SAMPLE_SIZE = 50;

    private static final long ALL_VIDEOS_FILTERED_BACKOFF_MILLISECONDS = 60 * 1000; // 60 seconds

    private static final int UTF8_MAX_BYTE_COUNT = 4;

    /**
     * Substrings that are always first in the identifier - the standard feed / search /
     * subscription / related video card containers.
     */
    protected final StringFilterGroup startsWithFilter = new StringFilterGroup(
            null, // Multiple settings are used and must be individually checked if active.
            "home_video_with_context.e",
            "search_video_with_context.e",
            "video_with_context.e", // Subscription tab videos.
            "related_video_with_context.e",
            // A/B test for subscribed video, and sometimes when tablet layout is enabled.
            "video_lockup_with_attachment.e",
            "compact_video.e",
            "inline_shorts",
            "shorts_video_cell",
            "shorts_pivot_item.e"
    );

    /**
     * Substrings that are never at the start of the path.
     */
    @SuppressWarnings("FieldCanBeLocal")
    protected final StringFilterGroup containsFilter = new StringFilterGroup(
            null,
            "modern_type_shelf_header_content.e",
            "shorts_lockup_cell.e", // Part of 'shorts_shelf_carousel.e'
            "video_card.e" // Shorts that appear in a horizontal shelf.
    );

    /**
     * Path components to not filter. Cannot filter the buffer when these are present,
     * otherwise text in UI controls can be filtered as a keyword (such as using "Playlist" as a keyword).
     * <p>
     * This is also a small performance improvement since the buffer of the parent component was
     * already searched and passed.
     */
    private final StringTrieSearch exceptions = new StringTrieSearch(
            "metadata.e",
            "thumbnail.e",
            "avatar.e",
            "overflow_button.e"
    );

    /**
     * Rolling average of how many videos were filtered by a phrase.
     * Used to detect if a phrase passes the initial check against {@link #STRINGS_IN_EVERY_BUFFER}
     * but a phrase is still hiding all videos.
     */
    private volatile float filteredVideosPercentage;

    /**
     * If filtering is temporarily turned off, the time to resume filtering.
     * Field is zero if no backoff is in effect.
     */
    private volatile long timeToResumeFiltering;

    protected BufferPhraseFilter(StringFilterGroup... extraPathCallbacks) {
        StringFilterGroup[] all = new StringFilterGroup[2 + extraPathCallbacks.length];
        all[0] = startsWithFilter;
        all[1] = containsFilter;
        System.arraycopy(extraPathCallbacks, 0, all, 2, extraPathCallbacks.length);
        addPathCallbacks(all);
    }

    /**
     * Called before each match attempt so the subclass can rebuild its search structures
     * if the underlying settings changed since the last call.
     */
    protected abstract void reparseIfNeeded();

    /**
     * @return whether filtering is active for the current feed / search context.
     * Only consulted when the matched group is {@link #startsWithFilter} or
     * {@link #containsFilter}. Extra path callbacks are assumed to be self-gated
     * by their own {@link app.morphe.extension.shared.settings.BooleanSetting}.
     */
    protected abstract boolean isActiveForFeedContext();

    /**
     * @return the matched phrase if the buffer contains a match, or {@code null} otherwise.
     * Called after the path-exception check has passed.
     */
    @Nullable
    protected abstract String matchBuffer(byte[] buffer, StringFilterGroup matchedGroup);

    /**
     * Optional hook invoked when a hide is confirmed. Subclasses can override to record stats.
     */
    protected void onHideConfirmed(String matched) {
        // Default no-op.
    }

    @Override
    public final boolean isFiltered(ContextInterface contextInterface,
                                    String identifier,
                                    String accessibility,
                                    String path,
                                    byte[] buffer,
                                    BufferAsciiStrings asciiStrings,
                                    StringFilterGroup matchedGroup,
                                    FilterContentType contentType,
                                    int contentIndex) {
        if (contentIndex != 0 && matchedGroup == startsWithFilter) {
            return false;
        }

        reparseIfNeeded();

        if (isBaseFeedGroup(matchedGroup) && !isActiveForFeedContextGuarded()) {
            return false;
        }

        if (exceptions.matches(path)) {
            return false; // Do not update statistics.
        }

        String matched = matchBuffer(buffer, matchedGroup);
        if (matched != null) {
            updateStats(true, matched);
            onHideConfirmed(matched);
            return true;
        }

        updateStats(false, null);
        return false;
    }

    private boolean isBaseFeedGroup(StringFilterGroup group) {
        return group == startsWithFilter || group == containsFilter;
    }

    /**
     * Wraps {@link #isActiveForFeedContext()} with the shared backoff timer used by
     * broad-filter detection.
     */
    private boolean isActiveForFeedContextGuarded() {
        if (timeToResumeFiltering != 0) {
            if (System.currentTimeMillis() < timeToResumeFiltering) {
                return false;
            }
            timeToResumeFiltering = 0;
            filteredVideosPercentage = 0;
            Logger.printDebug(() -> "Resuming filtering: " + getClass().getSimpleName());
        }
        return isActiveForFeedContext();
    }

    private void updateStats(boolean videoWasHidden, @Nullable String matched) {
        float updatedAverage = filteredVideosPercentage
                * ((ALL_VIDEOS_FILTERED_SAMPLE_SIZE - 1) / ALL_VIDEOS_FILTERED_SAMPLE_SIZE);
        if (videoWasHidden) {
            updatedAverage += 1 / ALL_VIDEOS_FILTERED_SAMPLE_SIZE;
        }

        if (updatedAverage <= ALL_VIDEOS_FILTERED_THRESHOLD) {
            filteredVideosPercentage = updatedAverage;
            return;
        }

        // A phrase is hiding everything. Inform the user, and temporarily turn off filtering.
        timeToResumeFiltering = System.currentTimeMillis() + ALL_VIDEOS_FILTERED_BACKOFF_MILLISECONDS;

        Logger.printDebug(() -> "Temporarily turning off filtering due to excessively broad match: " + matched);
        onBroadFilterDetected(matched);
    }

    /**
     * Called when the rolling average of hides exceeds the broad-filter threshold.
     * Default shows no toast; subclasses that surface user-input phrases (e.g.
     * KeywordContentFilter) can override to warn the user.
     */
    protected void onBroadFilterDetected(@Nullable String matched) {
        // Default no-op.
    }

    /**
     * @return If the string contains any characters from languages that do not use spaces between words.
     */
    public static boolean isLanguageWithNoSpaces(String text) {
        for (int i = 0, length = text.length(); i < length;) {
            final int codePoint = text.codePointAt(i);

            Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
            if (block == CJK_UNIFIED_IDEOGRAPHS // Chinese and Kanji
                    || block == HIRAGANA // Japanese Hiragana
                    || block == KATAKANA // Japanese Katakana
                    || block == THAI
                    || block == LAO
                    || block == MYANMAR
                    || block == KHMER
                    || block == TIBETAN) {
                return true;
            }

            i += Character.charCount(codePoint);
        }

        return false;
    }

    /**
     * Change first letter of the first word to use title case.
     */
    public static String titleCaseFirstWordOnly(String sentence) {
        if (sentence.isEmpty()) {
            return sentence;
        }
        final int firstCodePoint = sentence.codePointAt(0);
        // In some non-English languages title case is different from uppercase.
        return new StringBuilder()
                .appendCodePoint(Character.toTitleCase(firstCodePoint))
                .append(sentence, Character.charCount(firstCodePoint), sentence.length())
                .toString();
    }

    /**
     * Uppercase the first letter of each word.
     */
    public static String capitalizeAllFirstLetters(String sentence) {
        if (sentence.isEmpty()) {
            return sentence;
        }

        final int delimiter = ' ';
        // Use code points and not characters to handle Unicode surrogates.
        int[] codePoints = sentence.codePoints().toArray();
        boolean capitalizeNext = true;
        for (int i = 0, length = codePoints.length; i < length; i++) {
            final int codePoint = codePoints[i];
            if (codePoint == delimiter) {
                capitalizeNext = true;
            } else if (capitalizeNext) {
                codePoints[i] = Character.toUpperCase(codePoint);
                capitalizeNext = false;
            }
        }

        return new String(codePoints, 0, codePoints.length);
    }

    /**
     * @return If any of the given phrases would match a string in {@link #STRINGS_IN_EVERY_BUFFER}
     * (which would cause the filter to hide all videos).
     */
    public static boolean phrasesWillHideAllVideos(String[] phrases, boolean matchWholeWords) {
        for (String phrase : phrases) {
            for (String commonString : STRINGS_IN_EVERY_BUFFER) {
                if (matchWholeWords) {
                    byte[] commonStringBytes = commonString.getBytes(StandardCharsets.UTF_8);
                    int matchIndex = 0;
                    while (true) {
                        matchIndex = commonString.indexOf(phrase, matchIndex);
                        if (matchIndex < 0) break;

                        if (keywordMatchIsWholeWord(commonStringBytes, matchIndex, phrase.length())) {
                            return true;
                        }

                        matchIndex++;
                    }
                } else if (Utils.containsAny(commonString, phrases)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * @return If the start and end indexes are not surrounded by other letters.
     *         If the indexes are surrounded by numbers/symbols/punctuation it is considered a whole word.
     */
    public static boolean keywordMatchIsWholeWord(byte[] text, int keywordStartIndex, int keywordLength) {
        final Integer codePointBefore = getUtf8CodePointBefore(text, keywordStartIndex);
        if (codePointBefore != null && Character.isLetter(codePointBefore)) {
            return false;
        }

        final Integer codePointAfter = getUtf8CodePointAt(text, keywordStartIndex + keywordLength);
        //noinspection RedundantIfStatement
        if (codePointAfter != null && Character.isLetter(codePointAfter)) {
            return false;
        }

        return true;
    }

    /**
     * @return The UTF8 character point immediately before the index,
     *         or null if the bytes before the index is not a valid UTF8 character.
     */
    @Nullable
    public static Integer getUtf8CodePointBefore(byte[] data, int index) {
        int characterByteCount = 0;
        while (--index >= 0 && ++characterByteCount <= UTF8_MAX_BYTE_COUNT) {
            if (isValidUtf8(data, index, characterByteCount)) {
                return decodeUtf8ToCodePoint(data, index, characterByteCount);
            }
        }

        return null;
    }

    /**
     * @return The UTF8 character point at the index,
     *         or null if the index holds no valid UTF8 character.
     */
    @Nullable
    public static Integer getUtf8CodePointAt(byte[] data, int index) {
        int characterByteCount = 0;
        final int dataLength = data.length;
        while (index + characterByteCount < dataLength && ++characterByteCount <= UTF8_MAX_BYTE_COUNT) {
            if (isValidUtf8(data, index, characterByteCount)) {
                return decodeUtf8ToCodePoint(data, index, characterByteCount);
            }
        }

        return null;
    }

    public static boolean isValidUtf8(byte[] data, int startIndex, int numberOfBytes) {
        switch (numberOfBytes) {
            case 1 -> {
                return (data[startIndex] & 0x80) == 0; // 0xxxxxxx (ASCII)
            }
            case 2 -> {
                return (data[startIndex] & 0xE0) == 0xC0
                        && (data[startIndex + 1] & 0xC0) == 0x80; // 110xxxxx, 10xxxxxx
            }
            case 3 -> {
                return (data[startIndex] & 0xF0) == 0xE0
                        && (data[startIndex + 1] & 0xC0) == 0x80
                        && (data[startIndex + 2] & 0xC0) == 0x80; // 1110xxxx, 10xxxxxx, 10xxxxxx
            }
            case 4 -> {
                return (data[startIndex] & 0xF8) == 0xF0
                        && (data[startIndex + 1] & 0xC0) == 0x80
                        && (data[startIndex + 2] & 0xC0) == 0x80
                        && (data[startIndex + 3] & 0xC0) == 0x80;
            }
        }

        throw new IllegalArgumentException("numberOfBytes: " + numberOfBytes);
    }

    public static int decodeUtf8ToCodePoint(byte[] data, int startIndex, int numberOfBytes) {
        switch (numberOfBytes) {
            case 1 -> {
                return data[startIndex];
            }
            case 2 -> {
                return ((data[startIndex] & 0x1F) << 6) |
                        (data[startIndex + 1] & 0x3F);
            }
            case 3 -> {
                return ((data[startIndex] & 0x0F) << 12) |
                        ((data[startIndex + 1] & 0x3F) << 6) |
                        (data[startIndex + 2] & 0x3F);
            }
            case 4 -> {
                return ((data[startIndex] & 0x07) << 18) |
                        ((data[startIndex + 1] & 0x3F) << 12) |
                        ((data[startIndex + 2] & 0x3F) << 6) |
                        (data[startIndex + 3] & 0x3F);
            }
        }
        throw new IllegalArgumentException("numberOfBytes: " + numberOfBytes);
    }

    /**
     * The user-visible surface where a hide happened. Subclasses that want per-source
     * stats determine the value from the matched group plus current player/navigation state,
     * then pass it to their own tracker.
     */
    public enum Source {
        HOME, SUBSCRIPTIONS, SEARCH, COMMENTS;

        public static final Source[] VALUES = values();
    }

    /** YouTube video IDs are 11 chars from the base64-url alphabet. */
    public static final int VIDEO_ID_LENGTH = 11;

    /** Byte sequence that precedes the video ID inside the buffer's thumbnail URL. */
    public static final byte[] THUMBNAIL_URL_PREFIX =
            "https://i.ytimg.com/vi/".getBytes(StandardCharsets.US_ASCII);

    /**
     * @return the first video ID found in the buffer via the thumbnail URL prefix,
     * or {@code null} if none is present (e.g. comment threads that carry no thumbnail).
     * Callers who need a video ID in comment context should fall back to the currently
     * open player's video ID.
     */
    @Nullable
    public static String extractVideoIdFromBuffer(byte[] buffer) {
        final byte[] prefix = THUMBNAIL_URL_PREFIX;
        final int prefixLen = prefix.length;
        outer:
        for (int i = 0, max = buffer.length - prefixLen - VIDEO_ID_LENGTH; i <= max; i++) {
            for (int j = 0; j < prefixLen; j++) {
                if (buffer[i + j] != prefix[j]) continue outer;
            }
            int start = i + prefixLen;
            for (int k = 0; k < VIDEO_ID_LENGTH; k++) {
                if (!isVideoIdChar(buffer[start + k])) continue outer;
            }
            return new String(buffer, start, VIDEO_ID_LENGTH, StandardCharsets.US_ASCII);
        }
        return null;
    }

    private static boolean isVideoIdChar(byte b) {
        return (b >= 'A' && b <= 'Z')
                || (b >= 'a' && b <= 'z')
                || (b >= '0' && b <= '9')
                || b == '-' || b == '_';
    }

    /**
     * Simple non-atomic wrapper for capturing a match value from a trie callback.
     * Used because {@link java.util.concurrent.atomic.AtomicReference#setPlain(Object)}
     * is not available on Android 8.0.
     */
    public static final class MutableReference<T> {
        public T value;
    }
}
