/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.net.Uri;
import android.os.Build;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Base64;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.LithoRelayoutPatch;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.youtube.patches.dearrow.DeArrowPatch;
import app.morphe.extension.youtube.patches.dearrow.DeArrowTitleIcon;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Replaces the auto-translated video titles and descriptions with the original titles and descriptions,
 * and replaces the video titles with the titles submitted to DeArrow if enabled by the DeArrow patch.
 * DeArrow titles are used instead of the original titles, and videos without a DeArrow title
 * show the original title only if original titles are restored.
 * <p>
 * Responses do not include the original title, so it's fetched with {@link OriginalTitleRequest}.
 * The original description of the opened video is fetched with {@link OriginalDescriptionRequest}
 * when the video is opened, so it's available when the description panel is opened.
 * <p>
 * Elements are parsed on the main thread, where the original title cannot be fetched.
 * The element hook finds the translated titles and starts fetching the original titles.
 * If an original title is already available, the element is modified (including the
 * accessibility label). Otherwise the text hook replaces the title when it's laid out.
 * <p>
 * Elements do not show which text is the title, so a text is replaced only if it's verified
 * to be the title: the original title, which the element shows if the title is not translated,
 * or the title in the language of the app fetched with {@link LocalizedTitleRequest}, which is
 * fetched only if the element does not show the original title. If the text found as the title
 * is neither, the title is also fetched as shown in the lists, where titles can be auto-translated,
 * and in the language of the text, as elements can show the title in another language, such as
 * a search result shown in the language of the search.
 * Until the title is verified, the text found as the title by the layout of the element is shown
 * as loading, and it's shown again if it's not the title.
 */
@SuppressWarnings("unused")
public final class RestoreOriginalTitlesPatch {

    /**
     * If the original titles and descriptions are restored.
     */
    static final boolean RESTORE_ORIGINAL = Settings.RESTORE_ORIGINAL_TITLES.get();

    /**
     * If the titles are replaced with the DeArrow titles, for any navigation.
     */
    static final boolean USE_DEARROW = DeArrowPatch.DeArrowTitlesAvailability.usingDeArrowTitlesAnywhere();

    private static final boolean REPLACE_TITLES = RESTORE_ORIGINAL || USE_DEARROW;

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
     * Marks the Litho texts that can be the title of a video that is not yet verified,
     * such as the loading text of the text found as the title by the layout of the element.
     * The texts are laid out again when the title is verified, whether the text is the title or not.
     */
    private record PendingTitleSpan(String videoId) implements LithoRelayoutPatch.RelayoutSpan {
        @Override
        public boolean isOutdated() {
            return verifiedTitles(videoId) != null;
        }
    }

    /**
     * Marks the Litho loading texts found as the title of a video whose other titles failed to fetch
     * because of network errors or temporary errors of the server, which are shown as loading until
     * the title is verified or the titles are no longer fetched again.
     * The texts are laid out again when the titles are fetched again, when they are no longer fetched again,
     * and when the text is verified meanwhile, such as by another element of the video.
     */
    private record RetryTitleSpan(CandidateRetry retry) implements LithoRelayoutPatch.RelayoutSpan {
        @Override
        public boolean isOutdated() {
            return retry.started || retry.done.isDone() || retry.isVerified();
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
     * The hash is in hexadecimal without the leading zeros, such as 'about_channel_view.eml-fe|e0cd03134bb123e'.
     */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^[^\\s|]+\\.[^\\s|]+\\|[0-9a-f]{1,16}$");

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
     * Views that replace the translated titles seen before, such as the title of the next video of a playlist.
     */
    private static final Set<TextView> knownTitleViews = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Translated title -> video ids of the videos with the title, of the titles that are verified.
     * Different videos can have the same title, such as a video and its reupload.
     */
    private static final Map<String, Set<String>> translatedTitles =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Component and text of an element -> video ids of the elements with the text, of the texts
     * that can be the title of a video whose title is not yet verified when the element is parsed.
     * The text hook replaces the text when it's laid out, if it's verified to be the title.
     */
    private static final Map<String, Set<String>> pendingTitleTexts =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(2000));

    /**
     * Video id -> text found as the title by the layout of the element, such as the title of the opened video,
     * which is replaced even if no request returns the text, such as a title tested by the uploader.
     */
    private static final Map<String, String> layoutTitles =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Requests of the titles in the language of the app that register the title when done.
     */
    private static final Set<CompletableFuture<String>> localizedTitleRequests = ConcurrentHashMap.newKeySet();

    /**
     * Video id and language of the app -> text found as the title -> request of the other title that
     * the text can be, for the elements that show neither the original title nor the title in the
     * language of the app: the title shown in the lists, or the title in the language of the text.
     * The title is null if neither is the text. The titles are fetched once, as the requests are cached.
     */
    private static final Map<String, Map<String, CompletableFuture<String>>> candidateTitleRequests =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Maximum number of texts found as the title of a video whose other titles are fetched.
     */
    private static final int MAX_CANDIDATE_REQUESTS_PER_VIDEO = 5;

    /**
     * Time before the other titles of a text that failed to fetch because of network errors
     * are fetched again. The text is shown meanwhile, so a failing network does not keep loading.
     */
    private static final long CANDIDATE_RETRY_MILLISECONDS = 30_000;

    /**
     * Time after the time when an original title that failed to fetch can be fetched again,
     * before a view that shows it as loading fetches it again.
     */
    private static final long VIEW_TITLE_RETRY_MARGIN_MILLISECONDS = 100;

    /**
     * Maximum number of times the other titles of a text that failed to fetch are fetched again
     * without waiting for the element to be loaded again, such as by scrolling.
     */
    private static final int MAX_CANDIDATE_RETRIES = 3;

    /**
     * Time after a Litho view is measured, such as while scrolling, before the texts whose other titles
     * are fetched again when shown are checked, so the views measured at the same time are checked once.
     */
    private static final long HIDDEN_RETRY_CHECK_DELAY_MILLISECONDS = 300;

    /**
     * Maximum number of texts whose other titles are waiting to be fetched again, or to be shown again,
     * such as the elements scrolled while the requests are paused. The oldest are no longer fetched again.
     */
    private static final int MAX_PENDING_RETRIES = 100;

    /**
     * Fetching again of the other titles of a text found as the title, after they failed to fetch.
     */
    private static final class CandidateRetry {
        final String videoId;
        final Set<String> texts;
        final String candidate;
        final int retries;
        final Map<String, CompletableFuture<String>> requests;
        final CompletableFuture<String> failedRequest;

        /**
         * Done when the titles fetched again are fetched, or when they are no longer fetched again.
         */
        final CompletableFuture<Void> done = new CompletableFuture<>();

        /**
         * If the titles are being fetched again.
         */
        volatile boolean started;

        /**
         * Time when the titles can be fetched again. Accessed only on the main thread.
         */
        long retryTime;

        CandidateRetry(String videoId, Set<String> texts, String candidate, int retries,
                       Map<String, CompletableFuture<String>> requests, CompletableFuture<String> failedRequest) {
            this.videoId = videoId;
            this.texts = texts;
            this.candidate = candidate;
            this.retries = retries;
            this.requests = requests;
            this.failedRequest = failedRequest;
        }

        /**
         * @return If the text is verified to be the title meanwhile, such as by another element of the video,
         *         so the titles are no longer fetched again.
         */
        boolean isVerified() {
            List<String> titles = verifiedTitles(videoId);
            return titles != null && findTitle(videoId, Collections.singleton(candidate), titles) != null;
        }
    }

    /**
     * Video id and language of the app -> last fetching again of the other titles of a text of the video.
     */
    private static final Map<String, CandidateRetry> candidateRetries =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Texts whose other titles are waiting to be fetched again, in the order they failed to fetch.
     * Accessed only on the main thread.
     */
    private static final List<CandidateRetry> pendingRetries = new ArrayList<>();

