/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3758
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.FeedEngagementCountsPatch;
import app.morphe.extension.youtube.patches.FeedVideoActionsPatch;
import app.morphe.extension.youtube.patches.FeedViewCountPatch;
import app.morphe.extension.youtube.patches.PostImageDownloadPatch;
import app.morphe.extension.youtube.patches.PostTranslatePatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

/**
 * Finds the full width video cards of the feeds, and the video id of each card.
 * <p>
 * Feed videos are drawn by Litho, so a card has no view of its own and does not know its video.
 * The root {@link ComponentHost} of a full width video is recognized by its shape:
 * a 16:9 thumbnail at the top and the texts of the video below it.
 * <p>
 * The element of a video includes its thumbnail url, which has the video id, and the texts the
 * card shows. When an element of a single video is parsed, its texts are mapped to its video id.
 * When a card is laid out, the texts it shows are looked up, and the video id most of the texts
 * belong to is the video of the card. Texts that more than one video shows, such as the name of
 * a channel, do not belong to any video.
 */
@SuppressWarnings("unused")
public final class FeedVideoCardsPatch {

    private static final boolean ENABLED = FeedEngagementCountsPatch.ENABLED
            || FeedViewCountPatch.ENABLED
            || Settings.FEED_WATCH_LATER_BUTTON.get()
            // FeedVideoActionsPatch is given every card, so its long press also works
            // when its own features are the only ones on.
            || Settings.FEED_LONG_PRESS_MENU.get()
            || PostImageDownloadPatch.ENABLED
            || PostTranslatePatch.ENABLED;

    /**
     * If the video of every card is needed. The posts are found by their own texts, so with only
     * the post features on, the elements of the videos are not parsed and the cards are not matched
     * to their videos. The elements are parsed on the main thread, while the page loads.
     * {@link FeedVideoActionsPatch} is also given the video of the card for its long press menu.
     */
    private static final boolean VIDEO_CARDS = ENABLED
            && (FeedEngagementCountsPatch.ENABLED
            || FeedViewCountPatch.ENABLED
            || Settings.FEED_WATCH_LATER_BUTTON.get()
            || Settings.FEED_LONG_PRESS_MENU.get());

    /**
     * A card must be as wide as a card of a grid of up to three columns, such as the feed in landscape.
     * Shelves are not used, as they are horizontal lists.
     */
    private static final float MIN_WIDTH_OF_SCREEN = 0.3f;
    public static final float THUMBNAIL_RATIO = 9f / 16f;
    /** Fade in of the badges drawn on the thumbnails. */
    public static final long BADGE_FADE_IN_MS = 100;
    private static final int MIN_WIDTH_DP = 240;

    /** Shorter texts, such as a duration, are shown by too many videos. */
    private static final int MIN_TEXT_LENGTH = 4;
    /** A text this long is almost always a title, and alone finds the video. */
    private static final int TITLE_TEXT_LENGTH = 20;
    /**
     * Texts that are kept. A video has tens of texts, such as its title, channel, view count,
     * accessibility labels and urls, so this is enough for the videos of a long feed.
     */
    private static final int MAX_TEXTS = 4000;

    /**
     * A card whose texts are not drawn yet, or whose video is not known yet, is checked again,
     * because its texts can be drawn without a layout of the card.
     */
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_DELAY_MS = 500;
    /**
     * Delays of the checks after the first retries, while the card is shown. What the card needs can be
     * found later without a layout of the card. The texts of its element can be parsed after the card
     * is laid out, and a title can replace the title of its video.
     */
    private static final long[] LATE_RETRY_DELAYS_MS = {2000, 5000, 10_000};
    /** Time between checks of the cards that are partly shown, while the list scrolls. */
    private static final long SCROLL_CHECK_DELAY_MS = 50;

    /** Duration of a turn of the loading spinner of the badges. */
    private static final long SPINNER_TURN_MS = 1000;
    private static final float SPINNER_SWEEP_DEGREES = 270;
    private static final float SPINNER_STROKE_DP = 1.6f;

    /**
     * Result of {@link #findVideoId(ComponentHost)} for a video card whose texts are not drawn yet,
     * such as while the app returns from the background. The card keeps its last known video
     * until its texts are drawn.
     */
    private static final String NOT_READY = "not_ready";

    /** Value of a text shown by more than one video. */
    private static final String AMBIGUOUS = "";

    /**
     * Delimiter of the parts of the accessibility description of a video card,
     * such as "Title - 12 minutes - Channel - 4.7M views - 2 days ago - play video".
     */
    private static final String DESCRIPTION_DELIMITER = " - ";

    /**
     * Result for a card without a duration on its thumbnail, such as a mix or a playlist,
     * which shows the thumbnail of its first video. Nothing is drawn on these cards.
     * Elements of videos can include playlist ids too, such as the mix a music video
     * starts, so only the duration decides. Never an 11 character video id.
     */
    private static final String PLAYLIST = "playlist";

    private static final Pattern THUMBNAIL_VIDEO_ID_PATTERN =
            Pattern.compile("/vi(?:_webp)?/([A-Za-z0-9_-]{11})/");

    private static final byte[] THUMBNAIL_PATH = "/vi".getBytes(StandardCharsets.US_ASCII);

    /**
     * Invisible format characters, such as the ones the elements of the classic layout add
     * after the title (U+2060 to U+2064), which the card does not show.
     */
    private static final Pattern FORMAT_CHARACTERS_PATTERN = Pattern.compile("\\p{Cf}+");

    /** Text -> video id, or {@link #AMBIGUOUS}. Oldest texts are removed first. */
    @GuardedBy("itself")
    private static final Map<String, String> textVideoIds = Utils
            .createSizeRestrictedMap(MAX_TEXTS, true);

    /**
     * Text -> video id, of only the elements of a single video with a duration, or {@link #AMBIGUOUS} if the text
     * is also shown by another video or by another element, such as of a Short, a mix, a playlist or a live video.
     * Used to know a card by its description before its duration is drawn,
     * see {@link #findVideoIdByDescription(ComponentHost, boolean)}. Oldest texts are removed first.
     */
    @GuardedBy("textVideoIds")
    private static final Map<String, String> durationTextVideoIds = Utils
            .createSizeRestrictedMap(MAX_TEXTS, true);

    /** Titles of the cards whose video was found that are kept. */
    private static final int MAX_FOUND_TITLES = 1000;

    /**
     * Title of a card whose video was found -> its video id, or {@link #AMBIGUOUS}.
     * While other pages are browsed, the texts of their elements replace the texts of the feed,
     * and the feed can be shown again without its elements being parsed again.
     * Used only on the main thread.
     */
    private static final Map<String, String> foundTitleVideoIds = Utils
            .createSizeRestrictedMap(MAX_FOUND_TITLES, true);

    /**
     * Icon of the badge of a live video on its thumbnail, such as 'yt_outline_experimental_live_black_24'.
     * The live badge text depends on the language, and the avatar of a channel that is live
     * shows the same text on its other videos.
     */
    private static final Pattern LIVE_ICON_PATTERN = Pattern.compile("^yt_\\w+_live_\\w+$");

    /** Number of live videos that are kept. */
    private static final int MAX_LIVE_VIDEOS = 200;

    /** Live videos, by video id. Oldest videos are removed first. */
    @GuardedBy("itself")
    private static final Map<String, Boolean> liveVideoIds = Utils
            .createSizeRestrictedMap(MAX_LIVE_VIDEOS, true);

    /**
     * Root host -> video of the card, of the cards laid out last, and card inside a root host -> its video,
     * see {@link #innerCards}. Used only on the main thread.
     */
    private static final Map<ComponentHost, String> cardVideoIds = Collections.synchronizedMap(
            new WeakHashMap<>());

