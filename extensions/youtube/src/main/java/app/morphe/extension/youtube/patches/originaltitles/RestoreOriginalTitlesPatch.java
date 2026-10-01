/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import android.net.Uri;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Replaces the auto-translated video titles and descriptions with the original titles and descriptions,
 * or replaces the video titles with the titles submitted to DeArrow.
 * <p>
 * Responses do not include the original title, so it's fetched with {@link OriginalTitleRequest}.
 * The original description of the opened video is fetched with {@link OriginalDescriptionRequest}
 * when the video is opened, so it's available when the description panel is opened.
 * <p>
 * Elements are parsed on the main thread, where the original title cannot be fetched.
 * The element hook finds the translated titles and starts fetching the original titles.
 * If an original title is already available, the element is modified (including the
 * accessibility label). Otherwise the text hook replaces the title when it's laid out.
 */
@SuppressWarnings("unused")
public final class RestoreOriginalTitlesPatch {

    public enum TitleType {
        /**
         * Titles and descriptions as shown by YouTube.
         */
        TRANSLATED(false, false),
        /**
         * Original titles and descriptions.
         */
        ORIGINAL(true, false),
        /**
         * DeArrow titles, or the titles shown by YouTube if DeArrow has no title.
         * Descriptions as shown by YouTube.
         */
        TRANSLATED_DEARROW(false, true),
        /**
         * DeArrow titles, or the original titles if DeArrow has no title.
         * Original descriptions.
         */
        ORIGINAL_DEARROW(true, true);

        /**
         * If the original titles and descriptions are restored.
         */
        final boolean restoresOriginal;
        final boolean usesDeArrow;

        TitleType(boolean restoresOriginal, boolean usesDeArrow) {
            this.restoresOriginal = restoresOriginal;
            this.usesDeArrow = usesDeArrow;
        }

        boolean replacesTitles() {
            return restoresOriginal || usesDeArrow;
        }
    }

    static final TitleType TITLE_TYPE = Settings.RESTORE_ORIGINAL_TITLES_TYPE.get();

    private static final String DESCRIPTION_IDENTIFIER = "description_rich_text_list.eml";
    /**
     * Description of the description panel of a Short, which is a single text with its runs.
     */
    private static final String SHORTS_DESCRIPTION_IDENTIFIER = "cell_description_body.eml";

    private static final String DESCRIPTION_REDIRECT_PATH = "/redirect";

    /**
     * Title of the player overlay, which does not include the video id.
     */
    private static final String PLAYER_OVERLAY_IDENTIFIER = "player_overlay_video_heading.eml";

    /**
     * Header of the channel page, with the first line of the channel description.
     */
    private static final String CHANNEL_HEADER_IDENTIFIER = "page_header.eml";
    /**
     * Panel opened from the channel header, with the entire channel description.
     */
    private static final String CHANNEL_ABOUT_IDENTIFIER = "about_channel_view.eml";

    /**
     * State of the description body, which is in the component of the title of the opened video
     * as tapping the title expands the description, such as the title of the watch page and of a Short.
     * Other elements that open the description panel do not include it, such as the transcript section.
     */
    private static final String DESCRIPTION_BODY_STATE = "structured-description-body-state-id";

    /**
     * Top bar of the Shorts player. Its menu includes the item that opens the description panel,
     * which has the description state, but the top bar does not show the title.
     */
    private static final String SHORTS_TOP_BAR_IDENTIFIER = "top_bar.eml";

    /**
     * Accessibility id of the link of a Short to a related video, such as the full video of the Short.
     */
    private static final String SHORTS_VIDEO_LINK_ID = "id.reel_multi_format_link";

    private static final Pattern VIDEO_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{11}$");

    /**
     * Video id encoded in a key as any of the first fields, such as the key of a hashtag of a description.
     */
    private static final Pattern KEY_VIDEO_ID_PATTERN =
            Pattern.compile("[\\x0A\\x12\\x1A\\x22\\x2A]\\x0B([A-Za-z0-9_-]{11})");

    private static final Pattern HASHTAG_PATTERN = Pattern.compile("#[\\p{L}\\p{M}\\p{N}_]+");

    /**
     * Start of the key of a Short in a Shorts shelf or grid, such as 'shorts-shelf-item-videoId'.
     */
    private static final String SHORTS_ITEM_KEY_PREFIX = "shorts-shelf-item-";

