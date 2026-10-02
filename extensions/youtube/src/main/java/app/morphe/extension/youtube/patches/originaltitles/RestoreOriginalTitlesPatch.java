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
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
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
 * Replaces the auto-translated video titles and descriptions with the original titles and descriptions.
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

    private static final String DESCRIPTION_IDENTIFIER = "description_rich_text_list.eml";
    private static final String DESCRIPTION_REDIRECT_PATH = "/redirect";

    /**
     * Header of the channel page, with the first line of the channel description.
     */
    private static final String CHANNEL_HEADER_IDENTIFIER = "page_header.eml";
    /**
     * Panel opened from the channel header, with the entire channel description.
     */
    private static final String CHANNEL_ABOUT_IDENTIFIER = "about_channel_view.eml";

    /**
     * Elements with video thumbnails, the watch page title elements without thumbnails,
     * the description of the description panel, and the channel description.
     */
    private static final ByteTrieSearch elementSearch = new ByteTrieSearch(
            ByteTrieSearch.convertStringsToBytes(
                    "/vi/",
                    "/vi_webp/",
                    "video_metadata.eml",
                    "player_overlay_video_heading.eml",
                    DESCRIPTION_IDENTIFIER,
                    CHANNEL_HEADER_IDENTIFIER,
                    CHANNEL_ABOUT_IDENTIFIER
            ));

    /**
     * Playlists use the thumbnail of the first video, but show the playlist title.
     */
    private static final String PLAYLIST_URL = "playlist?list=";

    /**
     * Shown until the original title is fetched.
     */
    private static final StringRef LOADING_TITLE = StringRef.sfc("morphe_restore_original_titles_loading");

    /**
     * Marks the Litho loading texts with the video id of the title that is loading.
     */
    private record LoadingTitleSpan(String videoId) {
    }

    /**
     * Loading texts of titles that are no longer loading, which are laid out again.
     */
    private static final Predicate<CharSequence> LOADED_TITLE_FILTER = text -> {
        if (!(text instanceof Spanned spanned) || !LOADING_TITLE.toString().contentEquals(text)) {
            return false;
        }
        for (LoadingTitleSpan span : spanned.getSpans(0, spanned.length(), LoadingTitleSpan.class)) {
            if (!OriginalTitleRequest.isPending(span.videoId())) {
                return true;
            }
        }
        return false;
    };

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
     * Litho identifiers, such as 'video_lockup_with_attachment.eml-fe|c0c4a49b6544b5fb'.
     */
    private static final Pattern IDENTIFIER_PATTERN =
            Pattern.compile("^\\S+\\|[0-9a-f]{16}$");

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
     * Translated title -> video id.
     */
    private static final Map<String, String> translatedTitles =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

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
     * Translated description previews whose original description was fetched after the
     * header was laid out. Previews of descriptions that are not translated are not matched,
     * as laying them out again does not change them.
     */
    private static final Predicate<CharSequence> TRANSLATED_CHANNEL_PREVIEW_FILTER =
            text -> restoreChannelPreviewText(text) != null;

    /**
     * Injection point.
     */
    public static void newVideoLoaded(String videoId) {
        try {
            if (!Settings.RESTORE_ORIGINAL_TITLES.get() || videoId == null || videoId.isEmpty()) {
                return;
            }

            openedVideoId = videoId;
            OriginalDescriptionRequest.fetchRequestIfNeeded(videoId);
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoLoaded failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static byte[] restoreOriginalTitle(byte[] bytes) {
        try {
            if (!Settings.RESTORE_ORIGINAL_TITLES.get() || !elementSearch.matches(bytes)) {
                return bytes;
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
                if (identifier.startsWith(DESCRIPTION_IDENTIFIER)) {
                    return restoreDescription(root, textNodes) ? ProtoNode.write(root) : bytes;
                }
                if (identifier.startsWith(CHANNEL_HEADER_IDENTIFIER)) {
                    return restoreChannelHeader(textNodes) ? ProtoNode.write(root) : bytes;
                }
                if (identifier.startsWith(CHANNEL_ABOUT_IDENTIFIER)) {
                    return restoreChannelAbout(root) ? ProtoNode.write(root) : bytes;
                }
            }

            Map<List<ProtoNode>, Set<String>> messageVideoIds = new IdentityHashMap<>();
            findVideoIds(root, findThumbnailVideoIds(textNodes), messageVideoIds);
            boolean modified = restoreVideoTitles(root, messageVideoIds, identifier);
            modified |= restoreKnownTitles(textNodes);

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
            if (!Settings.RESTORE_ORIGINAL_TITLES.get() || text == null) {
                return text;
            }

            CharSequence channelPreview = restoreChannelPreview(text);
            if (channelPreview != null) {
                return channelPreview;
            }

            if (text.length() > maxTranslatedTitleLength.get()) {
                return text;
            }

            String translatedTitle = text.toString();
            String videoId = translatedTitles.get(translatedTitle);
            if (videoId == null) {
                return text;
            }

            // Litho texts are loaded on a single thread, so the title is not waited for.
            String originalTitle = OriginalTitleRequest.getIfAvailable(videoId);
            final boolean loading = originalTitle == null;
            if (loading) {
                if (!OriginalTitleRequest.isPending(videoId)) {
                    return text;
                }
                originalTitle = LOADING_TITLE.toString();
                relayoutWhenFetched(OriginalTitleRequest.fetch(videoId), LOADED_TITLE_FILTER);
            }
            if (originalTitle.equals(translatedTitle)) {
                return text;
            }

            SpannableString replacement = new SpannableString(originalTitle);
            if (loading) {
                replacement.setSpan(new LoadingTitleSpan(videoId), 0, replacement.length(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            copyEntireTextSpans(text, replacement);
            return replacement;
        } catch (Exception ex) {
            Logger.printException(() -> "onLithoTextLoaded failure", ex);
        }

        return text;
    }

    /**
     * Only spans that style the entire text can be applied to a different text.
     */
    private static void copyEntireTextSpans(CharSequence text, SpannableString replacement) {
        if (text instanceof Spanned spanned) {
            final int translatedLength = spanned.length();
            final int replacementLength = replacement.length();
            for (Object span : spanned.getSpans(0, translatedLength, Object.class)) {
                if (spanned.getSpanStart(span) == 0 && spanned.getSpanEnd(span) == translatedLength) {
                    replacement.setSpan(span, 0, replacementLength, spanned.getSpanFlags(span));
                }
            }
        }
    }

    /**
     * Replaces the description preview of the channel header when it's laid out,
     * if the original description was not yet fetched when the element was parsed.
     *
     * @return The original description preview, or null if the text is not a translated preview.
     */
    @Nullable
    private static CharSequence restoreChannelPreview(CharSequence text) {
        String restoredText = restoreChannelPreviewText(text);
        if (restoredText == null) {
            return null;
        }

        SpannableString replacement = new SpannableString(restoredText);
        copyEntireTextSpans(text, replacement);
        return replacement;
    }

    /**
     * @return The text with the original description, or null if the text is not a translated preview,
     *         or the original description is not yet fetched or is the same.
     */
    @Nullable
    private static String restoreChannelPreviewText(CharSequence text) {
        if (text.length() > maxTranslatedChannelPreviewLength.get()) {
            return null;
        }

        String translatedText = text.toString();
        ChannelPreview preview = translatedChannelPreviews.get(translatedText.trim());
        if (preview == null) {
            return null;
        }

        String originalPreview = getOriginalChannelPreview(preview.channelId());
        return originalPreview == null || originalPreview.equals(preview.translatedDescription())
                ? null
                : translatedText.replace(preview.translatedDescription(), originalPreview);
    }

    /**
     * Injection point.
     * <p>
     * Sets the original title of a view that shows the title of a video,
     * such as a video of the playlist panel.
     */
    public static void restoreOriginalTitle(TextView view, String videoId) {
        try {
            if (!Settings.RESTORE_ORIGINAL_TITLES.get() || view == null || videoId == null) {
                return;
            }

            CharSequence translatedTitle = view.getText();
            String translatedTitleText = translatedTitle.toString().trim();
            if (!translatedTitleText.isEmpty()) {
                putTranslatedTitle(translatedTitleText, videoId);
            }

            // Views are reused for other videos, so the title is only set if the view
            // still shows the same video when the title is fetched.
            titleViewVideoIds.put(view, videoId);

            if (OriginalTitleRequest.getIfAvailable(videoId) == null && OriginalTitleRequest.isPending(videoId)) {
                view.setText(LOADING_TITLE.toString());
            }

            WeakReference<TextView> viewRef = new WeakReference<>(view);
            OriginalTitleRequest.getAsync(videoId, originalTitle -> {
                TextView titleView = viewRef.get();
                if (titleView == null || !titleViewVideoIds.remove(titleView, videoId)) {
                    return;
                }
                CharSequence title = originalTitle == null ? translatedTitle : originalTitle;
                // Setting the same text again would notify the text listeners again.
                if (!TextUtils.equals(title, titleView.getText())) {
                    titleView.setText(title);
                }
            });
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
                if (!Settings.RESTORE_ORIGINAL_TITLES.get()) {
                    return;
                }

                String title = text.toString().trim();
                if (title.equals(LOADING_TITLE.toString())) {
                    return;
                }

                String videoId = translatedTitles.get(title);
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
     * @return If the description was replaced.
     */
    private static boolean restoreDescription(List<ProtoNode> root, List<ProtoNode> textNodes) {
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

        return OriginalDescription.restore(root, originalDescription);
    }

    /**
     * Loads the Litho texts that match the filter again when the request is done, such as
     * the texts that show the loading title, or the translated description preview of a channel.
     * Each request lays out the texts once, including requests made again after a failure.
     */
    private static void relayoutWhenFetched(CompletableFuture<String> request,
                                            Predicate<CharSequence> textFilter) {
        if (!relayoutRequests.add(request)) {
            return;
        }

        request.thenRun(() -> {
            relayoutRequests.remove(request);
            LithoRelayoutPatch.relayoutViewsShowingText(textFilter);
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
        ProtoNode[] previewAndLabel = findChannelPreview(textNodes);
        if (previewAndLabel == null) {
            Logger.printDebug(() -> "Channel description preview not found");
            return false;
        }
        ProtoNode preview = previewAndLabel[0];
        ProtoNode label = previewAndLabel[1];

        // The preview message includes the truncation text and the command that opens
        // the description panel of the channel.
        ProtoNode previewText = preview.getParent();
        ProtoNode previewMessage = previewText == null ? null : previewText.getParent();
        List<ProtoNode> previewTextNodes = previewMessage == null || previewMessage.children == null
                ? textNodes
                : ProtoNode.textNodes(previewMessage.children);

        String channelId = findChannelId(previewTextNodes);
        if (channelId == null) {
            channelId = findChannelId(textNodes);
            if (channelId == null) {
                return false;
            }
        }

        String shownPreview = preview.getText();
        String truncationText = findTruncationText(preview, previewTextNodes);
        String translatedPreview = (truncationText == null
                ? shownPreview
                : shownPreview.substring(0, shownPreview.length() - truncationText.length())).trim();

        openedChannelId = channelId;
        openedChannelPreview = translatedPreview;
        translatedChannelPreviews.put(shownPreview.trim(), new ChannelPreview(channelId, translatedPreview));
        maxTranslatedChannelPreviewLength.accumulateAndGet(shownPreview.length(), Math::max);

        String originalPreview = getOriginalChannelPreview(channelId);
        if (originalPreview == null) {
            relayoutWhenFetched(OriginalChannelDescriptionRequest.fetch(channelId),
                    TRANSLATED_CHANNEL_PREVIEW_FILTER);
            return false;
        }
        if (originalPreview.equals(translatedPreview)) {
            return false;
        }

        OriginalDescription.restore(preview, shownPreview.replace(translatedPreview, originalPreview));
        restoreChannelPreviewLabel(label, translatedPreview, originalPreview);

        final String restoredChannelId = channelId;
        Logger.printDebug(() -> "Restored description preview of channel: " + restoredChannelId);
        return true;
    }

    /**
     * The preview ends with the localized truncation text that opens the description panel,
     * such as '...more', which is also a text of the preview message.
     *
     * @return The longest other text of the preview message that ends the preview, or null if none.
     */
    @Nullable
    private static String findTruncationText(ProtoNode preview, List<ProtoNode> previewTextNodes) {
        String previewText = preview.getText();
        String truncationText = null;
        for (ProtoNode node : previewTextNodes) {
            String text = node.getText();
            if (node != preview && !text.trim().isEmpty() && text.length() < previewText.length()
                    && previewText.endsWith(text)
                    && (truncationText == null || text.length() > truncationText.length())) {
                truncationText = text;
            }
        }
        return truncationText;
    }

    @Nullable
    private static String findChannelId(List<ProtoNode> textNodes) {
        for (ProtoNode node : textNodes) {
            String text = node.getText();
            if (CHANNEL_ID_PATTERN.matcher(text).matches()) {
                return text;
            }
        }
        return null;
    }

    /**
     * Replaces the start of the preview in the accessibility label,
     * which can be truncated, such as 'Description. First line of the descr… Tap to read more.'
     */
    private static void restoreChannelPreviewLabel(ProtoNode label, String translatedPreview,
                                                   String originalPreview) {
        String labelText = label.getText();
        final int prefixLength = previewPrefixLength(labelText, translatedPreview);
        if (prefixLength == 0) {
            return;
        }
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

    /**
     * The preview is the first line of the description, and the preview message includes
     * the accessibility label of the preview, which includes the start of the preview and other texts.
     * The label is used to find the preview, as the label and the preview texts are localized.
     *
     * @return The description preview and its accessibility label, or null if not found.
     */
    @Nullable
    private static ProtoNode[] findChannelPreview(List<ProtoNode> textNodes) {
        ProtoNode preview = null;
        ProtoNode label = null;
        int longestPrefix = MIN_TITLE_LENGTH - 1;

        for (ProtoNode node : textNodes) {
            String text = node.getText().trim();
            ProtoNode previewText = node.getParent();
            ProtoNode previewMessage = previewText == null ? null : previewText.getParent();
            if (!isLabelCandidate(text) || previewMessage == null || previewMessage.children == null) {
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

        return preview == null ? null : new ProtoNode[]{preview, label};
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

    private static void putTranslatedTitle(String translatedTitle, String videoId) {
        translatedTitles.put(translatedTitle, videoId);
        maxTranslatedTitleLength.accumulateAndGet(translatedTitle.length(), Math::max);
    }

    private static Set<String> findThumbnailVideoIds(List<ProtoNode> textNodes) {
        Set<String> videoIds = new HashSet<>();
        for (ProtoNode node : textNodes) {
            addThumbnailVideoIds(node.getText(), videoIds);
        }
        return videoIds;
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
                                              @Nullable String identifier) {
        Set<String> videoIds = messageVideoIds.get(message);
        if (videoIds == null || videoIds.isEmpty()) {
            return false;
        }
        if (videoIds.size() == 1) {
            return restoreVideoTitle(message, videoIds.iterator().next(), identifier);
        }

        boolean modified = false;
        for (ProtoNode node : message) {
            List<ProtoNode> children = node.children;
            if (children != null) {
                modified |= restoreVideoTitles(children, messageVideoIds, identifier);
            }
        }
        return modified;
    }

    /**
     * @return If the title was replaced.
     */
    private static boolean restoreVideoTitle(List<ProtoNode> message, String videoId,
                                             @Nullable String identifier) {
        List<ProtoNode> textNodes = ProtoNode.textNodes(message);
        Set<String> texts = new LinkedHashSet<>();
        for (ProtoNode node : textNodes) {
            String text = node.getText().trim();
            if (text.contains(PLAYLIST_URL)) {
                return false;
            }
            if (!text.isEmpty()) {
                texts.add(text);
            }
        }

        String label = null;
        String translatedTitle;
        String[] labeledTitle = findLabeledTitle(texts);
        if (labeledTitle != null) {
            label = labeledTitle[0];
            translatedTitle = labeledTitle[1];
        } else {
            translatedTitle = findUnlabeledTitle(textNodes, identifier);
            if (translatedTitle == null) {
                return false;
            }
        }

        String originalTitle = OriginalTitleRequest.getIfAvailable(videoId);
        if (translatedTitle.equals(originalTitle)) {
            return false;
        }

        putTranslatedTitle(translatedTitle, videoId);
        return originalTitle != null && replaceTitle(textNodes, translatedTitle, originalTitle, label);
    }

    /**
     * Replaces titles seen before in other elements, such as the player overlay title
     * that does not include the video id but shows the same title as the watch page.
     *
     * @return If any title was replaced.
     */
    private static boolean restoreKnownTitles(List<ProtoNode> textNodes) {
        boolean modified = false;

        for (ProtoNode node : textNodes) {
            String text = node.getText().trim();
            String videoId = text.length() >= MIN_TITLE_LENGTH
                    ? translatedTitles.get(text)
                    : null;
            if (videoId == null) {
                continue;
            }

            String originalTitle = OriginalTitleRequest.getIfAvailable(videoId);
            if (originalTitle != null && !originalTitle.equals(text)) {
                modified |= replaceTitle(textNodes, text, originalTitle, null);
            }
        }

        return modified;
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
        for (Map.Entry<String, Integer> entry : labels) {
            String label = entry.getKey();
            String title = findTitleOfLabel(label, texts);
            if (title != null) {
                return new String[]{label, title};
            }
        }
        return null;
    }

    /**
     * @return The title, which is the longest text that starts the accessibility label
     *         and is followed by a punctuation separator, such as 'Title - 10 minutes'.
     *         Any text can be the title, including titles that look like keys such as 'a-ha'.
     */
    @Nullable
    private static String findTitleOfLabel(String label, Set<String> texts) {
        String title = null;
        int titleLength = 0;
        for (String text : texts) {
            final int length = text.length();
            if (length > titleLength && label.startsWith(text) && isFollowedBySeparator(label, length)) {
                title = text;
                titleLength = length;
            }
        }
        return title;
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
     * @return The title of the elements that show the title without an accessibility label.
     */
    @Nullable
    private static String findUnlabeledTitle(List<ProtoNode> textNodes, @Nullable String identifier) {
        if (identifier == null) {
            return null;
        }

        final int[] titlePath;
        if (identifier.startsWith("video_metadata.eml")) {
            titlePath = new int[]{1019, 1, 1};
        } else if (identifier.startsWith("player_overlay_video_heading.eml")) {
            titlePath = new int[]{370844319, 3, 1};
        } else if (identifier.startsWith("reel_player_overlay.eml")) {
            titlePath = new int[]{1080, 1, 1};
        } else {
            return null;
        }

        for (ProtoNode node : textNodes) {
            if (node.pathEndsWith(titlePath)) {
                String title = node.getText().trim();
                return title.isEmpty() ? null : title;
            }
        }
        return null;
    }

    /**
     * @return If the text can be a part of an accessibility label, and is not a key, url or identifier.
     */
    private static boolean isLabelCandidate(String text) {
        return LETTER_PATTERN.matcher(text).find()
                && (WHITESPACE_PATTERN.matcher(text).find() || !TOKEN_PATTERN.matcher(text).find());
    }

    /**
     * Replaces the texts that are the title, the start of the accessibility label,
     * and the title in other texts (such as the accessibility label of the menu button).
     *
     * @return If any text was replaced.
     */
    private static boolean replaceTitle(List<ProtoNode> textNodes, String translatedTitle,
                                        String originalTitle, @Nullable String label) {
        boolean replaced = false;
        final int translatedLength = translatedTitle.length();
        final boolean replaceInsideTexts = translatedLength >= MIN_TITLE_LENGTH;

        for (ProtoNode node : textNodes) {
            String nodeText = node.getText();
            String text = nodeText.trim();
            if (text.equals(translatedTitle)) {
                node.setText(originalTitle);
            } else if (text.equals(label)) {
                node.setText(originalTitle + label.substring(translatedLength));
            } else if (replaceInsideTexts && text.contains(translatedTitle)) {
                node.setText(nodeText.replace(translatedTitle, originalTitle));
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