    /**
     * Root host that is not shaped like a full width card -> the hosts inside it that the like count is drawn on:
     * the host of a thumbnail beside the texts (see {@link #findSideThumbnail(ComponentHost)}), or the cards
     * of a row of a grid, such as the shelves of a channel on tablets, which are shaped like full width cards.
     * Used only on the main thread.
     */
    private static final Map<ComponentHost, List<ComponentHost>> innerCards = new WeakHashMap<>();

    /** Shorts that are kept. */
    private static final int MAX_SHORTS = 500;

    /**
     * Shorts, by video id, found by their elements. The cards of Shorts have no duration on their thumbnail,
     * so they are known by their video. Oldest videos are removed first.
     */
    @GuardedBy("itself")
    private static final Map<String, Boolean> shortsVideoIds = Utils
            .createSizeRestrictedMap(MAX_SHORTS, true);

    /**
     * Text of a Short in its element, followed by its video id, such as 'shorts-shelf-item-abcdefghijk'.
     * Also in the elements of many videos, such as a row of the Shorts grid of a channel.
     */
    public static final String SHORTS_ITEM_PREFIX = "shorts-shelf-item-";

    /**
     * A card of a Short is a vertical thumbnail with the title of the Short on it,
     * from 2:3 in the grids up to 9:16 in the shelves.
     */
    private static final float MIN_SHORTS_HEIGHT_OF_WIDTH = 1.3f;
    private static final float MAX_SHORTS_HEIGHT_OF_WIDTH = 1.9f;
    private static final int MIN_SHORTS_WIDTH_DP = 100;
    /**
     * Space between the title on the thumbnail of a Short and the row above it, where the badges are drawn,
     * which is the row of the duration on the thumbnails of videos.
     */
    private static final int SHORTS_TITLE_GAP_DP = 4;
    /** Height of the row above the title, the same as the duration badge, and its margin from the edges. */
    private static final int SHORTS_ROW_HEIGHT_DP = 20;
    private static final int SHORTS_ROW_MARGIN_DP = 8;

    /** Cards of Shorts, which show the badges above their title. Used only on the main thread. */
    private static final Set<ComponentHost> shortsCards = Collections.newSetFromMap(new WeakHashMap<>());

    /**
     * Thumbnail of a compact video, which is not drawn by Litho, such as a video of the watch history,
     * -> its video. Used only on the main thread.
     */
    private static final Map<View, String> compactVideoIds = new WeakHashMap<>();

    static {
        if (ENABLED) {
            // The inline player shows its buttons over the thumbnail, so what is drawn
            // on the thumbnail is hidden while the video of the card plays in the feed.
            PlayerType.getOnChange().addObserver(type -> redrawCards());
        }
    }

    /**
     * @return If what is drawn on the thumbnail of the card is hidden now, because the video plays
     *         in the feed. The inline player then shows its own buttons over the thumbnail,
     *         such as the mute and the captions buttons.
     */
    public static boolean isCardPlayingInline(ComponentHost host) {
        // The video of the card that was laid out, or null if it is not known.
        String videoId = cardVideoIds.get(host);
        if (videoId == null || PLAYLIST.equals(videoId)
                || PlayerType.getCurrent() != PlayerType.INLINE_MINIMAL) {
            return false;
        }
        return videoId.equals(VideoInformation.getVideoId())
                || videoId.equals(VideoInformation.getPlayerResponseVideoId());
    }

    /**
     * @return If the card is a playlist or a mix, which nothing is drawn on.
     */
    public static boolean isPlaylistCard(ComponentHost host) {
        return PLAYLIST.equals(cardVideoIds.get(host));
    }

    /**
     * @return If the card is a live video, which has no like count yet and shows its number of viewers.
     */
    public static boolean isLiveCard(ComponentHost host) {
        String videoId = cardVideoIds.get(host);
        return videoId != null && isLiveVideo(videoId);
    }

    /**
     * @return If the video is live, such as of a card that enters the list, which is not known by its texts yet.
     */
    public static boolean isLiveVideo(String videoId) {
        synchronized (liveVideoIds) {
            return liveVideoIds.containsKey(videoId);
        }
    }