    /**
     * Elements with video thumbnails, the title elements of the opened video,
     * the description of the description panel, and the channel description.
     */
    private static final ByteTrieSearch elementSearch = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes(
                    "/vi/",
                    "/vi_webp/",
                    DESCRIPTION_BODY_STATE,
                    PLAYER_OVERLAY_IDENTIFIER,
                    DESCRIPTION_IDENTIFIER,
                    SHORTS_DESCRIPTION_IDENTIFIER,
                    CHANNEL_HEADER_IDENTIFIER,
                    CHANNEL_ABOUT_IDENTIFIER
            ));

    /**
     * Shown until the original title is fetched.
     */
    private static final StringRef LOADING_TITLE = StringRef.sfc("morphe_restore_original_titles_loading");

    /**
     * Marks the Litho loading texts with the video id of the title that is loading.
     * The loading texts are laid out again when the title is fetched.
     */
    private record LoadingTitleSpan(String videoId) implements LithoRelayoutPatch.RelayoutSpan {
        @Override
        public boolean isOutdated() {
            return !OriginalTitleRequest.isPending(videoId);
        }
    }

    /**
     * Different videos can have the same title, such as a video and its reupload, and the Litho
     * texts do not include the video id. So the titles that are not yet fetched when the elements
     * are parsed are marked with the video id, and the text hook removes the marker.
     * The marker is made of invisible characters: a start character, and each character of the
     * video id as 3 digits of base 4.
     */
    private static final char TITLE_MARKER_START = '\u2060'; // Word joiner.
    private static final char TITLE_MARKER_FIRST_DIGIT = '\u2061'; // Invisible operators U+2061 to U+2064.
    private static final String VIDEO_ID_CHARACTERS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final int VIDEO_ID_LENGTH = 11;
    private static final int TITLE_MARKER_DIGITS_PER_CHARACTER = 3;
    private static final int TITLE_MARKER_LENGTH = 1 + VIDEO_ID_LENGTH * TITLE_MARKER_DIGITS_PER_CHARACTER;

    /**
     * Minimum length of a translated title that is replaced inside other texts,
     * and in elements without the video id. Short titles can match other texts, such as
     * a title that is the same as the channel name.
     */
    private static final int MIN_TITLE_LENGTH = 8;

    private static final Pattern THUMBNAIL_VIDEO_ID_PATTERN =
            Pattern.compile("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/");

    /**
     * Entity keys are base64 encoded protos, and can include the video id as field 1 or 2.
     */
    private static final Pattern ENTITY_KEY_PATTERN =
            Pattern.compile("^[A-Za-z0-9_-]{12,}(?:%3D)*$");
    private static final Pattern ENCODED_VIDEO_ID_PATTERN =
            Pattern.compile("[\\x0A\\x12]\\x0B([A-Za-z0-9_-]{11})");

    /**
     * Litho identifiers of components, such as 'video_lockup_with_attachment.eml-fe|c0c4a49b6544b5fb'.
     * Elements can start with other identifiers without a component name, such as 'theme|83f890a67c133c77'.
     */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[^\\s|]+\\.[^\\s|]+\\|[0-9a-f]{16}$");

    private static final Pattern CHANNEL_ID_PATTERN = Pattern.compile("^UC[A-Za-z0-9_-]{22}$");


    private static final Pattern LETTER_PATTERN = Pattern.compile("\\p{L}");
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s");
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[_./:=|-]");

    /**
     * Title view -> video id of the title to set.
     */
    private static final Map<TextView, String> titleViewVideoIds =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Translated title -> video ids of the videos with the title.
     * Different videos can have the same title, such as a video and its reupload.
     */
    private static final Map<String, Set<String>> translatedTitles =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Texts with the description state -> video ids of the elements with the text,
     * and video id -> texts with the description state in the elements of the video.
     * Texts other than the title can have the description state, such as the
     * description item of the Shorts menu, but those texts are the same for different videos.
     */
    private static final Map<String, Set<String>> openedTitleVideoIds =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(200));
    private static final Map<String, Set<String>> openedVideoTitles =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(200));

    /**
     * Video id -> translated title, the first title found for the video.
     * A video has a single title, so other texts found as the title of the video are not the title,
     * such as the name of a product shown with the video.
     */
    private static final Map<String, String> videoTranslatedTitles =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Text found as the title -> video id, of the texts that are the title only if they are
     * the title translated by the uploader, until the translated title is fetched.
     */
    private static final Map<String, String> pendingLocalizedTitles =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(200));

    /**
     * Length of the longest translated title, so longer texts are ignored without copying them.
     */
    private static final AtomicInteger maxTranslatedTitleLength = new AtomicInteger();

    /**
     * Title requests that lay out the loading texts again when done.
     */
    private static final Set<CompletableFuture<String>> relayoutRequests = ConcurrentHashMap.newKeySet();

    /**
     * Video id of the opened video.
     */
    private static volatile String openedVideoId;

    /**
     * Language of the app when the translated titles were found. The language can be changed
     * without restarting the app, and the translated titles of the other language are not shown anymore.
     */
    private static volatile String titlesLanguage;

    /**
     * Description preview of a channel header, and the translated description it shows.
     * The preview also includes the truncation text that opens the description panel, such as '...more'.
     */
    private record ChannelPreview(String channelId, String translatedDescription) {
    }

    /**
     * Shown text of a translated description preview -> preview.
     */
    private static final Map<String, ChannelPreview> translatedChannelPreviews =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(50));

    /**
     * Length of the longest translated description preview, so longer texts are ignored without copying them.
     */
    private static final AtomicInteger maxTranslatedChannelPreviewLength = new AtomicInteger();

    /**
     * Channel id of the opened channel page, and its translated description preview.
     * The description panel does not include the channel id.
     */
    private static volatile String openedChannelId;
    private static volatile String openedChannelPreview;

    /**
     * Marks the translated description previews of channels whose original description is not
     * yet fetched. The previews are laid out again when the original description is fetched.
     */
    private record ChannelPreviewSpan(String channelId) implements LithoRelayoutPatch.RelayoutSpan {
        @Override
        public boolean isOutdated() {
            return !OriginalChannelDescriptionRequest.isPending(channelId);
        }
    }

    /**
     * Injection point.
     */
    public static void newVideoLoaded(String videoId) {
        try {
            if (!TITLE_TYPE.replacesTitles() || videoId == null || videoId.isEmpty()) {
                return;
            }

            openedVideoId = videoId;
            if (TITLE_TYPE.restoresOriginal) {
                OriginalDescriptionRequest.fetchRequestIfNeeded(videoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoLoaded failure", ex);
        }
    }

    /**
     * Does not wait for the title to be fetched.
     *
     * @return The title that replaces the title of the video, or the title if not replaced or not yet fetched.
     */
    public static String getTitle(String videoId, String title) {
        if (!TITLE_TYPE.replacesTitles() || videoId.isEmpty()) {
            return title;
        }

        // The title and the video id can be of different videos while the video changes.
        if (!isTranslatedTitle(title.trim(), videoId)) {
            return title;
        }

        String replacement = OriginalTitleRequest.getIfAvailable(videoId);
        return replacement == null ? title : replacement;
    }

    /**
     * Injection point.
     */
    public static byte[] restoreOriginalTitle(byte[] bytes) {
        try {
            if (!TITLE_TYPE.replacesTitles() || !elementSearch.matches(bytes)) {
                return bytes;
            }
            clearIfLanguageChanged();

            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }

            List<ProtoNode> textNodes = ProtoNode.textNodes(root);
            String identifier = null;
            for (ProtoNode node : textNodes) {
                String text = node.getText();
                if (IDENTIFIER_PATTERN.matcher(text).matches()) {
                    identifier = text;
                    break;
                }
            }

            if (identifier != null) {
                final boolean isShortsDescription = identifier.startsWith(SHORTS_DESCRIPTION_IDENTIFIER);
                if (isShortsDescription || identifier.startsWith(DESCRIPTION_IDENTIFIER)) {
                    return TITLE_TYPE.restoresOriginal && restoreDescription(root, textNodes, isShortsDescription)
                            ? ProtoNode.write(root) : bytes;
                }
                if (identifier.startsWith(CHANNEL_HEADER_IDENTIFIER)) {
                    return TITLE_TYPE.restoresOriginal && restoreChannelHeader(textNodes)
                            ? ProtoNode.write(root) : bytes;
                }
                if (identifier.startsWith(CHANNEL_ABOUT_IDENTIFIER)) {
                    return TITLE_TYPE.restoresOriginal && restoreChannelAbout(root)
                            ? ProtoNode.write(root) : bytes;
                }
            }

            Set<String> thumbnailVideoIds = new HashSet<>();
            for (ProtoNode node : textNodes) {
                addThumbnailVideoIds(node.getText(), thumbnailVideoIds);
            }
            Map<List<ProtoNode>, Set<String>> messageVideoIds = new IdentityHashMap<>();
            findVideoIds(root, thumbnailVideoIds, messageVideoIds);
            String component = identifier == null ? "" : identifier.substring(0, identifier.indexOf('|'));
            boolean modified = restoreVideoTitles(root, messageVideoIds, component);
            if (messageVideoIds.get(root).isEmpty()) {
                // Elements of the opened video that do not include the video id, such as the watch page title.
                String videoId = openedVideoId;
                if (videoId != null) {
                    modified |= restoreVideoTitle(root, videoId, false, component);
                }
            }
            modified |= restoreLinkedVideoTitles(textNodes);

            // Replaces titles seen before in other elements, such as the player overlay title
            // that does not include the video id but shows the same title as the watch page.
            // The player overlay shows the title of the opened video.
            String preferredVideoId = identifier != null && identifier.startsWith(PLAYER_OVERLAY_IDENTIFIER)
                    ? openedVideoId
                    : null;
            for (ProtoNode node : textNodes) {
                String nodeText = node.getText();
                if (findTitleMarker(nodeText) >= 0) {
                    continue;
                }
                String text = nodeText.trim();
                String videoId = text.length() >= MIN_TITLE_LENGTH
                        ? findVideoIdOfTitle(text, preferredVideoId)
                        : null;
                if (videoId == null) {
                    continue;
                }

                String originalTitle = OriginalTitleRequest.getIfAvailable(videoId);
                if (originalTitle != null && !originalTitle.trim().equals(text) && !isChannelName(videoId, text)) {
                    modified |= replaceTitle(textNodes, text, Collections.emptySet(), originalTitle, null);
                }
            }

            return modified ? ProtoNode.write(root) : bytes;
        } catch (Exception ex) {
            Logger.printException(() -> "restoreOriginalTitle failure", ex);
        }

        return bytes;
    }

    /**
     * Clears the translated titles and descriptions found in another language.
     * The original titles and descriptions do not depend on the language, and are kept.
     */
    private static void clearIfLanguageChanged() {
        String language = Locale.getDefault().toLanguageTag();
        String previousLanguage = titlesLanguage;
        titlesLanguage = language;
        if (previousLanguage == null || previousLanguage.equals(language)) {
            return;
        }

        Logger.printDebug(() -> "Language changed from: " + previousLanguage + " to: " + language);
        translatedTitles.clear();
        maxTranslatedTitleLength.set(0);
        videoTranslatedTitles.clear();
        synchronized (openedTitleVideoIds) {
            openedTitleVideoIds.clear();
            openedVideoTitles.clear();
        }
        translatedChannelPreviews.clear();
        maxTranslatedChannelPreviewLength.set(0);
        openedChannelPreview = null;
        pendingLocalizedTitles.clear();
        TitleLayouts.clearCandidates();
    }

    /**
     * Injection point.
     * <p>
     * Called when a Litho text is created or reused. Usually called off the main thread.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence text) {
        try {
            if (!TITLE_TYPE.replacesTitles() || text == null) {
                return text;
            }

            CharSequence channelPreview = restoreChannelPreview(text);
            if (channelPreview != null) {
                return channelPreview;
            }

            CharSequence translatedText = text;
            String videoId;
            final int markerStart = findTitleMarker(text);
            if (markerStart >= 0) {
                translatedText = text.subSequence(0, markerStart);
                videoId = decodeTitleMarker(text, markerStart);
                // The title can be found to be another text after the element was marked.
                if (videoId != null && !isTranslatedTitle(translatedText.toString().trim(), videoId)) {
                    CharSequence loading = loadingLocalizedTitle(translatedText, translatedText.toString().trim(), videoId);
                    if (loading != null) {
                        return loading;
                    }
                    videoId = null;
                }
            } else {
                // Texts without the marker can be any text, such as a comment or a user name.
                // Short texts are more likely to be the same as a title by chance.
                String trimmedText = text.toString().trim();
                if (trimmedText.length() < MIN_TITLE_LENGTH) {
                    return text;
                }
                videoId = trimmedText.length() <= maxTranslatedTitleLength.get()
                        ? findVideoIdOfTitle(trimmedText, openedVideoId)
                        : null;
                if (videoId == null) {
                    // The text can be the title of an element whose title is not yet found.
                    CharSequence loading = loadingLocalizedTitle(text, trimmedText, null);
                    if (loading != null) {
                        return loading;
                    }
                    String unresolvedVideoId = TitleLayouts.findUnresolvedVideoId(trimmedText);
                    return unresolvedVideoId == null
                            ? text
                            : spannedText(text, text, new UnresolvedTitleSpan(trimmedText, unresolvedVideoId));
                }
            }
            if (videoId == null) {
                return translatedText;
            }

            // Litho texts are loaded on a single thread, so the title is not waited for.
            String translatedTitle = translatedText.toString();
            if (isChannelName(videoId, translatedTitle.trim())) {
                return translatedText;
            }
            String originalTitle = OriginalTitleRequest.getIfAvailable(videoId);
            final boolean loading = originalTitle == null;
            if (loading) {
                if (!OriginalTitleRequest.isPending(videoId)) {
                    return translatedText;
                }
                originalTitle = LOADING_TITLE.toString();
                relayoutWhenFetched(OriginalTitleRequest.fetch(videoId));
            }
            if (originalTitle.trim().equals(translatedTitle.trim())) {
                return translatedText;
            }

            return spannedText(translatedText, originalTitle, loading ? new LoadingTitleSpan(videoId) : null);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }

        return text;
    }

    /**
     * Marks the Litho texts of elements whose title is not yet found, if the text can be the title.
     * The texts are laid out again if the text is found to be the title, such as with the title path
     * learned for the layout of the element after the element was parsed.
     */
    private record UnresolvedTitleSpan(String title, String videoId) implements LithoRelayoutPatch.RelayoutSpan {
        @Override
        public boolean isOutdated() {
            return isTranslatedTitle(title, videoId);
        }
    }

    /**
     * Marks the loading texts of the titles that are confirmed with the title translated by the uploader.
     * The texts are laid out again when the translated title is fetched, whether the text is the title or not.
     */
    private record LocalizedTitleSpan(String title) implements LithoRelayoutPatch.RelayoutSpan {
        @Override
        public boolean isOutdated() {
            return !pendingLocalizedTitles.containsKey(title);
        }
    }

    /**
     * @return The loading title of a text that is confirmed with the title translated by the uploader,
     *         or null if the text is not being confirmed.
     */
    @Nullable
    private static CharSequence loadingLocalizedTitle(CharSequence text, String title, @Nullable String videoId) {
        String pendingVideoId = pendingLocalizedTitles.get(title);
        if (pendingVideoId == null || (videoId != null && !videoId.equals(pendingVideoId))) {
            return null;
        }
        return spannedText(text, LOADING_TITLE.toString(), new LocalizedTitleSpan(title));
    }

    /**
     * Only spans that style the entire text can be applied to a different text.
     *
     * @param relayoutSpan Span of the entire new text, or null if none.
     * @return The new text with the spans of the text that style the entire text.
     */
    private static SpannableString spannedText(CharSequence text, CharSequence newText,
                                               @Nullable LithoRelayoutPatch.RelayoutSpan relayoutSpan) {
        SpannableString spannedText = new SpannableString(newText);
        final int newLength = spannedText.length();
        if (newText != text && text instanceof Spanned spanned) {
            final int length = spanned.length();
            for (Object span : spanned.getSpans(0, length, Object.class)) {
                if (spanned.getSpanStart(span) == 0 && spanned.getSpanEnd(span) == length) {
                    spannedText.setSpan(span, 0, newLength, spanned.getSpanFlags(span));
                }
            }
        }
        if (relayoutSpan != null) {
            spannedText.setSpan(relayoutSpan, 0, newLength, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return spannedText;
    }

    /**
     * Replaces the description preview of the channel header when it's laid out,
     * if the original description was not yet fetched when the element was parsed.
     * If the original description is still not fetched, the preview is laid out again when fetched.
     *
     * @return The original description preview, the translated preview marked to be laid out again,
     *         or null if the text is not a translated preview or the original description is the same.
     */
    @Nullable
    private static CharSequence restoreChannelPreview(CharSequence text) {
        if (text.length() > maxTranslatedChannelPreviewLength.get()) {
            return null;
        }

        String translatedText = text.toString();
        ChannelPreview preview = translatedChannelPreviews.get(translatedText.trim());
        if (preview == null) {
            return null;
        }

        String channelId = preview.channelId();
        // A request that failed is fetched again, but the loading text is shown only once,
        // so a failing network does not keep loading.
        final boolean wasPending = OriginalChannelDescriptionRequest.isPending(channelId);
        String originalPreview = getOriginalChannelPreview(channelId);
        if (originalPreview == null) {
            if (!wasPending || !OriginalChannelDescriptionRequest.isPending(channelId)) {
                return null;
            }
            relayoutWhenFetched(OriginalChannelDescriptionRequest.fetch(channelId));
            return spannedText(text, LOADING_TITLE.toString(), new ChannelPreviewSpan(channelId));
        }
        if (originalPreview.equals(preview.translatedDescription())) {
            return null;
        }

        return spannedText(text, translatedText.replace(preview.translatedDescription(), originalPreview), null);
    }

    /**
     * Injection point.
     * <p>
     * Sets the original title of a view that shows the title of a video,
     * such as a video of the playlist panel.
     */
    public static void restoreOriginalTitle(TextView view, String videoId) {
        try {
            if (!TITLE_TYPE.replacesTitles() || view == null || videoId == null) {
                return;
            }

            CharSequence translatedTitle = view.getText();
            String translatedTitleText = translatedTitle.toString().trim();
            // Reused views can still show the loading title.
            if (!translatedTitleText.isEmpty() && !translatedTitleText.equals(LOADING_TITLE.toString())) {
                putTranslatedTitle(translatedTitleText, Collections.emptySet(), videoId);
            }

            // Views are reused for other videos, so the title is only set if the view
            // still shows the same video when the title is fetched.
            titleViewVideoIds.put(view, videoId);

            if (OriginalTitleRequest.getIfAvailable(videoId) == null && OriginalTitleRequest.isPending(videoId)) {
                view.setText(LOADING_TITLE.toString());
            }

            // The title is null if the video has no available title, or if it failed to fetch.
            WeakReference<TextView> viewRef = new WeakReference<>(view);
            OriginalTitleRequest.fetch(videoId).thenAccept(originalTitle -> Utils.runOnMainThreadNowOrLater(() -> {
                TextView titleView = viewRef.get();
                if (titleView == null || !titleViewVideoIds.remove(titleView, videoId)) {
                    return;
                }
                CharSequence title = originalTitle == null ? translatedTitle : originalTitle;
                // Setting the same text again would notify the text listeners again.
                if (!TextUtils.equals(title, titleView.getText())) {
                    titleView.setText(title);
                }
            }));
        } catch (Exception ex) {
            Logger.printException(() -> "restoreOriginalTitle failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Replaces the translated titles seen before in other views or elements,
     * for a view that shows a title without the video id.
     */
    public static void restoreKnownTitles(TextView view) {
        if (view == null) {
            return;
        }

        view.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!TITLE_TYPE.replacesTitles()) {
                    return;
                }

                String title = text.toString().trim();
                if (title.equals(LOADING_TITLE.toString())) {
                    return;
                }

                String videoId = findVideoIdOfTitle(title, null);
                if (videoId == null) {
                    titleViewVideoIds.remove(view);
                    return;
                }

                // The text cannot be changed while the listeners are notified.
                view.post(() -> restoreOriginalTitle(view, videoId));
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
    }

    /**
     * @param isShortsDescription If the description is of a Short, which is a single text with its runs
     *                            instead of a list of segments.
     * @return If the description was replaced.
     */
    private static boolean restoreDescription(List<ProtoNode> root, List<ProtoNode> textNodes,
                                              boolean isShortsDescription) {
        String videoId = openedVideoId;
        if (videoId == null) {
            return false;
        }

        // Links of the description are redirects that include the video id.
        for (ProtoNode node : textNodes) {
            Uri uri = Uri.parse(node.getText());
            if (DESCRIPTION_REDIRECT_PATH.equals(uri.getPath()) && uri.getHost() != null
                    && !videoId.equals(uri.getQueryParameter("v"))) {
                return false;
            }
        }

        String originalDescription = OriginalDescriptionRequest.fetchRequestIfNeeded(videoId)
                .getDescriptionIfFetched();
        if (originalDescription == null) {
            Logger.printDebug(() -> "Original description is not yet available for: " + videoId);
            return false;
        }
        if (!isShortsDescription) {
            return OriginalDescription.restore(root, originalDescription);
        }

        ProtoNode description = findShortsDescription(root, null);
        if (description == null || description.decodeUtf8().equals(originalDescription)
                || !isShortsDescriptionOfVideo(textNodes, description.decodeUtf8(), originalDescription, videoId)) {
            return false;
        }
        OriginalDescription.restore(description, originalDescription);
        Logger.printDebug(() -> "Restored description of Short: " + videoId);
        return true;
    }

    /**
     * The description panel does not include the video id, and the opened video can change before
     * the panel is parsed. The keys of the hashtags and links of the description can include the
     * video id, and the hashtags are not translated, so they must be the same in the original description.
     *
     * @return If the description is of the video.
     */
    private static boolean isShortsDescriptionOfVideo(List<ProtoNode> textNodes, String shownDescription,
                                                      String originalDescription, String videoId) {
        Set<String> keyVideoIds = new HashSet<>();
        for (ProtoNode node : textNodes) {
            String text = node.getText();
            if (!ENTITY_KEY_PATTERN.matcher(text).matches()) {
                continue;
            }
            try {
                byte[] decoded = Base64.decode(text.replace("%3D", ""),
                        Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                Matcher matcher = KEY_VIDEO_ID_PATTERN.matcher(new String(decoded, StandardCharsets.ISO_8859_1));
                while (matcher.find()) {
                    keyVideoIds.add(matcher.group(1));
                }
            } catch (IllegalArgumentException ignored) {
                // Not base64.
            }
        }
        if (!keyVideoIds.isEmpty() && !keyVideoIds.contains(videoId)) {
            Logger.printDebug(() -> "Description of Short is of other videos: " + keyVideoIds + " opened: " + videoId);
            return false;
        }

        Set<String> originalHashtags = new HashSet<>();
        Matcher originalMatcher = HASHTAG_PATTERN.matcher(originalDescription.toLowerCase(Locale.ROOT));
        while (originalMatcher.find()) {
            originalHashtags.add(originalMatcher.group());
        }
        Matcher shownMatcher = HASHTAG_PATTERN.matcher(shownDescription.toLowerCase(Locale.ROOT));
        while (shownMatcher.find()) {
            if (!originalHashtags.contains(shownMatcher.group())) {
                final String hashtag = shownMatcher.group();
                Logger.printDebug(() -> "Description of Short has hashtag not in the original: " + hashtag);
                return false;
            }
        }
        return true;
    }

    /**
     * The description of a Short is the content of a text, and the other texts of the element
     * are keys, identifiers and names. Paths of the fields depend on the app version.
     *
     * @return The longest content of a text that can be shown, or null if none.
     */
    @Nullable
    private static ProtoNode findShortsDescription(List<ProtoNode> message, @Nullable ProtoNode description) {
        for (ProtoNode field : message) {
            if (field.children != null) {
                description = findShortsDescription(field.children, description);
                continue;
            }
            if (field.getFieldNumber() != OriginalDescription.TEXT_CONTENT_FIELD || field.getVarint() != null) {
                continue;
            }
            String text = OriginalDescription.decodeText(field);
            if (text != null && isLabelCandidate(text) && !ENTITY_KEY_PATTERN.matcher(text).matches()
                    && (description == null || text.length() > description.decodeUtf8().length())) {
                description = field;
            }
        }
        return description;
    }

    /**
     * Lays out the outdated Litho texts again when the request is done, such as the texts
     * that show the loading title, or the translated description preview of a channel.
     * Each request lays out the texts once, including requests made again after a failure.
     */
    private static void relayoutWhenFetched(CompletableFuture<String> request) {
        if (!relayoutRequests.add(request)) {
            return;
        }

        request.thenRun(() -> {
            relayoutRequests.remove(request);
            LithoRelayoutPatch.relayoutOutdatedTexts();
        });
    }

    /**
     * Replaces the description preview of the channel header, and its accessibility label.
     * If the original description is not yet fetched, the preview is replaced
     * by the text hook when the header is laid out again.
     *
     * @return If the preview was replaced.
     */
    private static boolean restoreChannelHeader(List<ProtoNode> textNodes) {
        // The preview is the first line of the description, and the preview message includes
        // the accessibility label of the preview, which includes the start of the preview and other texts.
        // The label is used to find the preview, as the label and the preview texts are localized.
        ProtoNode preview = null;
        ProtoNode label = null;
        int longestPrefix = MIN_TITLE_LENGTH - 1;
        for (ProtoNode node : textNodes) {
            String text = node.getText().trim();
            ProtoNode previewText = node.getParent();
            ProtoNode previewMessage = previewText == null ? null : previewText.getParent();
            // Keys can also be included at the start of other texts of the preview message.
            if (!isLabelCandidate(text) || ENTITY_KEY_PATTERN.matcher(text).matches()
                    || previewMessage == null || previewMessage.children == null) {
                continue;
            }
            for (ProtoNode labelNode : ProtoNode.textNodes(previewMessage.children)) {
                String labelText = labelNode.getText();
                final int prefixLength = previewPrefixLength(labelText, text);
                // The label also includes other texts, such as 'Description' and 'Tap to read more'.
                // Copies of the same text, such as copies of the label, are not a preview and its label.
                if (prefixLength > longestPrefix && prefixLength < labelText.trim().length()) {
                    preview = node;
                    label = labelNode;
                    longestPrefix = prefixLength;
                }
            }
        }
        if (preview == null) {
            Logger.printDebug(() -> "Channel description preview not found");
            return false;
        }

        // The preview message includes the truncation text and the command that opens
        // the description panel of the channel.
        ProtoNode previewText = preview.getParent();
        ProtoNode previewMessage = previewText == null ? null : previewText.getParent();
        List<ProtoNode> previewTextNodes = previewMessage == null || previewMessage.children == null
                ? textNodes
                : ProtoNode.textNodes(previewMessage.children);

        String channelId = null;
        for (List<ProtoNode> nodes : List.of(previewTextNodes, textNodes)) {
            for (ProtoNode node : nodes) {
                if (CHANNEL_ID_PATTERN.matcher(node.getText()).matches()) {
                    channelId = node.getText();
                    break;
                }
            }
            if (channelId != null) {
                break;
            }
        }
        if (channelId == null) {
            return false;
        }

        // The preview ends with the localized truncation text that opens the description panel,
        // such as '...more', which is also a text of the preview message.
        // The truncation text is the longest other text of the preview message that ends the preview.
        String shownPreview = preview.getText();
        int truncationLength = 0;
        for (ProtoNode node : previewTextNodes) {
            String text = node.getText();
            if (node != preview && !text.trim().isEmpty() && text.length() < shownPreview.length()
                    && shownPreview.endsWith(text) && text.length() > truncationLength) {
                truncationLength = text.length();
            }
        }
        String translatedPreview = shownPreview.substring(0, shownPreview.length() - truncationLength).trim();

        openedChannelId = channelId;
        openedChannelPreview = translatedPreview;
        translatedChannelPreviews.put(shownPreview.trim(), new ChannelPreview(channelId, translatedPreview));
        maxTranslatedChannelPreviewLength.accumulateAndGet(shownPreview.length(), Math::max);

        String originalPreview = getOriginalChannelPreview(channelId);
        if (originalPreview == null) {
            relayoutWhenFetched(OriginalChannelDescriptionRequest.fetch(channelId));
            return false;
        }
        if (originalPreview.equals(translatedPreview)) {
            return false;
        }

        OriginalDescription.restore(preview, shownPreview.replace(translatedPreview, originalPreview));

        // Replaces the start of the preview in the accessibility label,
        // which can be truncated, such as 'Description. First line of the descr… Tap to read more.'
        String labelText = label.getText();
        final int prefixLength = previewPrefixLength(labelText, translatedPreview);
        if (prefixLength > 0) {
            final int prefixStart = labelText.indexOf(translatedPreview.substring(0, prefixLength));
            int restoredLength = prefixLength == translatedPreview.length()
                    ? originalPreview.length()
                    : Math.min(prefixLength, originalPreview.length());
            if (restoredLength < originalPreview.length()
                    && Character.isHighSurrogate(originalPreview.charAt(restoredLength - 1))) {
                restoredLength--;
            }
            label.setText(labelText.substring(0, prefixStart)
                    + originalPreview.substring(0, restoredLength)
                    + labelText.substring(prefixStart + prefixLength));
        }

        final String restoredChannelId = channelId;
        Logger.printDebug(() -> "Restored description preview of channel: " + restoredChannelId);
        return true;
    }

    /**
     * @return The length of the start of the preview that the label includes,
     *         or 0 if the label does not include the start of the preview.
     */
    private static int previewPrefixLength(String label, String preview) {
        if (preview.length() < MIN_TITLE_LENGTH) {
            return 0;
        }
        final int start = label.indexOf(preview.substring(0, MIN_TITLE_LENGTH));
        if (start < 0) {
            return 0;
        }
        int length = MIN_TITLE_LENGTH;
        while (length < preview.length() && start + length < label.length()
                && label.charAt(start + length) == preview.charAt(length)) {
            length++;
        }
        return length;
    }

    /**
     * Replaces the description of the panel opened from the channel header,
     * which starts with the translated preview of the header.
     *
     * @return If the description was replaced.
     */
    private static boolean restoreChannelAbout(List<ProtoNode> root) {
        String channelId = openedChannelId;
        String translatedPreview = openedChannelPreview;
        if (channelId == null || translatedPreview == null) {
            return false;
        }

        String originalDescription = OriginalChannelDescriptionRequest.getIfAvailable(channelId);
        if (originalDescription == null) {
            Logger.printDebug(() -> "Original description is not yet available for channel: " + channelId);
            return false;
        }

        ProtoNode description = findContentStartingWith(root, translatedPreview);
        if (description == null || description.decodeUtf8().trim().equals(originalDescription)) {
            return false;
        }

        OriginalDescription.restore(description, originalDescription);
        Logger.printDebug(() -> "Restored description of channel: " + channelId);
        return true;
    }

    /**
     * Texts with line breaks, such as a description with more than one line, are not text nodes,
     * as control characters are not parsed as text.
     *
     * @return The first length delimited field that is not a message and starts with the text.
     */
    @Nullable
    private static ProtoNode findContentStartingWith(List<ProtoNode> message, String text) {
        for (ProtoNode node : message) {
            List<ProtoNode> children = node.children;
            if (children != null) {
                ProtoNode content = findContentStartingWith(children, text);
                if (content != null) {
                    return content;
                }
            } else if (node.getVarint() == null && node.decodeUtf8().trim().startsWith(text)) {
                return node;
            }
        }
        return null;
    }

    /**
     * @return The first line of the original channel description,
     *         or null if not yet fetched or the channel has no description.
     */
    @Nullable
    private static String getOriginalChannelPreview(String channelId) {
        String description = OriginalChannelDescriptionRequest.getIfAvailable(channelId);
        if (description == null) {
            return null;
        }
        for (String line : description.split("\n")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                return trimmed;
            }
        }
        return null;
    }

    /**
     * @return If the code point is part of a word in any script: a letter, a digit,
     *         or a combining mark such as the vowel signs of Indic scripts.
     */
    static boolean isWordCodePoint(int codePoint) {
        if (Character.isLetterOrDigit(codePoint)) {
            return true;
        }
        final int type = Character.getType(codePoint);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    /**
     * Saves the translated title of the video, and the texts that are the title truncated with an ellipsis.
     * Elements can show the title truncated, such as 'Start of the title…', and the entire title
     * in other texts. The truncated title can also be in other texts, such as the accessibility label.
     *
     * @param texts Texts of the element of the title.
     * @return The texts that are the title truncated.
     */
    private static Set<String> putTranslatedTitle(String translatedTitle, Set<String> texts, String videoId) {
        Set<String> truncatedTitles = new HashSet<>();
        for (String text : texts) {
            if (isTruncatedTitle(text, translatedTitle)) {
                truncatedTitles.add(text);
            }
        }

        List<String> titles = new ArrayList<>(truncatedTitles);
        titles.add(translatedTitle);
        for (String title : titles) {
            synchronized (translatedTitles) {
                translatedTitles.computeIfAbsent(title, key -> new HashSet<>()).add(videoId);
            }
            maxTranslatedTitleLength.accumulateAndGet(title.length(), Math::max);
        }
        return truncatedTitles;
    }

    private static void removeTranslatedTitle(String translatedTitle, String videoId) {
        synchronized (translatedTitles) {
            Set<String> videoIds = translatedTitles.get(translatedTitle);
            if (videoIds != null) {
                videoIds.remove(videoId);
                if (videoIds.isEmpty()) {
                    translatedTitles.remove(translatedTitle);
                }
            }
        }
    }

    private static boolean isTranslatedTitle(String translatedTitle, String videoId) {
        synchronized (translatedTitles) {
            Set<String> videoIds = translatedTitles.get(translatedTitle);
            return videoIds != null && videoIds.contains(videoId);
        }
    }

    /**
     * @param preferredVideoId Video used if different videos have the title, such as the opened video.
     * @return The video id of the translated title, or null if the title is unknown,
     *         or different videos have the title and none is the preferred video.
     */
    @Nullable
    private static String findVideoIdOfTitle(String translatedTitle, @Nullable String preferredVideoId) {
        synchronized (translatedTitles) {
            Set<String> videoIds = translatedTitles.get(translatedTitle);
            if (videoIds == null) {
                return null;
            }
            if (videoIds.size() == 1) {
                return videoIds.iterator().next();
            }
            return preferredVideoId != null && videoIds.contains(preferredVideoId)
                    ? preferredVideoId
                    : null;
        }
    }

    /**
     * @return The title with the marker of the video id.
     */
    private static String addTitleMarker(String title, String videoId) {
        StringBuilder builder = new StringBuilder(title.length() + TITLE_MARKER_LENGTH)
                .append(title)
                .append(TITLE_MARKER_START);
        for (int i = 0; i < VIDEO_ID_LENGTH; i++) {
            final int value = VIDEO_ID_CHARACTERS.indexOf(videoId.charAt(i));
            for (int shift = 2 * (TITLE_MARKER_DIGITS_PER_CHARACTER - 1); shift >= 0; shift -= 2) {
                builder.append((char) (TITLE_MARKER_FIRST_DIGIT + ((value >> shift) & 3)));
            }
        }
        return builder.toString();
    }

    /**
     * @return The index of the title marker at the end of the text, or -1 if the text has no marker.
     */
    private static int findTitleMarker(CharSequence text) {
        final int markerStart = text.length() - TITLE_MARKER_LENGTH;
        return markerStart >= 0 && text.charAt(markerStart) == TITLE_MARKER_START
                ? markerStart
                : -1;
    }

    /**
     * @return The video id of the title marker, or null if the marker is not valid.
     */
    @Nullable
    private static String decodeTitleMarker(CharSequence text, int markerStart) {
        char[] videoId = new char[VIDEO_ID_LENGTH];
        int index = markerStart + 1;
        for (int i = 0; i < VIDEO_ID_LENGTH; i++) {
            int value = 0;
            for (int digit = 0; digit < TITLE_MARKER_DIGITS_PER_CHARACTER; digit++) {
                final int digitValue = text.charAt(index++) - TITLE_MARKER_FIRST_DIGIT;
                if (digitValue < 0 || digitValue > 3) {
                    return null;
                }
                value = (value << 2) | digitValue;
            }
            videoId[i] = VIDEO_ID_CHARACTERS.charAt(value);
        }
        return new String(videoId);
    }

    private static String removeTitleMarker(String text) {
        final int markerStart = findTitleMarker(text);
        return markerStart < 0 ? text : text.substring(0, markerStart);
    }

    /**
     * Marks the texts that are the title with the video id, so the text hook finds the video
     * of the title. Texts that include the title, such as the accessibility label, are not marked.
     *
     * @return If any text was marked.
     */
    private static boolean markTitle(List<ProtoNode> textNodes, String translatedTitle,
                                     Set<String> truncatedTitles, String videoId) {
        boolean marked = false;
        for (ProtoNode node : textNodes) {
            String nodeText = node.getText();
            String text = nodeText.trim();
            if (findTitleMarker(nodeText) < 0 && (text.equals(translatedTitle) || truncatedTitles.contains(text))) {
                node.setText(addTitleMarker(nodeText, videoId));
                marked = true;
            }
        }
        return marked;
    }

    private static void addThumbnailVideoIds(String text, Set<String> videoIds) {
        if (text.contains("/vi")) {
            Matcher matcher = THUMBNAIL_VIDEO_ID_PATTERN.matcher(text);
            while (matcher.find()) {
                videoIds.add(matcher.group(1));
            }
        }
    }

    /**
     * Finds the video ids of the message and all sub messages. Thumbnail urls and video id fields
     * are used, or if the element has no thumbnails then the video ids encoded in entity keys.
     *
     * @param messageVideoIds Message -> video ids, of the message and all sub messages.
     */
    private static Set<String> findVideoIds(List<ProtoNode> message, Set<String> thumbnailVideoIds,
                                            Map<List<ProtoNode>, Set<String>> messageVideoIds) {
        Set<String> videoIds = new HashSet<>();

        for (ProtoNode node : message) {
            List<ProtoNode> children = node.children;
            if (children != null) {
                videoIds.addAll(findVideoIds(children, thumbnailVideoIds, messageVideoIds));
                continue;
            }

            if (!node.isText()) {
                continue;
            }
            String text = node.getText();
            if (!thumbnailVideoIds.isEmpty()) {
                if (thumbnailVideoIds.contains(text)) {
                    videoIds.add(text);
                } else {
                    addThumbnailVideoIds(text, videoIds);
                }
            } else if (ENTITY_KEY_PATTERN.matcher(text).matches()) {
                try {
                    byte[] decoded = Base64.decode(text.replace("%3D", ""),
                            Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
                    Matcher matcher = ENCODED_VIDEO_ID_PATTERN.matcher(
                            new String(decoded, StandardCharsets.ISO_8859_1));
                    while (matcher.find()) {
                        videoIds.add(matcher.group(1));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Not base64.
                }
            }
        }

        messageVideoIds.put(message, videoIds);
        return videoIds;
    }

    /**
     * Restores the title of the largest parts of the message that each show a single video,
     * such as a feed video, a Short in a Shorts shelf, or the watch page title.
     *
     * @return If any title was replaced.
     */
    private static boolean restoreVideoTitles(List<ProtoNode> message,
                                              Map<List<ProtoNode>, Set<String>> messageVideoIds,
                                              String component) {
        Set<String> videoIds = messageVideoIds.get(message);
        if (videoIds == null || videoIds.isEmpty()) {
            return false;
        }
        if (videoIds.size() == 1) {
            return restoreVideoTitle(message, videoIds.iterator().next(), true, component);
        }

        boolean modified = false;
        for (ProtoNode node : message) {
            List<ProtoNode> children = node.children;
            if (children != null) {
                modified |= restoreVideoTitles(children, messageVideoIds, component);
            }
        }
        return modified;
    }

    /**
     * The title is found in order of reliability: a title seen before for the video,
     * the title of the opened video that expands the description, the title that starts
     * the accessibility label of the video, or the title at the field path learned by
     * {@link TitleLayouts} for the elements without an accessibility label.
     * The opened video title is found before the labeled title, as the element can include
     * other labels that look like a video label, such as the channel label of an artist
     * '@handle, Official Artist Channel' of a Short.
     *
     * @param elementVideoId If the video id is included in the message. Otherwise the video
     *                       is the opened video, and only titles of the opened video are found.
     * @param component      Name of the Litho component of the element.
     * @return If the title was replaced.
     */
    private static boolean restoreVideoTitle(List<ProtoNode> message, String videoId,
                                             boolean elementVideoId, String component) {
        List<ProtoNode> textNodes = ProtoNode.textNodes(message);
        // Texts of the text nodes without the title markers, and the texts that are not empty.
        List<String> nodeTexts = new ArrayList<>(textNodes.size());
        Set<String> texts = new LinkedHashSet<>();
        for (ProtoNode node : textNodes) {
            String text = removeTitleMarker(node.getText()).trim();
            if (isListUrl(text)) {
                return false;
            }
            nodeTexts.add(text);
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }

        // The longest text that is a title seen before for the video.
        String title = null;
        synchronized (translatedTitles) {
            for (String text : texts) {
                Set<String> videoIds = translatedTitles.get(text);
                if (videoIds != null && videoIds.contains(videoId)
                        && (title == null || text.length() > title.length())) {
                    title = text;
                }
            }
        }
        if (title == null && !component.startsWith(SHORTS_TOP_BAR_IDENTIFIER)) {
            title = findOpenedVideoTitle(textNodes, texts);
            if (title != null) {
                // The opened video can change before the element of the new video is parsed.
                String knownVideoId = elementVideoId ? null : findVideoIdOfTitle(title, videoId);
                if ((knownVideoId != null && !knownVideoId.equals(videoId)) || !isOpenedVideoTitle(title, videoId)) {
                    return false;
                }
            }
        }
        // Accessibility label that starts with the title, or null if none.
        String label;
        List<String> paths = null;
        if (title != null) {
            label = findLabelOfTitle(title, texts);
        } else {
            if (!elementVideoId) {
                return false;
            }

            String[] labelAndTitle = findLabeledTitle(texts);
            String labeledLabel = labelAndTitle == null ? null : labelAndTitle[0];
            String labeledTitle = labelAndTitle == null ? null : labelAndTitle[1];
            if (labeledTitle != null && isLabelOfTitle(textNodes, labeledLabel, labeledTitle)) {
                // The label is replaced as a label that starts with the title only if it does.
                label = labeledLabel.startsWith(labeledTitle) ? labeledLabel : null;
                title = labeledTitle;
                // The label of a long title can include the title truncated, and the element the entire title.
                // Texts that include the truncated title with the ellipsis are not the entire title,
                // such as the accessibility label 'Start of the title… - play Short'.
                for (String text : texts) {
                    if (isTruncatedTitle(labeledTitle, text) && !text.contains(labeledTitle)) {
                        label = null;
                        title = text;
                        break;
                    }
                }
            } else {
                // The title at the title path learned for the component.
                label = null;
                paths = TitleLayouts.fieldPaths(textNodes);
                title = TitleLayouts.findTitle(component, paths, nodeTexts);
                if (title == null || !isLabelCandidate(title) || isChannelHandle(title, texts)) {
                    // Saves the texts of the element, so the title path of the component is learned
                    // if the title is not translated. Fetching the original title of the video
                    // finds which text is the title.
                    if (TITLE_TYPE.restoresOriginal) {
                        TitleLayouts.addCandidates(component, videoId, paths, nodeTexts);
                        OriginalTitleRequest.OriginalVideo originalVideo = OriginalTitleRequest.getOriginalIfFetched(videoId);
                        if (originalVideo != null) {
                            TitleLayouts.originalTitleFetched(videoId, originalVideo.title());
                        } else {
                            OriginalTitleRequest.fetch(videoId);
                        }
                    }
                    return false;
                }
            }

            // Titles that are not seen before or the title of the opened video
            // can be other texts, such as the name of the channel.
            OriginalTitleRequest.requireChannelName(videoId);
        }

        if (isChannelName(videoId, title)) {
            return false;
        }
        if (!isTitleOfVideo(videoId, title)) {
            // The text hook shows the loading title until the title is confirmed.
            return videoId.equals(pendingLocalizedTitles.get(title))
                    && markTitle(textNodes, title, Collections.emptySet(), videoId);
        }
        if (elementVideoId) {
            TitleLayouts.confirmTitle(component, videoId,
                    paths == null ? TitleLayouts.fieldPaths(textNodes) : paths, nodeTexts, title);
        }

        // Titles are saved even if not replaced, so titles of different videos are known.
        Set<String> truncatedTitles = putTranslatedTitle(title, texts, videoId);

        String originalTitle = OriginalTitleRequest.getIfAvailable(videoId);
        if (originalTitle == null) {
            // The text hook replaces the title when the element is laid out.
            return OriginalTitleRequest.isPending(videoId)
                    && markTitle(textNodes, title, truncatedTitles, videoId);
        }
        return !title.equals(originalTitle.trim())
                && replaceTitle(textNodes, title, truncatedTitles, originalTitle, label);
    }

    /**
     * The link of a Short to a related video includes the video id as a text, and not in a thumbnail
     * or in a key, so the video of the element is the Short. The title of the link is found in the
     * smallest message that includes the link and a video id, if the message includes a single
     * video id and a single text that can be a title.
     *
     * @return If any title was replaced.
     */
    private static boolean restoreLinkedVideoTitles(List<ProtoNode> textNodes) {
        boolean modified = false;
        for (ProtoNode node : textNodes) {
            if (!SHORTS_VIDEO_LINK_ID.equals(node.getText())) {
                continue;
            }
            for (ProtoNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
                if (parent.children == null) {
                    continue;
                }
                List<ProtoNode> linkTextNodes = ProtoNode.textNodes(parent.children);
                Set<String> videoIds = new HashSet<>();
                Set<String> titles = new LinkedHashSet<>();
                for (ProtoNode linkNode : linkTextNodes) {
                    String text = removeTitleMarker(linkNode.getText()).trim();
                    if (VIDEO_ID_PATTERN.matcher(text).matches()) {
                        videoIds.add(text);
                    } else if (isLabelCandidate(text)) {
                        titles.add(text);
                    }
                }
                if (videoIds.isEmpty()) {
                    continue;
                }
                if (videoIds.size() == 1 && titles.size() == 1) {
                    modified |= restoreLinkedVideoTitle(linkTextNodes, videoIds.iterator().next(),
                            titles.iterator().next(), titles);
                }
                break;
            }
        }
        return modified;
    }

    /**
     * @return If the title was replaced.
     */
    private static boolean restoreLinkedVideoTitle(List<ProtoNode> textNodes, String videoId,
                                                   String title, Set<String> texts) {
        // The text can be another text, such as the name of the channel.
        OriginalTitleRequest.requireChannelName(videoId);
        if (isChannelName(videoId, title)) {
            return false;
        }
        if (!isTitleOfVideo(videoId, title)) {
            // The text hook shows the loading title until the title is confirmed.
            return videoId.equals(pendingLocalizedTitles.get(title))
                    && markTitle(textNodes, title, Collections.emptySet(), videoId);
        }

        Set<String> truncatedTitles = putTranslatedTitle(title, texts, videoId);
        String originalTitle = OriginalTitleRequest.getIfAvailable(videoId);
        if (originalTitle == null) {
            // The text hook replaces the title when the element is laid out.
            return OriginalTitleRequest.isPending(videoId)
                    && markTitle(textNodes, title, truncatedTitles, videoId);
        }
        return !title.equals(originalTitle.trim())
                && replaceTitle(textNodes, title, truncatedTitles, originalTitle, null);
    }

    /**
     * The accessibility label of a video starts with the title, and can include
     * the other texts of the video (duration, channel, views, date).
     * Texts that contain other texts are tried as the label, starting with the texts
     * that contain the most other texts. Keys and identifiers are ignored,
     * as they can contain other short keys.
     *
     * @return The accessibility label and the title, or null if not found.
     */
    @Nullable
    private static String[] findLabeledTitle(Set<String> texts) {
        List<String> candidates = new ArrayList<>();
        for (String text : texts) {
            if (isLabelCandidate(text)) {
                candidates.add(text);
            }
        }

        Map<String, Integer> containedCounts = new HashMap<>();
        for (String text : candidates) {
            final int length = text.length();
            int containedCount = 0;
            for (String other : candidates) {
                if (other.length() < length && text.contains(other)) {
                    containedCount++;
                }
            }
            if (containedCount > 0) {
                containedCounts.put(text, containedCount);
            }
        }

        List<Map.Entry<String, Integer>> labels = new ArrayList<>(containedCounts.entrySet());
        labels.sort((a, b) -> {
            final int countComparison = Integer.compare(b.getValue(), a.getValue());
            return countComparison != 0
                    ? countComparison
                    : Integer.compare(b.getKey().length(), a.getKey().length());
        });
        // Labels of some languages do not start with the title, so these labels are tried
        // only if no label starts with the title.
        for (boolean afterColon : new boolean[]{false, true}) {
            for (Map.Entry<String, Integer> entry : labels) {
                String label = entry.getKey();
                String title = findTitleOfLabel(label, texts, afterColon);
                if (title != null) {
                    return new String[]{label, title};
                }
            }
        }
        return null;
    }

    /**
     * @return The longest accessibility label that starts with the title, or null if none.
     */
    @Nullable
    private static String findLabelOfTitle(String title, Set<String> texts) {
        final int titleLength = title.length();
        String label = null;
        for (String text : texts) {
            if (text.length() > titleLength && text.startsWith(title) && isFollowedBySeparator(text, titleLength)
                    && (label == null || text.length() > label.length())) {
                label = text;
            }
        }
        return label;
    }

    /**
     * The accessibility label of a video is a field of the component that includes the title,
     * such as the lockup of the video. Other components shown with the video can have their own
     * label and title, such as a product: their label is not a field of a component that
     * includes the title of the video.
     *
     * @return If the label is a field of a message that includes the title.
     */
    private static boolean isLabelOfTitle(List<ProtoNode> textNodes, String label, String title) {
        List<ProtoNode> titleNodes = new ArrayList<>();
        List<ProtoNode> labelMessages = new ArrayList<>();
        for (ProtoNode node : textNodes) {
            String text = removeTitleMarker(node.getText()).trim();
            if (text.equals(title)) {
                titleNodes.add(node);
            } else if (text.equals(label) && node.getParent() != null) {
                labelMessages.add(node.getParent());
            }
        }
        for (ProtoNode titleNode : titleNodes) {
            for (ProtoNode parent = titleNode.getParent(); parent != null; parent = parent.getParent()) {
                if (labelMessages.contains(parent)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Elements without an accessibility label can have other texts that start with a text,
     * such as the views and date 'Views · Date', or a title that starts with the channel name
     * 'Channel - Song'. The accessibility label of a video also includes the other texts
     * of the video after the title, such as the channel or the views.
     * The accessibility label of a channel includes the channel handle after the channel name.
     * The accessibility label of a Short can include only the title and the action to play it,
     * such as 'Title - play Short' of a Short without views or with a long title.
     *
     * @param afterColon If the title follows a colon instead of starting the label, as the labels
     *                   of some languages, such as the Russian label 'Воспроизвести видео "Duration"
     *                   – на канале "Channel"; – продолжительность: Title – Channel - Views - Date'.
     * @return The title, which is the longest text that starts the accessibility label
     *         and is followed by a punctuation separator, such as 'Title - 10 minutes'.
     *         Any text can be the title, including titles that look like keys such as 'a-ha'.
     *         Null if not found, or if the label is not the accessibility label of the video.
     */
    @Nullable
    private static String findTitleOfLabel(String label, Set<String> texts, boolean afterColon) {
        String title = null;
        int titleStart = 0;
        int titleLength = 0;
        for (String text : texts) {
            final int length = text.length();
            if (length <= titleLength || length >= label.length() || isChannelHandle(text, texts)) {
                continue;
            }
            int start = afterColon ? label.indexOf(text) : (label.startsWith(text) ? 0 : -1);
            while (start >= 0 && !(isFollowedBySeparator(label, start + length)
                    && (!afterColon || isPrecededByColon(label, start)))) {
                start = afterColon ? label.indexOf(text, start + 1) : -1;
            }
            if (start >= 0) {
                title = text;
                titleStart = start;
                titleLength = length;
            }
        }
        if (title == null) {
            return null;
        }

        String otherTexts = label.substring(0, titleStart) + label.substring(titleStart + titleLength);
        boolean includesOtherText = false;
        boolean isShort = false;
        for (String text : texts) {
            if (text.startsWith(SHORTS_ITEM_KEY_PREFIX)) {
                isShort = true;
            }
            if (text.length() < 2 || text.length() >= label.length() || !otherTexts.contains(text)
                    || title.contains(text) || !isLabelCandidate(text)) {
                continue;
            }
            if (isChannelHandle(text, texts)) {
                return null;
            }
            includesOtherText = true;
        }
        return includesOtherText || isShort ? title : null;
    }

    /**
     * @return If the text at the index, after any whitespace, starts with a punctuation character.
     *         Texts followed by words, such as '10 views', are not titles of the label.
     */
    private static boolean isFollowedBySeparator(String label, int index) {
        final int labelLength = label.length();
        while (index < labelLength && Character.isWhitespace(label.charAt(index))) {
            index++;
        }
        return index < labelLength && !isWordCodePoint(label.codePointAt(index));
    }

    /**
     * @return If the text at the index is preceded by a colon, before any whitespace
     *         (including no-break spaces).
     */
    private static boolean isPrecededByColon(String label, int index) {
        while (index > 0 && (Character.isWhitespace(label.charAt(index - 1))
                || Character.isSpaceChar(label.charAt(index - 1)))) {
            index--;
        }
        return index > 0 && label.charAt(index - 1) == ':';
    }

    /**
     * Called when the title of an element that was parsed is found later, with the title path
     * learned for its layout. The title is replaced by the text hook when the element is laid out again.
     *
     * @param texts Texts of the element, without the title markers.
     */
    static void titleFound(String videoId, String title, List<String> texts) {
        Set<String> elementTexts = new HashSet<>(texts);
        if (!isLabelCandidate(title) || isChannelHandle(title, elementTexts)
                || isChannelName(videoId, title) || !isTitleOfVideo(videoId, title)) {
            return;
        }
        OriginalTitleRequest.requireChannelName(videoId);
        putTranslatedTitle(title, elementTexts, videoId);
        LithoRelayoutPatch.relayoutOutdatedTexts();
    }

    /**
     * @return If the text is the start of the title followed by an ellipsis.
     */
    private static boolean isTruncatedTitle(String text, String title) {
        final int ellipsisLength = text.endsWith("\u2026") ? 1 : text.endsWith("...") ? 3 : 0;
        if (ellipsisLength == 0) {
            return false;
        }
        String start = text.substring(0, text.length() - ellipsisLength).trim();
        return start.length() >= MIN_TITLE_LENGTH && start.length() < title.length() && title.startsWith(start);
    }

    /**
     * The title of the opened video expands the description, so the component of the title
     * includes the title and then the command with the state of the description. Other texts
     * can be between them, such as the commands of the hashtags of the title.
     *
     * @return The title of the opened video, or null if the element does not show it.
     */
    @Nullable
    private static String findOpenedVideoTitle(List<ProtoNode> textNodes, Set<String> texts) {
        for (ProtoNode node : textNodes) {
            if (!node.getText().equals(DESCRIPTION_BODY_STATE)) {
                continue;
            }
            // The first ancestor that starts with a label is the component of the title.
            for (ProtoNode parent = node.getParent(); parent != null; parent = parent.getParent()) {
                List<ProtoNode> parentTextNodes = parent.children == null
                        ? null
                        : ProtoNode.textNodes(parent.children);
                if (parentTextNodes == null || parentTextNodes.isEmpty()) {
                    continue;
                }
                String text = removeTitleMarker(parentTextNodes.get(0).getText()).trim();
                if (isLabelCandidate(text)) {
                    return isChannelHandle(text, texts) ? null : text;
                }
            }
            return null;
        }
        return null;
    }

    /**
     * A text with the description state is the title if no other video has the text,
     * and no other text of the video has the description state. Texts that are
     * not the title are removed from the translated titles, as elements can be parsed
     * before it's known that the text is not the title.
     *
     * @return If the text is the title of the video.
     */
    private static boolean isOpenedVideoTitle(String text, String videoId) {
        synchronized (openedTitleVideoIds) {
            openedTitleVideoIds.computeIfAbsent(text, key -> new HashSet<>()).add(videoId);
            Set<String> videoTexts = openedVideoTitles.computeIfAbsent(videoId, key -> new HashSet<>());
            videoTexts.add(text);

            String title = null;
            for (String videoText : videoTexts) {
                Set<String> videoIds = openedTitleVideoIds.get(videoText);
                if (videoIds != null && videoIds.size() > 1) {
                    // Same text for different videos.
                    for (String id : videoIds) {
                        removeTranslatedTitle(videoText, id);
                    }
                } else if (title == null) {
                    title = videoText;
                } else {
                    // Different texts of the video, and it's not known which is the title.
                    for (String ambiguousText : videoTexts) {
                        removeTranslatedTitle(ambiguousText, videoId);
                    }
                    return false;
                }
            }
            return text.equals(title);
        }
    }

    /**
     * The same title can be shown truncated, such as 'Start of the title…'.
     * Some elements show the original title, and others the translated title, such as a search
     * shelf and the channel page. If the first title found is the original title, then another
     * text is the title only if it's the title translated by the uploader, which is fetched.
     * Until it's fetched, the text is not replaced, and it's laid out again when fetched.
     *
     * @return If the text is the title of the video, or the first title found for the video.
     */
    private static boolean isTitleOfVideo(String videoId, String text) {
        String title = videoTranslatedTitles.putIfAbsent(videoId, text);
        if (title == null || isSameTitle(text, title)) {
            return true;
        }
        OriginalTitleRequest.OriginalVideo originalVideo = OriginalTitleRequest.getOriginalIfFetched(videoId);
        if (originalVideo == null || !title.equals(originalVideo.title().trim())) {
            return false;
        }

        CompletableFuture<String> request = LocalizedTitleRequest.fetch(videoId);
        String localizedTitle = request.getNow(null);
        if (localizedTitle != null) {
            if (!isSameTitle(text, localizedTitle)) {
                return false;
            }
            videoTranslatedTitles.put(videoId, localizedTitle);
            return true;
        }
        if (!request.isDone()) {
            pendingLocalizedTitles.put(text, videoId);
            request.thenAccept(fetchedTitle -> {
                if (!pendingLocalizedTitles.remove(text, videoId)) {
                    return;
                }
                if (fetchedTitle != null && isSameTitle(text, fetchedTitle)) {
                    videoTranslatedTitles.put(videoId, fetchedTitle);
                    putTranslatedTitle(text, Collections.emptySet(), videoId);
                }
                // The loading texts show the title or the text again.
                LithoRelayoutPatch.relayoutOutdatedTexts();
            });
        }
        return false;
    }

    /**
     * @return If the text is the title, or the title truncated with an ellipsis, or the title is truncated.
     */
    private static boolean isSameTitle(String text, String title) {
        return title.equals(text) || isTruncatedTitle(text, title) || isTruncatedTitle(title, text);
    }

    /**
     * Texts found as the title can be the name of the channel, such as the name of a channel
     * in an element without the title. The name of the channel is fetched with the original title.
     *
     * @return If the text is the name of the channel of the video.
     */
    private static boolean isChannelName(String videoId, String text) {
        OriginalTitleRequest.OriginalVideo originalVideo = OriginalTitleRequest.getOriginalIfFetched(videoId);
        return originalVideo != null && text.equals(originalVideo.channelName());
    }

    /**
     * Channel handles are not titles, such as the handle of the label '@handle, Verified',
     * as the element includes the channel url '/@handle'.
     */
    private static boolean isChannelHandle(String text, Set<String> texts) {
        return texts.contains("/" + text);
    }

    /**
     * Lists use the thumbnail of the first video, but show the list title, such as a playlist
     * or a mix. Their urls include the list, but not the index of a video in the list.
     */
    private static boolean isListUrl(String text) {
        if (!text.contains("list=")) {
            return false;
        }
        try {
            Uri uri = Uri.parse(text);
            return uri.isHierarchical()
                    && uri.getQueryParameter("list") != null
                    && uri.getQueryParameter("index") == null;
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * @return If the text can be a part of an accessibility label, and is not a key, url or identifier.
     */
    static boolean isLabelCandidate(String text) {
        return LETTER_PATTERN.matcher(text).find()
                && (WHITESPACE_PATTERN.matcher(text).find() || !TOKEN_PATTERN.matcher(text).find());
    }

    /**
     * Replaces the texts that are the title, the start of the accessibility label,
     * and the title in other labels (such as the accessibility label of the menu button).
     * Keys and urls are not changed, even if they include the title.
     *
     * @return If any text was replaced.
     */
    private static boolean replaceTitle(List<ProtoNode> textNodes, String translatedTitle,
                                        Set<String> truncatedTitles, String originalTitle,
                                        @Nullable String label) {
        boolean replaced = false;
        final int translatedLength = translatedTitle.length();
        final boolean replaceInsideTexts = translatedLength >= MIN_TITLE_LENGTH;

        for (ProtoNode node : textNodes) {
            String nodeText = node.getText();
            String text = removeTitleMarker(nodeText).trim();
            if (text.equals(translatedTitle) || truncatedTitles.contains(text)) {
                // Truncated titles are replaced by the entire title, which the view truncates.
                node.setText(originalTitle);
            } else if (text.equals(label)) {
                node.setText(originalTitle + label.substring(translatedLength));
            } else if (isLabelCandidate(text)) {
                String restored = replaceInsideTexts ? text.replace(translatedTitle, originalTitle) : text;
                for (String truncatedTitle : truncatedTitles) {
                    restored = restored.replace(truncatedTitle, originalTitle);
                }
                if (restored.equals(text)) {
                    continue;
                }
                node.setText(restored);
            } else {
                continue;
            }
            replaced = true;
        }

        if (replaced) {
            Logger.printDebug(() -> "Restored title: " + translatedTitle + " to: " + originalTitle);
        }
        return replaced;
    }
}