    /**
     * Texts whose other titles were not fetched again as they were not shown, such as the elements
     * scrolled off-screen. They are fetched again when shown again, which is checked only when Litho views
     * are measured, as Litho does not load again the texts of the elements scrolled on screen again
     * from the cache of the list. Nothing is done meanwhile. Accessed only on the main thread.
     */
    private static final List<CandidateRetry> hiddenRetries = new ArrayList<>();

    /**
     * Time of the next check of the pending retries, or 0 if none. Accessed only on the main thread.
     */
    private static long retryCheckTime;

    /**
     * If the hidden retries are checked. Accessed only on the main thread.
     */
    private static boolean hiddenRetryCheckScheduled;

    /**
     * Length of the longest translated title, so longer texts are ignored without copying them.
     */
    private static final AtomicInteger maxTranslatedTitleLength = new AtomicInteger();

    /**
     * Title requests that lay out the loading texts again when done.
     */
    private static final Set<CompletableFuture<?>> relayoutRequests = ConcurrentHashMap.newKeySet();

    /**
     * Video id of the opened video.
     */
    private static volatile String openedVideoId;

    /**
     * Metadata of the media notification, as set by the app, and its media session.
     * The metadata is set again if the original title is fetched after the metadata is set.
     */
    @Nullable
    private static volatile MediaMetadata mediaMetadata;
    private static volatile WeakReference<MediaSession> mediaSessionRef = new WeakReference<>(null);

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
            if (!REPLACE_TITLES || videoId == null || videoId.isEmpty()) {
                return;
            }

            openedVideoId = videoId;
            if (RESTORE_ORIGINAL) {
                OriginalDescriptionRequest.fetchRequestIfNeeded(videoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoLoaded failure", ex);
        }
    }