    /**
     * @return The paint of the loading spinner of a badge, shown until what the badge shows is found.
     */
    public static Paint createSpinnerPaint(float density, int color) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(SPINNER_STROKE_DP * density);
        return paint;
    }

    /**
     * Draws the loading spinner of a badge, an arc that turns with the time.
     * The badge draws itself again after it, so the arc keeps turning while it's shown.
     */
    public static void drawSpinner(Canvas canvas, RectF oval, Paint paint) {
        final float inset = paint.getStrokeWidth() / 2;
        oval.inset(inset, inset);
        final float start = (SystemClock.uptimeMillis() % SPINNER_TURN_MS) * 360f / SPINNER_TURN_MS;
        canvas.drawArc(oval, start, SPINNER_SWEEP_DEGREES, false, paint);
    }

    private static void redrawCards() {
        Utils.runOnMainThread(() -> {
            try {
                for (ComponentHost host : cardVideoIds.keySet()) {
                    if (host != null) {
                        host.invalidate();
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "redrawCards failure", ex);
            }
        });
    }

    /**
     * Injection point.
     * Called when a video is loaded, such as the next video that plays in the feed.
     */
    public static void onVideoLoaded(String videoId) {
        if (ENABLED) {
            redrawCards();
        }
    }

    private FeedVideoCardsPatch() {
    }

    /**
     * @return If the bytes include the search bytes, such as an ASCII text of an element.
     */
    public static boolean contains(byte[] bytes, byte[] search) {
        return getElementText(bytes).contains(new String(search, StandardCharsets.ISO_8859_1));
    }

    /**
     * @return If no text of the element that includes ".eml", such as the identifier of the element,
     *         can include the search text. Every text around ".eml" is checked as a whole, from the
     *         bytes before and after it that are printable ASCII, so any such text that includes
     *         the search text is found. Faster than parsing the element to read its texts, as words
     *         such as "post" are found in most elements.
     */
    public static boolean emlTextLacks(byte[] bytes, String search) {
        String element = getElementText(bytes);
        int index = element.indexOf(".eml");
        while (index >= 0) {
            int start = index;
            while (start > 0 && element.charAt(start - 1) >= 0x20 && element.charAt(start - 1) <= 0x7E) {
                start--;
            }
            int end = index + 4;
            while (end < element.length() && element.charAt(end) >= 0x20 && element.charAt(end) <= 0x7E) {
                end++;
            }
            final int found = element.indexOf(search, start);
            if (found >= 0 && found + search.length() <= end) {
                return false;
            }
            index = element.indexOf(".eml", end);
        }
        return true;
    }

    /**
     * The bytes of the element last searched on each thread, and their string.
     */
    private static final ThreadLocal<Object[]> lastElementText = ThreadLocal.withInitial(() -> new Object[2]);

    /**
     * The element hooks search every element on the main thread, while the page loads. Every byte is
     * one character in ISO-8859-1, so the bytes include some bytes when the string of the bytes includes
     * their string, which is searched in native code. That is much faster than a loop that is not compiled
     * yet, as when the app starts. The bytes of an element are a buffer of the app that is not changed,
     * and the hooks are called one after another for it, so the string is made once for all of them.
     *
     * @return The bytes as a string with one character for each byte.
     */
    public static String getElementText(byte[] bytes) {
        // Never null, as the thread local has an initial value.
        Object[] last = Objects.requireNonNull(lastElementText.get());
        if (last[0] != bytes) {
            last[1] = new String(bytes, StandardCharsets.ISO_8859_1);
            last[0] = bytes;
        }
        return (String) last[1];
    }

    /**
     * @return The text as it's matched between an element and a card.
     */
    private static String normalizeText(CharSequence text) {
        return FORMAT_CHARACTERS_PATTERN.matcher(text).replaceAll("").trim();
    }

    private static boolean isVideoText(String text) {
        return text.length() >= MIN_TEXT_LENGTH
                && !text.contains("/vi")
                && !text.contains(".eml")
                && !text.startsWith("http")
                && !text.contains("://");
    }

    /**
     * Injection point.
     * <p>
     * Called when an element is parsed. The element is not changed.
     */
    public static byte[] onElementLoaded(byte[] bytes) {
        if (!VIDEO_CARDS || bytes == null || !contains(bytes, THUMBNAIL_PATH)) {
            return bytes;
        }
        try {
            List<ProtoNode> root = ProtoNode.parse(bytes);
            if (root == null) {
                return bytes;
            }
            List<ProtoNode> textNodes = ProtoNode.textNodes(root);

            // Video id -> number of thumbnail urls, in the order they are found.
            Map<String, Integer> thumbnailCounts = new LinkedHashMap<>();
            String identifier = null;
            for (ProtoNode node : textNodes) {
                String text = node.getText();
                if (identifier == null && text.contains(".eml")) {
                    identifier = text;
                }
                if (!text.contains("/vi")) {
                    continue;
                }
                Matcher matcher = THUMBNAIL_VIDEO_ID_PATTERN.matcher(text);
                while (matcher.find()) {
                    thumbnailCounts.merge(matcher.group(1), 1, Integer::sum);
                }
            }
            if (thumbnailCounts.isEmpty()) {
                return bytes;
            }
            // Video id -> its texts.
            Map<String, List<ProtoNode>> videoTextNodes = new LinkedHashMap<>();
            // If the element shows a single video. Elements of many videos can have the durations
            // of their videos too, such as a playlist, which has the first videos of the playlist.
            boolean singleVideo = true;
            if (thumbnailCounts.size() == 1) {
                videoTextNodes.put(thumbnailCounts.keySet().iterator().next(), textNodes);
            } else if (thumbnailCounts.size() == 2 && identifier != null
                    && identifier.startsWith("video_lockup") && !identifier.contains("shelf")) {
                // A video with an attachment that shows another thumbnail, such as the song
                // of a music video. The video shows its thumbnail in more sizes.
                String videoId = null;
                int best = 0;
                for (Map.Entry<String, Integer> entry : thumbnailCounts.entrySet()) {
                    if (entry.getValue() > best) {
                        best = entry.getValue();
                        videoId = entry.getKey();
                    }
                }
                videoTextNodes.put(videoId, textNodes);
            } else {
                singleVideo = false;
                // An element of many videos, such as a row of a grid or a shelf, which keeps the texts
                // of each video in a message with the thumbnails of only that video.
                // Message -> the video of all thumbnails inside it, or AMBIGUOUS.
                Map<ProtoNode, String> messageVideoIds = new IdentityHashMap<>();
                for (ProtoNode node : textNodes) {
                    String text = node.getText();
                    if (!text.contains("/vi")) {
                        continue;
                    }
                    Matcher matcher = THUMBNAIL_VIDEO_ID_PATTERN.matcher(text);
                    if (!matcher.find()) {
                        continue;
                    }
                    String thumbnailVideoId = matcher.group(1);
                    // A message that has this video already was set by another thumbnail of the video,
                    // with the surrounding messages. A message of another video becomes AMBIGUOUS,
                    // and so do the surrounding messages.
                    boolean mixed = false;
                    for (ProtoNode message = node.getParent(); message != null; message = message.getParent()) {
                        String previous = messageVideoIds.get(message);
                        if (previous == null) {
                            messageVideoIds.put(message, mixed ? AMBIGUOUS : thumbnailVideoId);
                        } else if (previous.equals(AMBIGUOUS)) {
                            break;
                        } else if (!previous.equals(thumbnailVideoId)) {
                            messageVideoIds.put(message, AMBIGUOUS);
                            mixed = true;
                        } else if (mixed) {
                            messageVideoIds.put(message, AMBIGUOUS);
                        } else {
                            break;
                        }
                    }
                }
                // A text belongs to the video of the smallest message around it with thumbnails.
                // Texts of the whole element, such as the title of a shelf, belong to no video.
                for (ProtoNode node : textNodes) {
                    for (ProtoNode message = node.getParent(); message != null; message = message.getParent()) {
                        String messageVideoId = messageVideoIds.get(message);
                        if (messageVideoId != null) {
                            if (!messageVideoId.equals(AMBIGUOUS)) {
                                videoTextNodes.computeIfAbsent(messageVideoId, id -> new ArrayList<>()).add(node);
                            }
                            break;
                        }
                    }
                }
            }

            for (Map.Entry<String, List<ProtoNode>> entry : videoTextNodes.entrySet()) {
                final String videoId = entry.getKey();
                final List<ProtoNode> videoNodes = entry.getValue();
                boolean live = false;
                for (ProtoNode node : videoNodes) {
                    if (LIVE_ICON_PATTERN.matcher(node.getText()).matches()) {
                        live = true;
                        break;
                    }
                }
                synchronized (liveVideoIds) {
                    if (live) {
                        liveVideoIds.put(videoId, Boolean.TRUE);
                    } else {
                        // The live stream ended.
                        liveVideoIds.remove(videoId);
                    }
                }

                boolean isShort = false;
                for (ProtoNode node : videoNodes) {
                    String text = node.getText();
                    if (text.startsWith(SHORTS_ITEM_PREFIX) && text.endsWith(videoId)) {
                        isShort = true;
                        break;
                    }
                }
                // Shorts can have a duration in their element, such as in the search results.
                boolean hasDuration = false;
                for (ProtoNode node : videoNodes) {
                    if (singleVideo && !isShort && isDurationText(node.getText().trim())) {
                        hasDuration = true;
                        break;
                    }
                }
                synchronized (textVideoIds) {
                    for (ProtoNode node : videoNodes) {
                        String text = normalizeText(node.getText());
                        if (!isVideoText(text)) {
                            continue;
                        }
                        putVideoText(textVideoIds, text, videoId);
                        if (hasDuration) {
                            putVideoText(durationTextVideoIds, text, videoId);
                        } else {
                            durationTextVideoIds.put(text, AMBIGUOUS);
                        }
                    }
                }
                if (isShort) {
                    synchronized (shortsVideoIds) {
                        shortsVideoIds.put(videoId, Boolean.TRUE);
                    }
                }
                if (!live) {
                    FeedViewCountPatch.onVideoElementLoaded(videoId, videoNodes, isShort);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
        }
        return bytes;
    }

    /**
     * Adds a text shown by the cards of a video. Also called for the titles that replace the title
     * of a video on its cards, such as original titles and DeArrow titles, which the elements
     * of the video do not include. Can be called on any thread.
     */
    public static void addVideoText(String videoId, @Nullable String text) {
        if (!VIDEO_CARDS || text == null) {
            return;
        }
        String normalized = normalizeText(text);
        if (!isVideoText(normalized)) {
            return;
        }
        synchronized (textVideoIds) {
            putVideoText(textVideoIds, normalized, videoId);
        }
    }

    /**
     * Adds the text of the video, or marks it as {@link #AMBIGUOUS} if another video shows it too.
     */
    private static void putVideoText(Map<String, String> textVideoIds, String text, String videoId) {
        String previous = textVideoIds.get(text);
        if (previous == null) {
            textVideoIds.put(text, videoId);
        } else if (!previous.equals(videoId)) {
            textVideoIds.put(text, AMBIGUOUS);
        }
    }

    public static boolean isVideoShaped(ComponentHost host) {
        return isVideoShaped(host, MIN_WIDTH_OF_SCREEN);
    }

    /**
     * @param minWidthOfScreen Smallest width of the card, relative to the width of the screen.
     *                         Cards of a grid, such as in landscape, are narrower than the screen.
     */
    public static boolean isVideoShaped(ComponentHost host, float minWidthOfScreen) {
        final int width = host.getWidth();
        final float density = host.getResources().getDisplayMetrics().density;
        final int screenWidth = host.getResources().getDisplayMetrics().widthPixels;
        return width >= MIN_WIDTH_DP * density
                && width >= screenWidth * minWidthOfScreen
                && host.getHeight() >= width * THUMBNAIL_RATIO + 40 * density
                // Not the Shorts shelf, or the Shorts player.
                // Square thumbnails are used too, such as in the Music feed.
                && host.getHeight() <= width + MAX_BELOW_THUMBNAIL_DP * density;
    }

    /** Height of the texts of a video below its thumbnail, at most. */
    private static final int MAX_BELOW_THUMBNAIL_DP = 300;

    /** Depth of the views of a card that are searched for the thumbnail. */
    private static final int MAX_THUMBNAIL_SEARCH_DEPTH = 4;

    /**
     * @return The bottom of the thumbnail of a card, relative to the root host.
     *         Vertical videos show a taller thumbnail, so the height is not always 16:9.
     *         The thumbnail is the smallest view at the top of the card that is as wide as
     *         the card and at least as tall as a 16:9 thumbnail.
     */
    public static int getThumbnailBottom(ComponentHost root) {
        final int width = root.getWidth();
        final int minHeight = Math.round(width * THUMBNAIL_RATIO);
        final int bottom = findThumbnailBottom(root, 0, 0, width, minHeight, MAX_THUMBNAIL_SEARCH_DEPTH);
        return bottom == Integer.MAX_VALUE ? minHeight : bottom;
    }

    /**
     * @return The smallest bottom of a thumbnail shaped child, or {@link Integer#MAX_VALUE}
     *         if there is none.
     */
    private static int findThumbnailBottom(ViewGroup group, int left, int top,
                                           int width, int minHeight, int depth) {
        int best = Integer.MAX_VALUE;
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }
            final int childLeft = left + child.getLeft();
            final int childTop = top + child.getTop();
            if (childLeft != 0 || childTop != 0 || child.getWidth() != width) {
                continue;
            }
            if (child.getHeight() >= minHeight) {
                best = Math.min(best, child.getHeight());
            }
            if (depth > 1 && child instanceof ViewGroup childGroup) {
                best = Math.min(best, findThumbnailBottom(childGroup, childLeft, childTop,
                        width, minHeight, depth - 1));
            }
        }
        return best;
    }

    /**
     * Duration of a video, such as 4:05 or 1:02:33. Music videos show an icon before it,
     * which can be a character of the same text.
     */
    private static final Pattern DURATION_PATTERN =
            Pattern.compile("(?:^|[^\\p{L}\\p{N}])\\d{1,2}(?:[:.]\\d{2}){1,2}$");
    private static final int MAX_DURATION_TEXT_LENGTH = 12;

    /** A short badge in capital letters, such as the live badge. */
    private static final Pattern BADGE_PATTERN = Pattern.compile("^\\p{Lu}{2,12}$");

    /**
     * @return If the text is a duration, such as 4:05, or a badge such as the live badge.
     */
    public static boolean isVideoBadgeText(String text) {
        return isDurationText(text)
                || BADGE_PATTERN.matcher(text).matches();
    }

    private static boolean isDurationText(String text) {
        return text.length() <= MAX_DURATION_TEXT_LENGTH && DURATION_PATTERN.matcher(text).find();
    }

    /**
     * Classify the actual tapped card without the optional text-to-video cache.
     * Music-video cards have a duration even when their watch command starts a Mix.
     * An intentional Mix/playlist card has no duration. Unknown layouts are left alone.
     * Must be called on the main thread while the tap's source view is still mounted.
     */
    public static boolean isIndividualVideoTap(View sender) {
        ComponentHost root = findTappedRootHost(sender);
        return root != null && isVideoShaped(root) && !hasHorizontalList(root) && !isInDialog(root)
                && hasDuration(root);
    }

    /**
     * Classify a mounted card whose command already identifies a Mix. Unlike an
     * individual music video, a Mix card has no duration. Do not impose the full
     * width feed geometry: Mix cards also appear in smaller library grids/shelves.
     */
    public static boolean isPlaylistTap(View sender) {
        ComponentHost root = findTappedRootHost(sender);
        return root != null && root.isAttachedToWindow() && !isInDialog(root) && !hasDuration(root);
    }

    /**
     * @return The root host of the card that contains the tapped view.
     */
    @Nullable
    public static ComponentHost findTappedRootHost(View sender) {
        ViewParent parent = sender instanceof ComponentHost host ? host : sender.getParent();
        while (parent instanceof View view && !(parent instanceof ComponentHost)) {
            parent = view.getParent();
        }
        return parent instanceof ComponentHost host ? findRootHost(host) : null;
    }

    private static boolean hasDuration(View view) {
        if (view instanceof ComponentHost host) {
            TextContent content = host.getTextContent();
            if (content != null) {
                for (CharSequence text : content.getTextItems()) {
                    if (text != null && isDurationText(text.toString().trim())) {
                        return true;
                    }
                }
            }
        } else if (view instanceof TextView textView) {
            CharSequence text = textView.getText();
            if (text != null && isDurationText(text.toString().trim())) {
                return true;
            }
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() == View.VISIBLE && hasDuration(child)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Largest space between the thumbnail and the left, top and right edges of the card.
     * Cards of some layouts show the thumbnail inset from their edges, such as by 12 dp.
     */
    public static final int MAX_THUMBNAIL_INSET_DP = 16;
    /** A duration badge larger than this is not the badge itself, but a host around it. */
    public static final int MAX_DURATION_WIDTH_DP = 120;
    public static final int MAX_DURATION_HEIGHT_DP = 32;

    /**
     * @param rect The host that shows a badge text, such as a duration or 'LIVE', relative to the root host.
     * @return If the text is a badge of the thumbnail: the host is as wide as the thumbnail and starts in it,
     *         such as the host of the whole thumbnail, or it's as large as a badge, on the side of the duration
     *         (the right, or the left in right to left languages). Not a badge text elsewhere on the card,
     *         such as the live badge on the avatar of a channel that is live, at the top of its page.
     */
    public static boolean isThumbnailBadge(ComponentHost root, Rect rect) {
        final float density = root.getResources().getDisplayMetrics().density;
        final int width = root.getWidth();
        if (rect.width() >= width - 2 * MAX_THUMBNAIL_INSET_DP * density
                && rect.top < width * THUMBNAIL_RATIO) {
            return true;
        }
        return rect.width() <= MAX_DURATION_WIDTH_DP * density
                && rect.height() <= MAX_DURATION_HEIGHT_DP * density
                && (FeedViewCountPatch.isRtl(root) ? rect.centerX() < width / 2 : rect.centerX() > width / 2);
    }

    /**
     * Videos show their duration, or a badge such as the live badge, on the thumbnail.
     * Mixes, playlists and Shorts do not.
     */
    private static boolean hasVideoBadge(ComponentHost root, ComponentHost host, Rect rect) {
        TextContent textContent = host.getTextContent();
        if (textContent != null) {
            for (CharSequence text : textContent.getTextItems()) {
                if (text != null && isVideoBadgeText(text.toString().trim())) {
                    rect.set(0, 0, host.getWidth(), host.getHeight());
                    if (host != root) {
                        root.offsetDescendantRectToMyCoords(host, rect);
                    }
                    if (isThumbnailBadge(root, rect)) {
                        return true;
                    }
                }
            }
        }
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            if (host.getChildAt(i) instanceof ComponentHost child && hasVideoBadge(root, child, rect)) {
                return true;
            }
        }
        return false;
    }

    private static void collectTexts(ComponentHost host, List<String> texts) {
        TextContent textContent = host.getTextContent();
        if (textContent != null) {
            for (CharSequence text : textContent.getTextItems()) {
                if (text != null) {
                    String item = normalizeText(text);
                    if (isVideoText(item) && !texts.contains(item)) {
                        texts.add(item);
                    }
                }
            }
        }
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            if (host.getChildAt(i) instanceof ComponentHost child) {
                collectTexts(child, texts);
            }
        }
    }

    /**
     * The share sheet and other bottom sheets are dialogs that can show the thumbnail and the texts
     * of the opened video, so they look like a card. Feeds are in the window of the activity.
     */
    public static boolean isInDialog(ComponentHost host) {
        Context context = host.getContext();
        while (context instanceof ContextWrapper wrapper && !(context instanceof Activity)) {
            context = wrapper.getBaseContext();
        }
        if (context instanceof Activity activity && activity.getWindow() != null) {
            View decorView = activity.getWindow().peekDecorView();
            if (decorView != null && host.getRootView() != decorView) {
                return true;
            }
        }

        // Bottom sheets that are shown in the window of the activity.
        ViewParent parent = host.getParent();
        while (parent instanceof View parentView) {
            final int id = parentView.getId();
            // Generated ids, which have no package, are not resources.
            if (id != View.NO_ID && (id >>> 24) != 0
                    && parentView.getResources().getResourceEntryName(id).contains("bottom_sheet")) {
                return true;
            }
            parent = parentView.getParent();
        }
        return false;
    }

    /**
     * @return If the view is a list that scrolls sideways, such as a shelf of videos.
     */
    public static boolean isHorizontalList(View view) {
        if (view instanceof ComponentHost) {
            return false;
        }
        String name = view.getClass().getName();
        return name.contains("RecyclerView") || name.contains("HorizontalScrollView")
                || name.contains("ViewPager")
                || view.canScrollHorizontally(1) || view.canScrollHorizontally(-1);
    }

    /**
     * Cards of some layouts show the thumbnail beside the texts, such as the search results on tablets.
     * The host of the thumbnail is then shaped like a full width card without texts below its thumbnail,
     * so what is drawn on the thumbnail of a full width card is drawn on it.
     *
     * @return The host of the thumbnail at the start side of a card shaped like that: as tall as the card,
     *         16:9 and at least {@link #MIN_SIDE_THUMBNAIL_WIDTH_DP} wide. Null if the card is not shaped like that.
     */
    @Nullable
    public static ComponentHost findSideThumbnail(ComponentHost root) {
        final float density = root.getResources().getDisplayMetrics().density;
        return findSideThumbnail(root, root, 0, 0, Math.round(MAX_THUMBNAIL_INSET_DP * density),
                FeedViewCountPatch.isRtl(root), MAX_THUMBNAIL_SEARCH_DEPTH);
    }

    @Nullable
    private static ComponentHost findSideThumbnail(ComponentHost root, ComponentHost host, int left, int top,
                                                   int inset, boolean rtl, int depth) {
        final int width = root.getWidth();
        final float density = root.getResources().getDisplayMetrics().density;
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            if (!(host.getChildAt(i) instanceof ComponentHost child) || child.getVisibility() != View.VISIBLE) {
                continue;
            }
            final int childLeft = left + child.getLeft();
            final int childTop = top + child.getTop();
            if (childTop > inset || (rtl ? childLeft + child.getWidth() < width - inset : childLeft > inset)) {
                continue;
            }
            if (child.getHeight() >= root.getHeight() - 2 * inset
                    && child.getWidth() >= MIN_SIDE_THUMBNAIL_WIDTH_DP * density && child.getWidth() <= width / 2
                    && Math.abs(child.getWidth() * THUMBNAIL_RATIO - child.getHeight())
                    <= child.getHeight() * SIDE_THUMBNAIL_RATIO_TOLERANCE) {
                return child;
            }
            if (depth > 1) {
                ComponentHost thumbnail = findSideThumbnail(root, child, childLeft, childTop, inset, rtl, depth - 1);
                if (thumbnail != null) {
                    return thumbnail;
                }
            }
        }
        return null;
    }

    /**
     * Smallest thumbnail beside the texts, such as of the rows of a list on phones,
     * which fits the compact like count at the top of the thumbnail.
     */
    private static final int MIN_SIDE_THUMBNAIL_WIDTH_DP = 120;

    /** How far the height of a thumbnail beside the texts may differ from 16:9, relative to its height. */
    private static final float SIDE_THUMBNAIL_RATIO_TOLERANCE = 0.1f;

    /**
     * A shelf shows many videos, and is shaped like a single video. The texts of its first video
     * would decide the video of the whole shelf, and stay when the shelf is scrolled.
     */
    public static boolean hasHorizontalList(ViewGroup group) {
        for (int i = 0, count = group.getChildCount(); i < count; i++) {
            View child = group.getChildAt(i);
            if (isHorizontalList(child)
                    || (child instanceof ViewGroup childGroup && hasHorizontalList(childGroup))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The video of a full width video card, or null if it is not known.
     */
    @Nullable
    public static String findVideoId(ComponentHost root) {
        // The badge of a card with the thumbnail beside the texts is on the host of the thumbnail.
        return findVideoId(root, isVideoShaped(root) ? root : findSideThumbnail(root));
    }

    /**
     * @param thumbnail The host that what is drawn on the thumbnail of the card is drawn on,
     *                  or null if the card is not shaped like a video card.
     */
    @Nullable
    private static String findVideoId(ComponentHost root, @Nullable ComponentHost thumbnail) {
        if (thumbnail == null || hasHorizontalList(root)) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        if (texts.isEmpty()) {
            return NOT_READY;
        }
        if (!hasVideoBadge(thumbnail, thumbnail, new Rect())) {
            // A mix or a playlist, which shows a badge without a duration.
            return PLAYLIST;
        }

        Map<String, Integer> scores = new HashMap<>();
        synchronized (textVideoIds) {
            for (String text : texts) {
                String videoId = textVideoIds.get(text);
                if (videoId == null || videoId.equals(AMBIGUOUS)) {
                    continue;
                }
                final int score = text.length() >= TITLE_TEXT_LENGTH ? 2 : 1;
                scores.merge(videoId, score, Integer::sum);
            }
        }
        // The texts of a mix or a playlist, which are never shared with a video.
        // A single short text can belong to an element of another video that was not seen.
        String videoId = findBestMatch(scores);
        if (videoId != null) {
            for (String text : texts) {
                if (text.length() >= TITLE_TEXT_LENGTH) {
                    putVideoText(foundTitleVideoIds, text, videoId);
                }
            }
            return videoId;
        }
        return scores.isEmpty() ? findVideoIdByFoundTitles(texts) : null;
    }

    /**
     * The texts of the element of the card were removed, so the card is found by the titles
     * of the cards that were found before. A long text of the card can be shared with another
     * video of the same channel that was found, such as the channel and the view count,
     * so at least two texts must match.
     */
    @Nullable
    private static String findVideoIdByFoundTitles(List<String> texts) {
        Map<String, Integer> scores = new HashMap<>();
        for (String text : texts) {
            if (text.length() < TITLE_TEXT_LENGTH) {
                continue;
            }
            String videoId = foundTitleVideoIds.get(text);
            if (videoId != null && !videoId.equals(AMBIGUOUS)) {
                scores.merge(videoId, 1, Integer::sum);
            }
        }
        return findBestMatch(scores);
    }

    /**
     * Litho draws only the part of a card that is shown, so a card that enters from the bottom
     * of the list shows its thumbnail before its texts, and its video is not known by its texts.
     * The accessibility description of the card is set already, and starts with the title,
     * such as "Title - 12 minutes - Channel - 4.7M views - 2 days ago - play video".
     *
     * @param withDuration If only the texts of the elements of videos with a duration are used, so the card
     *                     is known to be such a video before its duration is drawn. Never a mix or a playlist,
     *                     whose elements can have the thumbnail of a video.
     * @return The video of the card found by the start of its description, or null if not known.
     */
    @Nullable
    public static String findVideoIdByDescription(ComponentHost root, boolean withDuration) {
        // The host of the card can be inside the root host.
        View described = root.getContentDescription() != null
                ? root
                : Utils.getChildView(root, true, view -> view.getContentDescription() != null);
        if (described == null) {
            return null;
        }
        String text = described.getContentDescription().toString();
        synchronized (textVideoIds) {
            // A title can contain the delimiter too, so the longest start of the description
            // that is a text of a video is used. The element of a Short has the whole description.
            Map<String, String> videoIds = withDuration ? durationTextVideoIds : textVideoIds;
            for (int end = text.length(); end > 0;
                 end = text.lastIndexOf(DESCRIPTION_DELIMITER, end - 1)) {
                String videoId = videoIds.get(normalizeText(text.substring(0, end)));
                if (videoId != null) {
                    return videoId.equals(AMBIGUOUS) ? null : videoId;
                }
            }
        }
        return null;
    }

    /**
     * @param scores Match -> score of the texts that belong to it, where a long text scores 2.
     * @return The match with the highest score, or null if there is a tie
     *         or the best match has only a single short text.
     */
    @Nullable
    public static String findBestMatch(Map<String, Integer> scores) {
        String best = null;
        int bestScore = 0;
        boolean tie = false;
        for (Map.Entry<String, Integer> entry : scores.entrySet()) {
            final int score = entry.getValue();
            if (score > bestScore) {
                best = entry.getKey();
                bestScore = score;
                tie = false;
            } else if (score == bestScore) {
                tie = true;
            }
        }
        return tie || bestScore < 2 ? null : best;
    }

    @Nullable
    private static ComponentHost findRootHost(ComponentHost host) {
        ComponentHost root = null;
        ViewParent parent = host;
        while (parent instanceof ComponentHost componentHost) {
            root = componentHost;
            parent = componentHost.getParent();
        }
        return root;
    }

    /**
     * Root hosts with a layout change, which are checked once the layout pass is done.
     * The root host is a LithoView, which may not call the layout method of its super class,
     * so the layout of any host schedules its root host.
     */
    private static final Map<ComponentHost, Boolean> rootsToUpdate = Collections.synchronizedMap(
            new WeakHashMap<>());
    private static volatile boolean rootsUpdateScheduled;

    /** Root host -> number of checks since its last layout. Used only on the main thread. */
    private static final Map<ComponentHost, Integer> retries = new WeakHashMap<>();
    /** Cards that are checked again after a delay. Used only on the main thread. */
    private static final Map<ComponentHost, Boolean> pendingRetries = new WeakHashMap<>();
    /**
     * Cards that are checked again after a longer delay, see {@link #LATE_RETRY_DELAYS_MS}.
     * Used only on the main thread.
     */
    private static final Map<ComponentHost, Boolean> pendingLateRetries = new WeakHashMap<>();

    /**
     * Root hosts that are checked again when the list scrolls. Litho mounts only the part of a card
     * that is shown, so what is drawn on it can be found once more of it is shown.
     * Used only on the main thread.
     */
    private static final Set<ComponentHost> scrollChecks = Collections.newSetFromMap(new WeakHashMap<>());
    /** Tree observers of the windows the scroll listener was added to. Used only on the main thread. */
    private static final Map<ViewTreeObserver, Boolean> scrollObservers = new WeakHashMap<>();
    private static boolean scrollCheckScheduled;
    private static final Rect visibleRect = new Rect();

    private static final Runnable scrollCheckRunnable = () -> {
        scrollCheckScheduled = false;
        for (ComponentHost root : scrollChecks.toArray(new ComponentHost[0])) {
            if (root != null) {
                scheduleUpdate(root);
            }
        }
    };

    private static final ViewTreeObserver.OnScrollChangedListener scrollListener = () -> {
        if (!scrollCheckScheduled && !scrollChecks.isEmpty()) {
            scrollCheckScheduled = true;
            Utils.runOnMainThreadDelayed(scrollCheckRunnable, SCROLL_CHECK_DELAY_MS);
        }
    };

    private static void scheduleUpdate(ComponentHost root) {
        rootsToUpdate.put(root, Boolean.TRUE);
        if (!rootsUpdateScheduled) {
            rootsUpdateScheduled = true;
            Utils.runOnMainThread(updateRootsRunnable);
        }
    }

    /**
     * Checks the card again when the list scrolls, if the card is partly shown.
     * Litho mounts only the part of a card that is shown, so what is drawn on it can be found
     * once more of it is shown. Must be called on the main thread.
     *
     * @return If the card is partly shown, so it is checked again.
     */
    public static boolean scheduleScrollCheck(ComponentHost root) {
        final boolean partlyShown = root.isAttachedToWindow()
                && (!root.getLocalVisibleRect(visibleRect) || visibleRect.height() < root.getHeight());
        if (partlyShown) {
            scrollChecks.add(root);
            ViewTreeObserver observer = root.getViewTreeObserver();
            if (observer.isAlive() && scrollObservers.put(observer, Boolean.TRUE) == null) {
                observer.addOnScrollChangedListener(scrollListener);
            }
        }
        return partlyShown;
    }

    /**
     * @return If the card is checked again later, such as while its video is not found yet.
     *         Must be called on the main thread.
     */
    public static boolean isRetryPending(ComponentHost host) {
        // The host can be a card inside the root host, see innerCards.
        return pendingRetries.containsKey(findRootHost(host));
    }

    /**
     * Checks the card again a little later, such as when what is drawn on it was not found yet,
     * and when the list scrolls while the card is partly shown. After the first retries, the card
     * is checked a few more times with longer delays while it's shown.
     * Must be called on the main thread.
     *
     * @return If the card is checked again soon, or when the list scrolls.
     */
    public static boolean scheduleRetry(ComponentHost root) {
        final boolean partlyShown = scheduleScrollCheck(root);

        // Every patch that draws on the card can ask for a retry when the card is checked,
        // and the card is checked again once for all of them.
        if (pendingRetries.containsKey(root)) {
            return true;
        }
        Integer count = retries.get(root);
        final int attempt = count == null ? 0 : count;
        if (attempt >= MAX_RETRIES + LATE_RETRY_DELAYS_MS.length) {
            return partlyShown;
        }
        if (attempt >= MAX_RETRIES) {
            // A late retry of an earlier layout of the card can still be pending.
            if (pendingLateRetries.containsKey(root)) {
                return partlyShown;
            }
            retries.put(root, attempt + 1);
            // Not a pending retry, so what is drawn while the card is checked again soon is not kept
            // for as long.
            pendingLateRetries.put(root, Boolean.TRUE);
            root.postDelayed(() -> {
                pendingLateRetries.remove(root);
                if (root.isShown()) {
                    scheduleUpdate(root);
                }
            }, LATE_RETRY_DELAYS_MS[attempt - MAX_RETRIES]);
            return partlyShown;
        }
        retries.put(root, attempt + 1);
        pendingRetries.put(root, Boolean.TRUE);
        root.postDelayed(() -> {
            pendingRetries.remove(root);
            if (root.isAttachedToWindow()) {
                scheduleUpdate(root);
            }
        }, RETRY_DELAY_MS);
        return true;
    }

    /**
     * A host of a card search, with its depth below the root host, which is 1.
     */
    private record HostAtDepth(ComponentHost host, int depth) {
    }

    /**
     * @return If the root host does not show one known video: none was found, its element is not
     *         parsed yet, or it is a mix or a playlist.
     */
    private static boolean isUnknownVideo(@Nullable String videoId) {
        return videoId == null || NOT_READY.equals(videoId) || PLAYLIST.equals(videoId);
    }

    private static final Runnable updateRootsRunnable = () -> {
        rootsUpdateScheduled = false;
        try {
            ComponentHost[] roots = rootsToUpdate.keySet().toArray(new ComponentHost[0]);
            rootsToUpdate.clear();
            for (ComponentHost root : roots) {
                if (root == null) {
                    continue;
                }
                // Added again below if the card is still checked again.
                scrollChecks.remove(root);
                if (!root.isAttachedToWindow() || root.getParent() instanceof ComponentHost) {
                    continue;
                }
                if (!VIDEO_CARDS) {
                    // Only the post features are on, which do not use the video of the card.
                    PostImageDownloadPatch.onRootLaidOut(root);
                    PostTranslatePatch.onRootLaidOut(root);
                    continue;
                }
                String videoId = findVideoId(root);
                final boolean fullWidthCard = isVideoShaped(root);
                boolean retry = NOT_READY.equals(videoId)
                        || (videoId == null && fullWidthCard && !hasHorizontalList(root))
                        // A card that enters from the top of the list shows its texts before the
                        // duration of its thumbnail, so it looks like a mix until its top is shown.
                        || (PLAYLIST.equals(videoId) && fullWidthCard
                        && (!root.getLocalVisibleRect(visibleRect) || visibleRect.top > 0));
                // A card that enters from the bottom of the list shows the top of its thumbnail before its texts
                // and its duration, and then only some of its texts, so it's not known by them yet. The like count
                // and the view count of the thumbnail are shown at once if the description of the card is a text
                // of only a video with a duration. A card shown up to its bottom without a duration is a mix or
                // a playlist.
                String enteringVideoId = null;
                if (fullWidthCard && isUnknownVideo(videoId)
                        && root.getLocalVisibleRect(visibleRect) && visibleRect.top == 0
                        && (!PLAYLIST.equals(videoId) || visibleRect.bottom < root.getHeight())) {
                    enteringVideoId = findVideoIdByDescription(root, true);
                    // A row of a grid of Shorts is shaped like a card too, and its Shorts have their own badges.
                    // The thumbnail of the card is as wide as the card.
                    synchronized (shortsVideoIds) {
                        if (enteringVideoId != null && shortsVideoIds.containsKey(enteringVideoId)) {
                            enteringVideoId = null;
                        }
                    }
                    if (enteringVideoId != null && FeedViewCountPatch.findThumbnailFrame(root).thumbnail() == null) {
                        enteringVideoId = null;
                    }
                    retry |= enteringVideoId != null;
                }

                // The like count of a card with the thumbnail beside the texts is drawn on the host of
                // the thumbnail, and the like count of the cards of a row of a grid on each card. They are
                // shaped like full width cards. The other features use the shape of a full width card,
                // so they are given the root host as before.
                List<ComponentHost> cards = new ArrayList<>();
                List<String> cardIds = new ArrayList<>();
                if (!fullWidthCard && !hasHorizontalList(root)) {
                    ComponentHost sideThumbnail = findSideThumbnail(root);
                    if (sideThumbnail != null) {
                        cards.add(sideThumbnail);
                        cardIds.add(videoId);
                        // A thumbnail beside the texts can show its duration after the texts are shown.
                        retry |= videoId == null || PLAYLIST.equals(videoId);
                    } else {
                        // The cards of a row can be inside hosts around all of them, such as in the tabs of a channel.
                        ComponentHost row = root;
                        for (int depth = 1; depth < MAX_THUMBNAIL_SEARCH_DEPTH; depth++) {
                            ComponentHost single = null;
                            int hosts = 0;
                            for (int i = 0, count = row.getChildCount(); i < count; i++) {
                                if (row.getChildAt(i) instanceof ComponentHost child
                                        && child.getVisibility() == View.VISIBLE) {
                                    single = child;
                                    hosts++;
                                }
                            }
                            if (hosts != 1) {
                                break;
                            }
                            row = single;
                        }
                        for (int i = 0, count = row.getChildCount(); i < count; i++) {
                            // The cards of a row of many columns, such as in the tabs of a channel on large tablets,
                            // can be narrower than a full width card, so only their shape is checked.
                            if (row.getChildAt(i) instanceof ComponentHost child
                                    && child.getVisibility() == View.VISIBLE && isVideoShaped(child, 0)) {
                                cards.add(child);
                            }
                        }
                        if (cards.size() < 2) {
                            cards.clear();
                        }
                        for (ComponentHost card : cards) {
                            String cardId = findVideoId(card, card);
                            cardIds.add(cardId);
                            retry |= NOT_READY.equals(cardId) || cardId == null
                                    || (PLAYLIST.equals(cardId)
                                    && (!card.getLocalVisibleRect(visibleRect) || visibleRect.top > 0));
                        }
                    }
                }
                // The cards of Shorts have no duration on their thumbnail, so they look like a mix.
                // A grid row of Shorts is shaped like a full width card, and a shelf has a root host for each Short.
                List<FeedViewCountPatch.ThumbnailFrame> shortsFrames = new ArrayList<>();
                // A row of Shorts that enters the list shows the top of the Shorts before any text.
                if (cards.isEmpty() && isUnknownVideo(videoId)
                        && !hasHorizontalList(root)) {
                    final float density = root.getResources().getDisplayMetrics().density;
                    // Host -> its depth. A card is the smallest host shaped like the card of a Short,
                    // as the column of a grid around it can be shaped like it too, with a margin around the card.
                    // The hosts inside a card are not searched.
                    ArrayDeque<HostAtDepth> hosts = new ArrayDeque<>();
                    hosts.add(new HostAtDepth(root, 1));
                    HostAtDepth next;
                    // The queue holds no nulls, so poll() is null only once it is empty.
                    while ((next = hosts.poll()) != null) {
                        ComponentHost card = next.host();
                        final int depth = next.depth();
                        final int width = card.getWidth();
                        boolean isCard = width >= MIN_SHORTS_WIDTH_DP * density
                                && card.getHeight() >= width * MIN_SHORTS_HEIGHT_OF_WIDTH
                                && card.getHeight() <= width * MAX_SHORTS_HEIGHT_OF_WIDTH;
                        List<ComponentHost> children = new ArrayList<>();
                        for (int i = 0, count = card.getChildCount(); depth < MAX_THUMBNAIL_SEARCH_DEPTH && i < count; i++) {
                            if (card.getChildAt(i) instanceof ComponentHost child && child.getVisibility() == View.VISIBLE) {
                                children.add(child);
                                final int childWidth = child.getWidth();
                                // A host inside the card as large as the card is a part of the card.
                                if (childWidth < width && childWidth >= MIN_SHORTS_WIDTH_DP * density
                                        && child.getHeight() >= childWidth * MIN_SHORTS_HEIGHT_OF_WIDTH
                                        && child.getHeight() <= childWidth * MAX_SHORTS_HEIGHT_OF_WIDTH) {
                                    isCard = false;
                                }
                            }
                        }
                        if (!isCard) {
                            for (ComponentHost child : children) {
                                hosts.add(new HostAtDepth(child, depth + 1));
                            }
                            continue;
                        }
                        String cardId = findVideoIdByDescription(card, false);
                        final boolean isShort;
                        synchronized (shortsVideoIds) {
                            isShort = cardId != null && shortsVideoIds.containsKey(cardId);
                        }
                        if (!isShort) {
                            continue;
                        }

                        // The badges are drawn above the title on the thumbnail, in the row of the duration
                        // on the thumbnails of videos: the view count at the start, and the like count
                        // at the end, above the row. The title is the highest text in the bottom half.
                        int titleTop = Integer.MAX_VALUE;
                        Rect textRect = new Rect();
                        ArrayDeque<ComponentHost> textHosts = new ArrayDeque<>();
                        textHosts.add(card);
                        ComponentHost host;
                        while ((host = textHosts.poll()) != null) {
                            TextContent textContent = host.getTextContent();
                            if (textContent != null && !textContent.getTextItems().isEmpty()) {
                                textRect.set(0, 0, host.getWidth(), host.getHeight());
                                if (host != card) {
                                    card.offsetDescendantRectToMyCoords(host, textRect);
                                }
                                if (textRect.top >= card.getHeight() / 2) {
                                    titleTop = Math.min(titleTop, textRect.top);
                                }
                            }
                            for (int i = 0, count = host.getChildCount(); i < count; i++) {
                                if (host.getChildAt(i) instanceof ComponentHost child
                                        && child.getVisibility() == View.VISIBLE) {
                                    textHosts.add(child);
                                }
                            }
                        }
                        // The title is not drawn yet, such as while only the top of a Short that enters the list
                        // is shown. The row is placed as high as the title can be, and only the like count,
                        // which is at the top of the Short, is drawn until the title is drawn.
                        final boolean titleShown = titleTop != Integer.MAX_VALUE;
                        if (!titleShown) {
                            titleTop = card.getHeight() / 2;
                            retry = true;
                        }
                        // The row above the title, as the duration badge at the end of the row.
                        final int rowBottom = titleTop - Math.round(SHORTS_TITLE_GAP_DP * density);
                        final int rowTop = rowBottom - Math.round(SHORTS_ROW_HEIGHT_DP * density);
                        final int margin = Math.round(SHORTS_ROW_MARGIN_DP * density);
                        final int edge = FeedViewCountPatch.isRtl(card) ? margin : width - margin;
                        FeedViewCountPatch.ThumbnailFrame frame = new FeedViewCountPatch.ThumbnailFrame(
                                new Rect(edge, rowTop, edge, rowBottom), new Rect(0, 0, width, card.getHeight()),
                                !titleShown);
                        cards.add(card);
                        cardIds.add(cardId);
                        shortsFrames.add(frame);
                    }
                }
                if (retry) {
                    scheduleRetry(root);
                } else {
                    retries.remove(root);
                }
                if (NOT_READY.equals(videoId)) {
                    if (enteringVideoId != null && cards.isEmpty()) {
                        FeedEngagementCountsPatch.onCardLaidOut(root, enteringVideoId, null);
                        FeedViewCountPatch.onCardLaidOut(root, enteringVideoId, null);
                    }
                    // The like count of the Shorts whose title is not drawn yet.
                    for (int i = 0; i < shortsFrames.size(); i++) {
                        FeedViewCountPatch.ThumbnailFrame frame = shortsFrames.get(i);
                        if (frame.estimated()) {
                            cardVideoIds.put(cards.get(i), cardIds.get(i));
                            FeedEngagementCountsPatch.onCardLaidOut(cards.get(i), cardIds.get(i), frame);
                        }
                    }
                    FeedVideoActionsPatch.onCardLaidOut(root, null, false);
                    continue;
                }
                if (videoId == null) {
                    cardVideoIds.remove(root);
                } else {
                    cardVideoIds.put(root, videoId);
                }
                final String cardVideoId = PLAYLIST.equals(videoId) ? null : videoId;

                List<ComponentHost> previousCards = cards.isEmpty()
                        ? innerCards.remove(root)
                        : innerCards.put(root, cards);
                if (previousCards != null) {
                    for (ComponentHost previous : previousCards) {
                        // Litho reuses the hosts, so a previous card can be in another root host now,
                        // which draws on it.
                        if (!cards.contains(previous) && findRootHost(previous) == root) {
                            cardVideoIds.remove(previous);
                            FeedEngagementCountsPatch.onCardLaidOut(previous, null, null);
                            if (shortsCards.remove(previous)) {
                                FeedViewCountPatch.onCardLaidOut(previous, null, null);
                            }
                        }
                    }
                }
                for (int i = 0; i < cards.size(); i++) {
                    final ComponentHost card = cards.get(i);
                    final String cardId = cardIds.get(i);
                    if (NOT_READY.equals(cardId)) {
                        continue;
                    }
                    if (cardId == null) {
                        cardVideoIds.remove(card);
                    } else {
                        cardVideoIds.put(card, cardId);
                    }
                    FeedViewCountPatch.ThumbnailFrame shortsFrame = i < shortsFrames.size() ? shortsFrames.get(i) : null;
                    if (shortsFrame != null && shortsFrame.estimated()) {
                        FeedEngagementCountsPatch.onCardLaidOut(card, cardId, shortsFrame);
                        continue;
                    }
                    if (shortsFrame != null) {
                        shortsCards.add(card);
                        FeedEngagementCountsPatch.onCardLaidOut(card, cardId, shortsFrame);
                        FeedViewCountPatch.onCardLaidOut(card, cardId, shortsFrame);
                        continue;
                    }
                    if (shortsCards.remove(card)) {
                        FeedViewCountPatch.onCardLaidOut(card, null, null);
                    }
                    FeedEngagementCountsPatch.onCardLaidOut(card, PLAYLIST.equals(cardId) ? null : cardId, null);
                }
                final String fullWidthCardVideoId = cards.isEmpty() ? cardVideoId : null;
                // The root host of a Short of a shelf is the card of the Short.
                if (!cards.contains(root)) {
                    final String rootVideoId = enteringVideoId != null && cards.isEmpty()
                            ? enteringVideoId
                            : fullWidthCardVideoId;
                    FeedEngagementCountsPatch.onCardLaidOut(root, rootVideoId, null);
                    FeedViewCountPatch.onCardLaidOut(root, rootVideoId, null);
                }
                FeedVideoActionsPatch.onCardLaidOut(root, fullWidthCardVideoId, true);
                PostImageDownloadPatch.onRootLaidOut(root);
                PostTranslatePatch.onRootLaidOut(root);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateRoots failure", ex);
        }
    };

    /**
     * Injection point.
     * Called when a compact video is bound, such as a video of the watch history, which is not drawn by Litho.
     * The like count is drawn on its thumbnail, the same as on the thumbnails beside the texts of the Litho lists.
     */
    public static void onCompactVideoBound(TextView titleView, @Nullable String videoId) {
        if (!FeedEngagementCountsPatch.ENABLED) {
            return;
        }
        try {
            final int thumbnailId = ResourceUtils.getIdIdentifier("thumbnail_layout");
            View thumbnail = null;
            ViewParent parent = titleView.getParent();
            for (int depth = 0; depth < MAX_THUMBNAIL_SEARCH_DEPTH && thumbnail == null
                    && parent instanceof ViewGroup group; depth++) {
                thumbnail = group.findViewById(thumbnailId);
                parent = group.getParent();
            }
            if (!(thumbnail instanceof ViewGroup)) {
                return;
            }
            if (!compactVideoIds.containsKey(thumbnail)) {
                // The duration is set after the title, and is placed when the thumbnail is laid out.
                thumbnail.addOnLayoutChangeListener((view, left, top, right, bottom,
                                                     oldLeft, oldTop, oldRight, oldBottom) -> updateCompactVideo(view));
            }
            compactVideoIds.put(thumbnail, videoId);
            updateCompactVideo(thumbnail);
        } catch (Exception ex) {
            Logger.printException(() -> "onCompactVideoBound failure", ex);
        }
    }

    private static void updateCompactVideo(View thumbnail) {
        if (thumbnail.getWidth() == 0) {
            return;
        }
        Rect duration = null;
        View durationView = thumbnail.findViewById(ResourceUtils.getIdIdentifier("duration"));
        if (durationView != null && durationView.getVisibility() == View.VISIBLE && durationView.getWidth() > 0) {
            duration = new Rect(0, 0, durationView.getWidth(), durationView.getHeight());
            ((ViewGroup) thumbnail).offsetDescendantRectToMyCoords(durationView, duration);
        }
        FeedEngagementCountsPatch.onCardLaidOut(thumbnail, compactVideoIds.get(thumbnail),
                new FeedViewCountPatch.ThumbnailFrame(duration,
                        new Rect(0, 0, thumbnail.getWidth(), thumbnail.getHeight()), false));
    }

    /**
     * Injection point.
     * Called after a host has laid out its children.
     */
    public static void onComponentHostLayout(ComponentHost host) {
        if (!ENABLED) {
            return;
        }
        try {
            ComponentHost root = findRootHost(host);
            if (root == null) {
                return;
            }
            requestUpdate(root);
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostLayout failure", ex);
        }
    }

    /**
     * Checks the root host once the layout pass is done, such as after it was attached again.
     * Must be called on the main thread.
     */
    public static void requestUpdate(ComponentHost host) {
        // The host can be a card inside the root host, see innerCards.
        ComponentHost root = findRootHost(host);
        // A new layout can show another video, which is checked again as many times.
        retries.remove(root);
        scheduleUpdate(root);
    }
}