    /**
     * DeArrow titles can be used only for some navigations, such as only for the search results.
     * Can be called on any thread.
     *
     * @return If titles are replaced for the current navigation.
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean replacesTitlesForCurrentNavigation() {
        return RESTORE_ORIGINAL || DeArrowPatch.useDeArrowTitlesForCurrentNavigation();
    }

    /**
     * Does not wait for the title to be fetched.
     *
     * @return The title that replaces the title of the video, or the title if not replaced or not yet fetched.
     */
    public static String getTitle(String videoId, String title) {
        if (!REPLACE_TITLES || videoId.isEmpty()) {
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
            if (!REPLACE_TITLES || !elementSearch.matches(bytes) || !replacesTitlesForCurrentNavigation()) {
                return bytes;
            }
            // Clears the translated titles and descriptions found in another language.
            // The original titles and descriptions do not depend on the language, and are kept.
            String language = Locale.getDefault().toLanguageTag();
            String previousLanguage = titlesLanguage;
            titlesLanguage = language;
            if (previousLanguage != null && !previousLanguage.equals(language)) {
                Logger.printDebug(() -> "Language changed from: " + previousLanguage + " to: " + language);
                translatedTitles.clear();
                maxTranslatedTitleLength.set(0);
                pendingTitleTexts.clear();
                translatedChannelPreviews.clear();
                maxTranslatedChannelPreviewLength.set(0);
                openedChannelPreview = null;
                TitleLayouts.clearCandidates();
            }

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
                    return RESTORE_ORIGINAL && restoreDescription(root, textNodes, isShortsDescription)
                            ? ProtoNode.write(root) : bytes;
                }
                if (identifier.startsWith(CHANNEL_HEADER_IDENTIFIER)) {
                    return RESTORE_ORIGINAL && restoreChannelHeader(textNodes)
                            ? ProtoNode.write(root) : bytes;
                }
                if (identifier.startsWith(CHANNEL_ABOUT_IDENTIFIER)) {
                    return RESTORE_ORIGINAL && restoreChannelAbout(root)
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
            if (Objects.requireNonNull(messageVideoIds.get(root)).isEmpty()) {
                // Elements of the opened video that do not include the video id, such as the watch page title.
                String videoId = openedVideoId;
                if (videoId != null) {
                    modified |= restoreVideoTitle(root, videoId, false, component);
                }
            }
            modified |= restoreLinkedVideoTitles(textNodes, component);

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
     * Injection point.
     * <p>
     * Called when a Litho text is created or reused. Usually called off the main thread.
     */
    public static CharSequence onLithoTextLoaded(ContextInterface contextInterface, CharSequence text) {
        try {
            if (!REPLACE_TITLES || text == null || DeArrowTitleIcon.hasIcon(text)) {
                return text;
            }
            if (!replacesTitlesForCurrentNavigation()) {
                // A title can be marked when the element was parsed for another navigation.
                final int markerStart = findTitleMarker(text);
                return markerStart < 0 ? text : text.subSequence(0, markerStart);
            }

            CharSequence channelPreview = restoreChannelPreview(text);
            if (channelPreview != null) {
                return channelPreview;
            }

            CharSequence translatedText = text;
            Set<String> videoIds = null;
            final int markerStart = findTitleMarker(text);
            if (markerStart >= 0) {
                translatedText = text.subSequence(0, markerStart);
                String markedVideoId = decodeTitleMarker(text, markerStart);
                if (markedVideoId != null) {
                    videoIds = Collections.singleton(markedVideoId);
                }
            }
            String trimmedText = translatedText.toString().trim();
            final int length = trimmedText.length();
            if (length == 0) {
                return translatedText;
            }
            if (markerStart < 0 && DeArrowTitleIcon.isShown(trimmedText)) {
                // The title was replaced when the element was parsed.
                return titleText(translatedText, translatedText.toString(), null);
            }

            String videoId = null;
            if (videoIds != null) {
                // The text was found as the title, and is shown as loading until it's verified.
                // The marker can be of another video, such as an element of the opened video parsed
                // before the new video is loaded, so a text that is not the title of the marked video
                // is found as any other text.
                String pendingVideoId = findPendingVerification(videoIds);
                if (pendingVideoId != null) {
                    return spannedText(translatedText, LOADING_TITLE.toString(), new PendingTitleSpan(pendingVideoId));
                }
                videoId = findVerifiedVideoId(videoIds, trimmedText);
                if (videoId == null) {
                    // The other titles of the text are fetched again, and the fetching is not yet started.
                    for (String candidateVideoId : videoIds) {
                        CandidateRetry retry = candidateRetries.get(candidateKey(candidateVideoId));
                        if (retry != null && !retry.started && !retry.done.isDone()) {
                            return spannedText(translatedText, LOADING_TITLE.toString(), new RetryTitleSpan(retry));
                        }
                    }
                    // The title found by the layout of the element is replaced even if no request verified it.
                    for (String layoutVideoId : videoIds) {
                        String replacement = trimmedText.equals(layoutTitles.get(layoutVideoId))
                                ? OriginalTitleRequest.getIfAvailable(layoutVideoId)
                                : null;
                        if (replacement != null && !replacement.trim().equals(trimmedText)) {
                            return titleText(translatedText, replacement, null);
                        }
                    }
                }
            }
            if (videoId == null && length >= MIN_TITLE_LENGTH && length <= maxTranslatedTitleLength.get()) {
                // Texts without the marker can be any text, such as a comment or a user name.
                // Short texts are more likely to be the same as a title by chance.
                videoId = findVideoIdOfTitle(trimmedText, openedVideoId);
            }
            if (videoId == null) {
                // The text can be the title of an element whose title was not yet verified,
                // which is found in the same component for short texts.
                // The component is the name of the element, such as 'video_lockup_with_attachment.eml-fe'.
                String component = contextInterface == null ? null : contextInterface.patch_getIdentifier();
                if (component != null && component.indexOf('|') >= 0) {
                    component = component.substring(0, component.indexOf('|'));
                }
                if (component == null && length < MIN_TITLE_LENGTH) {
                    return translatedText;
                }
                synchronized (pendingTitleTexts) {
                    Set<String> pendingVideoIds = pendingTitleTexts.get((component == null ? "" : component) + '\n' + trimmedText);
                    videoIds = pendingVideoIds == null ? null : new HashSet<>(pendingVideoIds);
                }
                if (videoIds == null) {
                    return translatedText;
                }
                String pendingVideoId = findPendingVerification(videoIds);
                if (pendingVideoId != null) {
                    return spannedText(translatedText, translatedText, new PendingTitleSpan(pendingVideoId));
                }
                videoId = findVerifiedVideoId(videoIds, trimmedText);
                if (videoId == null) {
                    return translatedText;
                }
            }
            putTranslatedTitle(trimmedText, Collections.emptySet(), videoId);

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

            return loading
                    ? spannedText(translatedText, originalTitle, new LoadingTitleSpan(videoId))
                    : titleText(translatedText, originalTitle, null);
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }

        return text;
    }

    /**
     * Same as {@link #spannedText(CharSequence, CharSequence, LithoRelayoutPatch.RelayoutSpan)},
     * and shows the DeArrow icon before the title if the title is a DeArrow title.
     */
    @SuppressWarnings("SameParameterValue")
    private static SpannableString titleText(CharSequence text, String title,
                                             @Nullable LithoRelayoutPatch.RelayoutSpan relayoutSpan) {
        if (!DeArrowTitleIcon.isShown(title)) {
            return spannedText(text, title, relayoutSpan);
        }
        // Spans of the entire text are also applied to the icon, such as the color of the text.
        SpannableString spannedTitle = spannedText(text, DeArrowTitleIcon.addIcon(title), relayoutSpan);
        DeArrowTitleIcon.setIconSpan(spannedTitle);
        return spannedTitle;
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
     * Replaces the description of the channel header or the description panel when it's laid out,
     * if the original description was not yet fetched when the element was parsed.
     * The loading text is shown until the original description is fetched.
     *
     * @return The original description, the loading text marked to be laid out again,
     *         or null if the text is not a translated channel description, the original description
     *         is the same, or it failed to fetch.
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
        restoreViewTitle(view, videoId, USE_DEARROW && DeArrowPatch.useDeArrowTitlesForCurrentNavigation());
    }

    /**
     * Same as {@link #restoreOriginalTitle(TextView, String)}, for a view that shows a search result
     * that is not shown by the app, such as a result of another patch. DeArrow titles are used
     * if they are used for the search results.
     */
    public static void restoreSearchResultTitle(TextView view, String videoId) {
        restoreViewTitle(view, videoId, USE_DEARROW && Settings.DEARROW_TITLES_SEARCH.get());
    }

    /**
     * @param useDeArrow If the DeArrow title is used if the video has one.
     */
    private static void restoreViewTitle(@Nullable TextView view, @Nullable String videoId, boolean useDeArrow) {
        try {
            if (!REPLACE_TITLES || view == null || videoId == null || !(RESTORE_ORIGINAL || useDeArrow)) {
                return;
            }

            CharSequence translatedTitle = view.getText();
            String translatedTitleText = removeTitleMarker(translatedTitle.toString()).trim();
            // Reused views can still show the loading title or a DeArrow title.
            if (!translatedTitleText.isEmpty() && !translatedTitleText.equals(LOADING_TITLE.toString())
                    && !DeArrowTitleIcon.hasIcon(translatedTitle)) {
                putTranslatedTitle(translatedTitleText, Collections.emptySet(), videoId);
            }

            // Views are reused for other videos, so the title is only set if the view
            // still shows the same video when the title is fetched.
            titleViewVideoIds.put(view, videoId);

            if (OriginalTitleRequest.getIfAvailable(videoId) == null && OriginalTitleRequest.isPending(videoId)) {
                view.setText(LOADING_TITLE.toString());
            }

            setViewTitleWhenFetched(new WeakReference<>(view), videoId, useDeArrow, translatedTitle);
        } catch (Exception ex) {
            Logger.printException(() -> "restoreOriginalTitle failure", ex);
        }
    }

    /**
     * Sets the title of the view when it's fetched. The view shows the loading title meanwhile,
     * also while the original title that failed to fetch is fetched again, until the maximum number of retries.
     * The title is null if the video has no available title, or if it failed to fetch.
     */
    private static void setViewTitleWhenFetched(WeakReference<TextView> viewRef, String videoId,
                                                boolean useDeArrow, CharSequence translatedTitle) {
        OriginalTitleRequest.fetch(videoId).thenAccept(titles -> Utils.runOnMainThreadNowOrLater(() -> {
            TextView titleView = viewRef.get();
            if (titleView == null || !videoId.equals(titleViewVideoIds.get(titleView))) {
                return;
            }
            String originalTitle = titles.replacement(useDeArrow);
            if (originalTitle == null && titleView.isShown() && OriginalTitleRequest.isPending(videoId)) {
                Utils.runOnMainThreadDelayed(
                        () -> setViewTitleWhenFetched(viewRef, videoId, useDeArrow, translatedTitle),
                        OriginalTitleRequest.retryRemainingMilliseconds(videoId) + VIEW_TITLE_RETRY_MARGIN_MILLISECONDS);
                return;
            }
            titleViewVideoIds.remove(titleView, videoId);
            CharSequence title = originalTitle == null
                    ? translatedTitle
                    : titleText(translatedTitle, originalTitle, null);
            // Setting the same text again would notify the text listeners again.
            if (!TextUtils.equals(title, titleView.getText())) {
                titleView.setText(title);
            }
        }));
    }

    /**
     * Injection point.
     * <p>
     * Replaces the translated titles seen before in other views or elements,
     * for a view that shows a title without the video id.
     */
    public static void restoreKnownTitles(TextView view) {
        // Views can be bound again for other videos, and only need one listener.
        if (view == null || !knownTitleViews.add(view)) {
            return;
        }

        view.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!REPLACE_TITLES || !replacesTitlesForCurrentNavigation()) {
                    return;
                }

                String title = text.toString().trim();
                if (title.equals(LOADING_TITLE.toString()) || DeArrowTitleIcon.hasIcon(text)) {
                    return;
                }

                // The title of the element that opened the video can be marked with the video id,
                // such as the title shown on the watch page while the video is loading.
                final int markerStart = findTitleMarker(text);
                String videoId = markerStart >= 0
                        ? decodeTitleMarker(text, markerStart)
                        : findVideoIdOfTitle(title, null);
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
     * Injection point.
     * <p>
     * Replaces the translated title of the media notification.
     *
     * @return The metadata with the title replaced, or the same metadata if the title is not replaced.
     */
    public static MediaMetadata restoreMediaMetadataTitle(MediaSession session, MediaMetadata metadata) {
        try {
            if (!REPLACE_TITLES || metadata == null) {
                return metadata;
            }

            mediaMetadata = metadata;
            mediaSessionRef = new WeakReference<>(session);
            return replaceMediaMetadataTitle(metadata);
        } catch (Exception ex) {
            Logger.printException(() -> "restoreMediaMetadataTitle failure", ex);
            return metadata;
        }
    }

    /**
     * Injection point.
     * <p>
     * Replaces the translated title of the fullscreen engagement overlay,
     * shown by swiping up in fullscreen.
     */
    public static void restorePlayerTitle(TextView view) {
        if (view == null) {
            return;
        }

        view.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                if (!REPLACE_TITLES || DeArrowTitleIcon.hasIcon(text)
                        || text.toString().equals(LOADING_TITLE.toString())) {
                    return;
                }

                // The text cannot be changed while the listeners are notified.
                view.post(() -> setPlayerTitle(view, text));
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
    }

    private static void setPlayerTitle(TextView view, CharSequence text) {
        try {
            String replacement = playerTitle(text.toString(), () -> {
                // The view is set again only if it still shows the loading title of this text.
                if (view.getText().toString().equals(LOADING_TITLE.toString())) {
                    setPlayerTitle(view, text);
                }
            });
            if (replacement == null) {
                return;
            }

            CharSequence title = titleText(text, replacement, null);
            if (!TextUtils.equals(title, view.getText())) {
                view.setText(title);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setPlayerTitle failure", ex);
        }
    }

    /**
     * The title is shown as loading until it's fetched, or until the fetch is no longer retried.
     */
    private static MediaMetadata replaceMediaMetadataTitle(MediaMetadata metadata) {
        String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (title == null || title.isBlank()) {
            return metadata;
        }

        String replacement = playerTitle(title, () -> {
            try {
                MediaSession session = mediaSessionRef.get();
                if (session == null || mediaMetadata != metadata) {
                    return; // The metadata was changed meanwhile.
                }

                session.setMetadata(replaceMediaMetadataTitle(metadata));
            } catch (Exception ex) {
                Logger.printException(() -> "replaceMediaMetadataTitle failure", ex);
            }
        });
        if (replacement == null) {
            return metadata;
        }

        if (!replacement.equals(LOADING_TITLE.toString())) {
            Logger.printDebug(() -> "Restored media notification title: " + replacement);
        }
        MediaMetadata.Builder builder = new MediaMetadata.Builder(metadata)
                .putString(MediaMetadata.METADATA_KEY_TITLE, replacement);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // The images are not scaled again, as scaling some images fails, such as on YouTube 20.21.
            // The media session scales them when the metadata is set.
            builder.setBitmapDimensionLimit(Integer.MAX_VALUE);
        }
        return builder.build();
    }

    /**
     * The title shown by the player, from any navigation, for a title of the opened video
     * shown outside the player, such as the media notification.
     *
     * @param onFetched Called on the main thread when the title is fetched, if the loading title is returned.
     * @return The title that replaces the title, the loading title until it's fetched
     *         or until the fetch is no longer retried, or null if the title is not replaced.
     */
    @Nullable
    private static String playerTitle(String title, Runnable onFetched) {
        // The title is of the opened video, unless the title is of another video.
        String titleVideoId = findVideoIdOfTitle(title.trim(), openedVideoId);
        String videoId = titleVideoId == null ? openedVideoId : titleVideoId;
        if (videoId == null) {
            return null;
        }

        CompletableFuture<OriginalTitleRequest.Titles> future = OriginalTitleRequest.fetch(videoId);
        OriginalTitleRequest.Titles titles = future.getNow(null);
        if (!future.isDone()) {
            future.whenComplete((result, ex) -> Utils.runOnMainThread(onFetched));
            return LOADING_TITLE.toString();
        }
        if (OriginalTitleRequest.isPending(videoId)) {
            // The title is fetched again after the retry time.
            Utils.runOnMainThreadDelayed(onFetched, OriginalTitleRequest.retryRemainingMilliseconds(videoId));
            return LOADING_TITLE.toString();
        }

        String replacement = titles == null ? null : titles.replacement(Settings.DEARROW_TITLES_PLAYER.get());
        return replacement == null || replacement.equals(title) ? null : replacement;
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
    private static void relayoutWhenFetched(CompletableFuture<?> request) {
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
        ProtoNode previewMessage = preview.getParent().getParent();
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
            final int textLength = text.length();
            if (node != preview && textLength < shownPreview.length() && textLength > truncationLength
                    && shownPreview.endsWith(text) && !text.isBlank()) {
                truncationLength = textLength;
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
        // which can be truncated, such as 'Description. First line of the descr... Tap to read more.'
        String labelText = label.getText();
        final int prefixLength = previewPrefixLength(labelText, translatedPreview);
        if (prefixLength > 0) {
            final int prefixStart = labelText.indexOf(translatedPreview.substring(0, prefixLength));
            final int originalPreviewLength = originalPreview.length();
            int restoredLength = prefixLength == translatedPreview.length()
                    ? originalPreviewLength
                    : Math.min(prefixLength, originalPreviewLength);
            if (restoredLength < originalPreviewLength
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
        final int previewLength = preview.length();
        if (previewLength < MIN_TITLE_LENGTH) {
            return 0;
        }
        final int start = label.indexOf(preview.substring(0, MIN_TITLE_LENGTH));
        if (start < 0) {
            return 0;
        }
        final int labelLength = label.length();
        int length = MIN_TITLE_LENGTH;
        while (length < previewLength) {
            if (!(start + length < labelLength
                    && label.charAt(start + length) == preview.charAt(length))) break;
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
     * Elements can show the title truncated, such as 'Start of the title...', and the entire title
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
                // The marker of the video id is added after the title.
                StringBuilder builder = new StringBuilder(nodeText.length() + TITLE_MARKER_LENGTH)
                        .append(nodeText)
                        .append(TITLE_MARKER_START);
                for (int i = 0; i < VIDEO_ID_LENGTH; i++) {
                    final int value = VIDEO_ID_CHARACTERS.indexOf(videoId.charAt(i));
                    for (int shift = 2 * (TITLE_MARKER_DIGITS_PER_CHARACTER - 1); shift >= 0; shift -= 2) {
                        builder.append((char) (TITLE_MARKER_FIRST_DIGIT + ((value >> shift) & 3)));
                    }
                }
                node.setText(builder.toString());
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
     * Replaces the title if a text of the element is verified to be the title.
     * Until the title is verified, the text found as the title is shown as loading.
     *
     * @param elementVideoId If the video id is included in the message. Otherwise the video
     *                       is the opened video, and only titles of the opened video are found.
     * @param component      Name of the Litho component of the element.
     * @return If the title was replaced, or the element was marked.
     */
    private static boolean restoreVideoTitle(List<ProtoNode> message, String videoId,
                                             boolean elementVideoId, String component) {
        List<ProtoNode> textNodes = ProtoNode.textNodes(message);
        // Texts of the text nodes without the title markers, and the texts that are not empty.
        List<String> nodeTexts = new ArrayList<>(textNodes.size());
        Set<String> texts = new LinkedHashSet<>();
        for (ProtoNode node : textNodes) {
            String text = removeTitleMarker(node.getText()).trim();
            // Lists use the thumbnail of the first video, but show the list title, such as a playlist
            // or a mix. Their urls include the list, but not the index of a video in the list.
            if (text.contains("list=")) {
                try {
                    Uri uri = Uri.parse(text);
                    if (uri.isHierarchical() && uri.getQueryParameter("list") != null
                            && uri.getQueryParameter("index") == null) {
                        return false;
                    }
                } catch (Exception ignored) {
                }
            }
            nodeTexts.add(text);
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }

        return restoreTitle(textNodes, texts, videoId, component,
                // The title found by the layout of the element, and not only by its text: the title
                // of the opened video that expands the description, or the title that starts the accessibility label.
                () -> {
                    String layoutTitle = videoId.equals(openedVideoId) ? findOpenedVideoTitle(textNodes, texts) : null;
                    return layoutTitle == null && elementVideoId
                            ? findLabeledCandidateTitle(textNodes, texts)
                            : layoutTitle;
                },
                () -> findCandidateTitle(textNodes, nodeTexts, texts, videoId, elementVideoId, component),
                title -> {
                    if (elementVideoId) {
                        TitleLayouts.confirmTitle(component, videoId, TitleLayouts.fieldPaths(textNodes), nodeTexts, title);
                    }
                });
    }

    /**
     * Replaces the title if a text of the element is verified to be the title. Until the title
     * is verified, the text found as the title is shown as loading.
     *
     * @param texts             Texts of the element that can be the title.
     * @param findLayoutTitle   Finds the title by the layout of the element, or null to not find it.
     *                          The title found by the layout is replaced even if no text is verified to be the title.
     * @param findCandidate     Finds the text that can be the title, which is shown as loading until verified.
     * @param onTitleVerified   Called with the text that is verified to be the title, or null if none.
     * @return If the title was replaced, or the element was marked.
     */
    private static boolean restoreTitle(List<ProtoNode> textNodes, Set<String> texts, String videoId,
                                        String component, @Nullable Supplier<String> findLayoutTitle,
                                        Supplier<String> findCandidate,
                                        @Nullable Consumer<String> onTitleVerified) {
        requestVerification(videoId, texts);
        List<String> titles = verifiedTitles(videoId);
        String title = titles == null ? null : findTitle(videoId, texts, titles);
        if (title == null) {
            // The titles can be verified before the element was parsed, such as for another element
            // of the video, and the element can show the title auto-translated or in another language.
            if (titles == null) {
                addPendingTitleTexts(texts, videoId, component);
            }
            String candidate = findCandidate.get();
            if (candidate == null || isChannelName(videoId, candidate)) {
                return false;
            }
            final boolean layoutTitle = findLayoutTitle != null && candidate.equals(findLayoutTitle.get());
            if (requestCandidateTitle(videoId, texts, candidate) || titles == null) {
                if (titles != null) {
                    addPendingTitleTexts(texts, videoId, component);
                }
                if (layoutTitle) {
                    layoutTitles.put(videoId, candidate);
                }
                return markTitle(textNodes, candidate, Collections.emptySet(), videoId);
            }
            // The video can show a title that no request returns, such as a title tested by the uploader.
            // The title is not saved as a translated title, as no request verified it.
            String replacement = layoutTitle ? OriginalTitleRequest.getIfAvailable(videoId) : null;
            return replacement != null && !candidate.equals(replacement.trim())
                    && replaceTitle(textNodes, candidate, Collections.emptySet(), replacement,
                    findLabelOfTitle(candidate, texts));
        }
        if (isChannelName(videoId, title)) {
            return false;
        }
        if (onTitleVerified != null) {
            onTitleVerified.accept(title);
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
                && replaceTitle(textNodes, title, truncatedTitles, originalTitle, findLabelOfTitle(title, texts));
    }

    /**
     * The text that can be the title is found in order of reliability: a title seen before
     * for the video, the title of the opened video that expands the description, the title
     * that starts the accessibility label of the video, or the title at the field path learned
     * by {@link TitleLayouts} for the elements without an accessibility label.
     * The opened video title is found before the labeled title, as the element can include
     * other labels that look like a video label, such as the channel label of an artist
     * '@handle, Official Artist Channel' of a Short.
     * <p>
     * The text is only shown as loading until the title is verified, so it can be another text,
     * such as the name of a product shown with the video.
     *
     * @return The text that can be the title, or null if none.
     */
    @Nullable
    private static String findCandidateTitle(List<ProtoNode> textNodes, List<String> nodeTexts, Set<String> texts,
                                             String videoId, boolean elementVideoId, String component) {
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
        }
        if (title == null) {
            if (!elementVideoId) {
                return null;
            }

            title = findLabeledCandidateTitle(textNodes, texts);
            if (title == null) {
                // The title at the title path learned for the component.
                List<String> paths = TitleLayouts.fieldPaths(textNodes);
                title = TitleLayouts.findTitle(component, paths, nodeTexts);
                if (title == null || !isLabelCandidate(title) || isChannelHandle(title, texts)) {
                    // Saves the texts of the element, so the title path of the component is learned
                    // if the title is not translated.
                    if (RESTORE_ORIGINAL) {
                        TitleLayouts.addCandidates(component, videoId, paths, nodeTexts);
                        OriginalTitleRequest.OriginalVideo originalVideo = OriginalTitleRequest.getOriginalIfFetched(videoId);
                        if (originalVideo != null) {
                            TitleLayouts.originalTitleFetched(videoId, originalVideo.title());
                        }
                    }
                    return null;
                }
            }
        }
        return isChannelName(videoId, title) ? null : title;
    }

    /**
     * @return The title that starts the accessibility label of the video, or null if none.
     */
    @Nullable
    private static String findLabeledCandidateTitle(List<ProtoNode> textNodes, Set<String> texts) {
        String[] labelAndTitle = findLabeledTitle(texts);
        String labeledLabel = labelAndTitle == null ? null : labelAndTitle[0];
        String labeledTitle = labelAndTitle == null ? null : labelAndTitle[1];
        if (labeledTitle == null) {
            return null;
        }
        // The accessibility label of a video is a field of the component that includes the title,
        // such as the lockup of the video. Other components shown with the video can have their own
        // label and title, such as a product: their label is not a field of a component that
        // includes the title of the video.
        List<ProtoNode> titleNodes = new ArrayList<>();
        List<ProtoNode> labelMessages = new ArrayList<>();
        for (ProtoNode node : textNodes) {
            String text = removeTitleMarker(node.getText()).trim();
            if (text.equals(labeledTitle)) {
                titleNodes.add(node);
            } else if (text.equals(labeledLabel) && node.getParent() != null) {
                labelMessages.add(node.getParent());
            }
        }
        boolean labelOfTitle = false;
        for (ProtoNode titleNode : titleNodes) {
            for (ProtoNode parent = titleNode.getParent(); parent != null && !labelOfTitle;
                 parent = parent.getParent()) {
                labelOfTitle = labelMessages.contains(parent);
            }
        }
        if (!labelOfTitle) {
            return null;
        }
        // The label of a long title can include the title truncated, and the element the entire title.
        // Texts that include the truncated title with the ellipsis are not the entire title,
        // such as the accessibility label 'Start of the title... - play Short'.
        for (String text : texts) {
            if (isTruncatedTitle(labeledTitle, text) && !text.contains(labeledTitle)) {
                return text;
            }
        }
        return labeledTitle;
    }

    /**
     * Starts fetching the titles that verify which text of an element is the title,
     * if not yet fetched, such as a title removed from the cache. The title in the language
     * of the app is fetched only if the element does not show the original title, which is fetched first.
     *
     * @param texts Texts of the element, or null if not known, such as in the text hook.
     *              The title in the language of the app is then not fetched.
     */
    private static void requestVerification(String videoId, @Nullable Set<String> texts) {
        CompletableFuture<?> originalRequest = OriginalTitleRequest.fetch(videoId);
        if (originalRequest.isDone()) {
            if (texts != null) {
                requestLocalizedTitleIfNeeded(videoId, texts);
            }
        } else {
            if (texts != null) {
                originalRequest.thenRun(() -> requestLocalizedTitleIfNeeded(videoId, texts));
            }
            relayoutWhenFetched(originalRequest);
        }
    }

    private static void requestLocalizedTitleIfNeeded(String videoId, Set<String> texts) {
        OriginalTitleRequest.OriginalVideo originalVideo = OriginalTitleRequest.getOriginalIfFetched(videoId);
        if (originalVideo != null && findTitle(videoId, texts, Collections.singletonList(originalVideo.title())) != null) {
            return;
        }

        if (RequestBackoff.isPaused()) {
            // Not fetched while paused, so the elements loaded meanwhile do not lay out the texts again,
            // such as when scrolling a feed. The texts found as the title are fetched again later.
            return;
        }
        CompletableFuture<String> request = LocalizedTitleRequest.fetch(videoId);
        if (!localizedTitleRequests.add(request)) {
            return;
        }
        final String language = Locale.getDefault().toLanguageTag();
        request.thenAccept(localizedTitle -> {
            localizedTitleRequests.remove(request);
            // Other elements can show the title without the video id, such as the player overlay.
            if (localizedTitle != null && language.equals(titlesLanguage)) {
                putTranslatedTitle(localizedTitle.trim(), Collections.emptySet(), videoId);
            }
            LithoRelayoutPatch.relayoutOutdatedTexts();
        });
    }

    /**
     * Starts fetching the other titles that the text found as the title can be, if no text of the
     * element is the original title or the title in the language of the app, which are fetched first:
     * the title shown in the lists, and then the title in the language of the text.
     * The titles of each text found as the title of the video are fetched once, or again if they
     * failed to fetch because of network errors.
     *
     * @param texts Texts of the element.
     * @return If the other titles of the text are being fetched.
     */
    private static boolean requestCandidateTitle(String videoId, Set<String> texts, String candidate) {
        return requestCandidateTitle(videoId, texts, candidate, 0);
    }

    /**
     * @param retries Number of times the titles were fetched again after failing to fetch.
     */
    private static boolean requestCandidateTitle(String videoId, Set<String> texts, String candidate,
                                                 int retries) {
        Map<String, CompletableFuture<String>> requests = candidateTitleRequests.computeIfAbsent(
                candidateKey(videoId),
                key -> Collections.synchronizedMap(
                        Utils.createSizeRestrictedMap(MAX_CANDIDATE_REQUESTS_PER_VIDEO)));
        CompletableFuture<String> request;
        synchronized (requests) {
            request = requests.get(candidate);
            if (request != null) {
                return !request.isDone();
            }
            request = new CompletableFuture<>();
            requests.put(candidate, request);
        }

        final CompletableFuture<String> candidateRequest = request;
        List<String> elementTexts = new ArrayList<>(texts);
        OriginalTitleRequest.fetch(videoId).thenComposeAsync(titles -> {
            OriginalTitleRequest.OriginalVideo originalVideo = OriginalTitleRequest.getOriginalIfFetched(videoId);
            if (originalVideo != null && findTitle(videoId, elementTexts,
                    Collections.singletonList(originalVideo.title())) != null) {
                return CompletableFuture.completedFuture(false);
            }
            // The title in the language of the app is already fetched or being fetched.
            return LocalizedTitleRequest.fetch(videoId).thenCompose(localizedTitle -> {
                if (localizedTitle != null && findTitle(videoId, elementTexts,
                        Collections.singletonList(localizedTitle)) != null) {
                    return CompletableFuture.completedFuture(false);
                }
                return LocalizedTitleRequest.fetchListTitle(videoId).thenCompose(listTitle -> {
                    if (listTitle != null && findTitle(videoId, elementTexts,
                            Collections.singletonList(listTitle)) != null) {
                        Logger.printDebug(() -> "Title shown in the lists of: " + videoId + " is: " + listTitle);
                        candidateRequest.complete(listTitle);
                        return CompletableFuture.completedFuture(true);
                    }
                    return LocalizedTitleRequest.fetchInLanguageOf(videoId, candidate).thenCompose(title -> {
                        if (title != null && findTitle(videoId, elementTexts,
                                Collections.singletonList(title)) != null) {
                            candidateRequest.complete(title);
                            return CompletableFuture.completedFuture(true);
                        }
                        // The language of the text is not always detected on the device, and the title
                        // can be auto-translated to the language of a search, so the title is searched.
                        return LocalizedTitleRequest.fetchSearchTitle(videoId, candidate).thenApply(searchTitle -> {
                            if (searchTitle != null) {
                                Logger.printDebug(() -> "Title shown in the search of the text of: " + videoId
                                        + " is: " + searchTitle);
                            }
                            candidateRequest.complete(searchTitle);
                            return true;
                        });
                    });
                });
            });
        }, Utils::runOnBackgroundThread).whenComplete((fetched, ex) -> {
            if (ex == null) {
                if (!fetched) {
                    // The element shows the original title or the title in the language of the app,
                    // so other elements of the video can still show the title in another language.
                    requests.remove(candidate, candidateRequest);
                    candidateRequest.complete(null);
                }
                return;
            }
            Throwable cause = ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof IOException ioException) {
                Logger.printInfo(() -> "Could not fetch other titles of: " + videoId, ioException);
                retryCandidateTitle(videoId, texts, candidate, retries, requests, candidateRequest);
            } else {
                Logger.printException(() -> "requestCandidateTitle failure", cause);
            }
            candidateRequest.complete(null);
        });

        if (candidateRequest.isDone()) {
            return false;
        }
        relayoutWhenFetched(candidateRequest);
        return true;
    }

    private static String candidateKey(String videoId) {
        return videoId + ' ' + Locale.getDefault().toLanguageTag();
    }

    /**
     * Fetches again the other titles of a text that failed to fetch, after the requests to the server
     * are no longer paused and only if the text is shown, so the elements scrolled off-screen do not
     * fetch at the same time. The text is shown as loading until the title is verified.
     * After the maximum number of retries, the text shows the title of the app, and the titles are
     * fetched again only when the element is loaded again.
     */
    private static void retryCandidateTitle(String videoId, Set<String> texts, String candidate, int retries,
                                            Map<String, CompletableFuture<String>> requests,
                                            CompletableFuture<String> failedRequest) {
        final long delay = Math.max(CANDIDATE_RETRY_MILLISECONDS, RequestBackoff.pauseRemainingMilliseconds());
        if (retries >= MAX_CANDIDATE_RETRIES) {
            Utils.runOnMainThreadDelayed(() -> requests.remove(candidate, failedRequest), delay);
            return;
        }

        CandidateRetry retry = new CandidateRetry(videoId, texts, candidate, retries, requests, failedRequest);
        // Saved before the failed request is done, so the texts laid out again find the retry.
        candidateRetries.put(candidateKey(videoId), retry);
        final long retryTime = System.currentTimeMillis() + delay;
        Utils.runOnMainThreadNowOrLater(() -> {
            retry.retryTime = retryTime;
            pendingRetries.add(retry);
            if (pendingRetries.size() > MAX_PENDING_RETRIES) {
                cancelRetry(pendingRetries.remove(0));
            }
            scheduleRetryCheck();
        });
    }

    /**
     * Checks the pending retries when the next one can be fetched again.
     * Must be called on the main thread.
     */
    private static void scheduleRetryCheck() {
        long checkTime = Long.MAX_VALUE;
        for (CandidateRetry retry : pendingRetries) {
            checkTime = Math.min(checkTime, retry.retryTime);
        }
        if (checkTime == Long.MAX_VALUE) {
            return;
        }
        checkTime = Math.max(checkTime, System.currentTimeMillis() + RequestBackoff.pauseRemainingMilliseconds());
        if (retryCheckTime != 0 && retryCheckTime <= checkTime) {
            return;
        }
        retryCheckTime = checkTime;
        Utils.runOnMainThreadDelayed(RestoreOriginalTitlesPatch::checkPendingRetries,
                Math.max(0, checkTime - System.currentTimeMillis()));
    }

    /**
     * Fetches again the other titles of the texts that can be fetched again and are shown,
     * and keeps the others until they are shown again.
     * Must be called on the main thread.
     */
    private static void checkPendingRetries() {
        retryCheckTime = 0;
        try {
            if (RequestBackoff.isPaused()) {
                return;
            }
            final long now = System.currentTimeMillis();
            List<CandidateRetry> readyRetries = new ArrayList<>();
            Iterator<CandidateRetry> iterator = pendingRetries.iterator();
            while (iterator.hasNext()) {
                CandidateRetry retry = iterator.next();
                if (now >= retry.retryTime) {
                    iterator.remove();
                    // Loading the element again also fetches the titles again, such as after it's no longer shown.
                    retry.requests.remove(retry.candidate, retry.failedRequest);
                    readyRetries.add(retry);
                }
            }
            if (!readyRetries.isEmpty()) {
                startShownRetries(readyRetries, true);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "checkPendingRetries failure", ex);
        } finally {
            scheduleRetryCheck();
        }
    }

    /**
     * Fetches again the other titles of the texts that are shown, together with the other waiting texts
     * of the same videos, as the title of a video is shown as loading until the other titles of all the
     * texts of the video are fetched. The texts that are not shown are kept until they are shown again.
     * The shown texts are found once for all the retries, so the check does not slow down the app.
     * Must be called on the main thread.
     *
     * @param hideNotShown If the retries that are not shown are kept until they are shown again.
     */
    private static void startShownRetries(List<CandidateRetry> retries, boolean hideNotShown) {
        // The texts show the span of the last retry of the video.
        Set<String> shownVideoIds = new HashSet<>();
        for (RetryTitleSpan span : LithoRelayoutPatch.findShownSpans(RetryTitleSpan.class)) {
            shownVideoIds.add(span.retry().videoId);
        }

        Set<String> startedVideoIds = new HashSet<>();
        for (CandidateRetry retry : retries) {
            if (retry.isVerified()) {
                // Verified meanwhile, so the text shows the title without fetching again.
                retry.done.complete(null);
            } else if (shownVideoIds.contains(retry.videoId)) {
                startedVideoIds.add(retry.videoId);
                startRetry(retry);
            } else if (hideNotShown) {
                Logger.printDebug(() -> "Title of: " + retry.videoId + " is not shown, fetched again when shown");
                hiddenRetries.add(retry);
                if (hiddenRetries.size() > MAX_PENDING_RETRIES) {
                    cancelRetry(hiddenRetries.remove(0));
                }
                LithoRelayoutPatch.setLithoViewMeasuredListener(RestoreOriginalTitlesPatch::onLithoViewMeasured);
            }
        }
        if (startedVideoIds.isEmpty()) {
            return;
        }
        for (List<CandidateRetry> waitingRetries : Arrays.asList(pendingRetries, hiddenRetries)) {
            Iterator<CandidateRetry> iterator = waitingRetries.iterator();
            while (iterator.hasNext()) {
                CandidateRetry retry = iterator.next();
                if (startedVideoIds.contains(retry.videoId)) {
                    iterator.remove();
                    retry.requests.remove(retry.candidate, retry.failedRequest);
                    startRetry(retry);
                }
            }
        }
        if (hiddenRetries.isEmpty()) {
            LithoRelayoutPatch.setLithoViewMeasuredListener(null);
        }
    }

    /**
     * The titles are no longer fetched again, so the text shows the title of the app,
     * and the titles are fetched again only when the element is loaded again.
     */
    private static void cancelRetry(CandidateRetry retry) {
        retry.requests.remove(retry.candidate, retry.failedRequest);
        retry.done.complete(null);
    }

    /**
     * Called on the main thread when a Litho view is measured, while any retry is hidden.
     */
    private static void onLithoViewMeasured() {
        if (!hiddenRetryCheckScheduled) {
            hiddenRetryCheckScheduled = true;
            Utils.runOnMainThreadDelayed(RestoreOriginalTitlesPatch::checkHiddenRetries,
                    HIDDEN_RETRY_CHECK_DELAY_MILLISECONDS);
        }
    }

    /**
     * Fetches again the other titles of the hidden texts that are shown again.
     * While the requests are paused, they are fetched again when no longer paused.
     * Must be called on the main thread.
     */
    private static void checkHiddenRetries() {
        hiddenRetryCheckScheduled = false;
        try {
            if (hiddenRetries.isEmpty()) {
                LithoRelayoutPatch.setLithoViewMeasuredListener(null);
                return;
            }
            Set<String> shownVideoIds = new HashSet<>();
            for (RetryTitleSpan span : LithoRelayoutPatch.findShownSpans(RetryTitleSpan.class)) {
                shownVideoIds.add(span.retry().videoId);
            }
            List<CandidateRetry> shownRetries = new ArrayList<>();
            Iterator<CandidateRetry> iterator = hiddenRetries.iterator();
            while (iterator.hasNext()) {
                CandidateRetry retry = iterator.next();
                if (shownVideoIds.contains(retry.videoId)) {
                    iterator.remove();
                    shownRetries.add(retry);
                }
            }
            if (shownRetries.isEmpty()) {
                return;
            }
            if (RequestBackoff.isPaused()) {
                final long retryTime = System.currentTimeMillis() + RequestBackoff.pauseRemainingMilliseconds();
                for (CandidateRetry retry : shownRetries) {
                    retry.retryTime = retryTime;
                    pendingRetries.add(retry);
                }
                scheduleRetryCheck();
            } else {
                for (CandidateRetry retry : shownRetries) {
                    Logger.printDebug(() -> "Title of: " + retry.videoId + " is shown again, fetching again");
                }
                startShownRetries(shownRetries, false);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "checkHiddenRetries failure", ex);
        } finally {
            if (hiddenRetries.isEmpty()) {
                LithoRelayoutPatch.setLithoViewMeasuredListener(null);
            }
        }
    }

    /**
     * Must be called on the main thread.
     */
    private static void startRetry(CandidateRetry retry) {
        retry.started = true;
        Utils.runOnBackgroundThread(() -> {
            requestCandidateTitle(retry.videoId, retry.texts, retry.candidate, retry.retries + 1);
            Map<String, CompletableFuture<String>> requests = candidateTitleRequests.get(candidateKey(retry.videoId));
            CompletableFuture<String> request = requests == null ? null : requests.get(retry.candidate);
            if (request == null) {
                retry.done.complete(null);
            } else {
                request.whenComplete((title, ex) -> retry.done.complete(null));
            }
            // The text shows the loading text until the title is verified.
            LithoRelayoutPatch.relayoutOutdatedTexts();
        });
    }

    /**
     * @return The titles that a text can be if it's the title of the video: the original title,
     *         the title in the language of the app if it was fetched, and the other titles of the texts
     *         found as the title that were fetched. Null if the titles are being fetched.
     */
    @Nullable
    private static List<String> verifiedTitles(String videoId) {
        if (OriginalTitleRequest.isPending(videoId) || LocalizedTitleRequest.isPending(videoId)) {
            return null;
        }
        List<String> candidateTitles = null;
        Map<String, CompletableFuture<String>> candidateRequests = candidateTitleRequests.get(candidateKey(videoId));
        if (candidateRequests != null) {
            synchronized (candidateRequests) {
                for (CompletableFuture<String> request : candidateRequests.values()) {
                    if (!request.isDone()) {
                        return null;
                    }
                    String title = request.getNow(null);
                    if (title != null) {
                        if (candidateTitles == null) {
                            candidateTitles = new ArrayList<>(1);
                        }
                        candidateTitles.add(title);
                    }
                }
            }
        }

        List<String> titles = new ArrayList<>(3);
        OriginalTitleRequest.OriginalVideo originalVideo = OriginalTitleRequest.getOriginalIfFetched(videoId);
        if (originalVideo != null) {
            titles.add(originalVideo.title());
        }
        CompletableFuture<String> localizedRequest = LocalizedTitleRequest.getRequest(videoId);
        String localizedTitle = localizedRequest == null ? null : localizedRequest.getNow(null);
        if (localizedTitle != null) {
            titles.add(localizedTitle);
        }
        if (candidateTitles != null) {
            titles.addAll(candidateTitles);
        }
        return titles;
    }

    /**
     * Elements can show the title truncated, such as 'Start of the title...'.
     *
     * @param titles Titles that the text can be, from {@link #verifiedTitles(String)}.
     * @return The text that is a title seen before for the video or one of the titles,
     *         preferring the entire title to a truncated title, or null if none.
     */
    @Nullable
    private static String findTitle(String videoId, Collection<String> texts, List<String> titles) {
        List<String> normalizedTitles = new ArrayList<>(titles.size());
        for (String title : titles) {
            normalizedTitles.add(normalizeTitle(title));
        }

        String bestTitle = null;
        int bestRank = 0;
        for (String text : texts) {
            if (text.isEmpty()) {
                continue;
            }
            String normalizedText = normalizeTitle(text);
            int rank = 0;
            for (String title : normalizedTitles) {
                if (normalizedText.equals(title)) {
                    rank = 2;
                    break;
                }
                if (isTruncatedTitle(normalizedText, title)) {
                    rank = 1;
                }
            }
            if (rank == 0 && isTranslatedTitle(text, videoId)) {
                rank = 1;
            }
            if (rank > bestRank || (rank > 0 && rank == bestRank && text.length() > bestTitle.length())) {
                bestTitle = text;
                bestRank = rank;
            }
        }
        return bestTitle;
    }

    /**
     * Titles of the responses and of the elements can have different invisible characters,
     * such as the bidirectional marks of right to left languages, different spaces,
     * and different forms of the same characters, such as the precomposed and decomposed
     * forms of the Devanagari letters with a nukta, or the full width forms of punctuation.
     *
     * @return The title in the compatibility composed form, without format characters,
     *         and with single spaces.
     */
    private static String normalizeTitle(String title) {
        title = Normalizer.normalize(title, Normalizer.Form.NFKC);
        StringBuilder builder = new StringBuilder(title.length());
        boolean space = false;
        for (int i = 0, length = title.length(); i < length; i++) {
            final char c = title.charAt(i);
            if (Character.getType(c) == Character.FORMAT) {
                continue;
            }
            if (Character.isWhitespace(c) || Character.isSpaceChar(c)) {
                //noinspection SizeReplaceableByIsEmpty
                space = builder.length() > 0;
                continue;
            }
            if (space) {
                builder.append(' ');
                space = false;
            }
            builder.append(c);
        }
        return builder.toString();
    }

    /**
     * Saves the texts of an element whose title is not yet verified, so the text hook replaces
     * the text that is the title when the title is verified. Texts are saved with the component
     * of the element, as other components can show the same text, such as a comment.
     * Texts of elements without a component are saved only if they are not short.
     */
    private static void addPendingTitleTexts(Set<String> texts, String videoId, String component) {
        for (String text : texts) {
            if (!isLabelCandidate(text) || (component.isEmpty() && text.length() < MIN_TITLE_LENGTH)) {
                continue;
            }
            synchronized (pendingTitleTexts) {
                pendingTitleTexts.computeIfAbsent(component + '\n' + text, key -> new HashSet<>()).add(videoId);
            }
        }
    }

    /**
     * Starts fetching the titles that verify the text if not yet fetched.
     *
     * @return A video whose title is being verified, or null if the titles of all the videos are verified.
     */
    @Nullable
    private static String findPendingVerification(Set<String> videoIds) {
        String pendingVideoId = null;
        for (String videoId : videoIds) {
            requestVerification(videoId, null);
            if (verifiedTitles(videoId) == null) {
                pendingVideoId = videoId;
            }
        }
        return pendingVideoId;
    }

    /**
     * The Litho text does not show which element it's of, so the text is the title only if
     * it's the title of all the videos whose elements show the text, and the titles have the same
     * replacement, such as Shorts with the same title. A text that is the title of a video and
     * another text of an element of another video is not replaced, such as a short title that is
     * the same as the name of a channel.
     *
     * @param videoIds Videos whose titles are verified.
     * @return The video id of the title, or null if the text is not the title of all the videos.
     */
    @Nullable
    private static String findVerifiedVideoId(Set<String> videoIds, String text) {
        String videoId = null;
        String replacement = null;
        for (String id : videoIds) {
            List<String> titles = verifiedTitles(id);
            if (titles == null || findTitle(id, Collections.singleton(text), titles) == null) {
                return null;
            }
            String idReplacement = OriginalTitleRequest.getIfAvailable(id);
            if (videoId == null) {
                videoId = id;
                replacement = idReplacement;
            } else if (replacement == null || !replacement.equals(idReplacement)) {
                return null;
            }
        }
        return videoId;
    }

    /**
     * The link of a Short to a related video includes the video id as a text, and not in a thumbnail
     * or in a key, so the video of the element is the Short. The texts of the link are the video id,
     * the title and the accessibility id of the link, in this order and in the same message.
     * Other texts of the element can look like a video id, such as 'shortswatch',
     * so the texts are found by their order and not by their pattern only.
     *
     * @return If any title was replaced.
     */
    private static boolean restoreLinkedVideoTitles(List<ProtoNode> textNodes, String component) {
        boolean modified = false;
        for (int i = 2, size = textNodes.size(); i < size; i++) {
            ProtoNode linkNode = textNodes.get(i);
            if (!SHORTS_VIDEO_LINK_ID.equals(linkNode.getText())) {
                continue;
            }
            ProtoNode titleNode = textNodes.get(i - 1);
            String title = removeTitleMarker(titleNode.getText()).trim();
            // Keys can be between the video id and the title.
            ProtoNode videoIdNode = null;
            for (int j = i - 2; j >= Math.max(0, i - 1 - LINK_MAX_TEXTS_BEFORE_TITLE); j--) {
                ProtoNode candidate = textNodes.get(j);
                if (VIDEO_ID_PATTERN.matcher(candidate.getText().trim()).matches()) {
                    videoIdNode = candidate;
                    break;
                }
            }
            if (videoIdNode != null && isLabelCandidate(title)
                    && isNearby(linkNode, titleNode) && isNearby(linkNode, videoIdNode)) {
                modified |= restoreTitle(Arrays.asList(videoIdNode, titleNode, linkNode),
                        Collections.singleton(title), videoIdNode.getText().trim(), component, null, () -> title, null);
            }
        }
        return modified;
    }

    /**
     * Maximum number of texts of a link of a Short before its title, to find the video id.
     */
    private static final int LINK_MAX_TEXTS_BEFORE_TITLE = 3;

    /**
     * Fields of the same small component, such as a link, have a common ancestor within a few levels.
     */
    private static final int NEARBY_ANCESTOR_LEVELS = 6;

    /**
     * @return If the fields have a common ancestor within a few levels of both.
     */
    private static boolean isNearby(ProtoNode first, ProtoNode second) {
        Set<ProtoNode> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
        ProtoNode ancestor = first.getParent();
        for (int level = 0; ancestor != null && level < NEARBY_ANCESTOR_LEVELS; level++) {
            ancestors.add(ancestor);
            ancestor = ancestor.getParent();
        }
        ancestor = second.getParent();
        for (int level = 0; ancestor != null && level < NEARBY_ANCESTOR_LEVELS; level++) {
            if (ancestors.contains(ancestor)) {
                return true;
            }
            ancestor = ancestor.getParent();
        }
        return false;
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
     * Elements without an accessibility label can have other texts that start with a text,
     * such as the views and date 'Views - Date', or a title that starts with the channel name
     * 'Channel - Song'. The accessibility label of a video also includes the other texts
     * of the video after the title, such as the channel or the views.
     * The accessibility label of a channel includes the channel handle after the channel name.
     * The accessibility label of a Short can include only the title and the action to play it,
     * such as 'Title - play Short' of a Short without views or with a long title.
     *
     * @param afterColon If the title follows a colon instead of starting the label, as the labels
     *                   of some languages, such as the Russian label that translates to
     *                   'Play video "Duration" - on channel "Channel"; - duration: Title - Channel - Views - Date'.
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
     * @return If the text is the start of the title followed by an ellipsis.
     */
    private static boolean isTruncatedTitle(String text, String title) {
        final int ellipsisLength = text.endsWith("…") ? 1 : text.endsWith("...") ? 3 : 0;
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
