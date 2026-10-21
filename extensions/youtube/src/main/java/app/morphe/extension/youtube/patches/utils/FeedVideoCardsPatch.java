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
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import com.facebook.litho.ComponentHost;
import com.facebook.litho.TextContent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.FeedEngagementCountsPatch;
import app.morphe.extension.youtube.patches.FeedViewCountPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import kotlin.Unit;

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
            || Settings.FEED_WATCH_LATER_BUTTON.get();

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
     * Result of {@link #findVideoId(ComponentHost)} for a video card whose texts are not drawn yet,
     * such as while the app returns from the background. The card keeps its last known video
     * until its texts are drawn.
     */
    private static final String NOT_READY = "not_ready";

    /** Value of a text shown by more than one video. */
    private static final String AMBIGUOUS = "";

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

    /** Text -> video id, or {@link #AMBIGUOUS}. Oldest texts are removed first. */
    @GuardedBy("itself")
    private static final Map<String, String> textVideoIds = Utils
            .createSizeRestrictedMap(MAX_TEXTS, true);

    /** Root host -> video of the card, of the cards laid out last. Used only on the main thread. */
    private static final Map<ComponentHost, String> cardVideoIds = Collections.synchronizedMap(
            new WeakHashMap<>());

    static {
        if (ENABLED) {
            // The inline player shows its buttons over the thumbnail, so what is drawn
            // on the thumbnail is hidden while the video of the card plays in the feed.
            PlayerType.getOnChange().addObserver(type -> {
                redrawCards();
                return Unit.INSTANCE;
            });
        }
    }

    /**
     * @return The video of a card that was laid out, or null if it is not known.
     */
    @Nullable
    public static String getCardVideoId(ComponentHost host) {
        String videoId = cardVideoIds.get(host);
        return PLAYLIST.equals(videoId) ? null : videoId;
    }

    /**
     * @return If the video plays in the feed, where the inline player shows its own buttons
     *         over the thumbnail, such as the mute and the captions buttons.
     */
    public static boolean isPlayingInline(@Nullable String videoId) {
        if (videoId == null || PlayerType.getCurrent() != PlayerType.INLINE_MINIMAL) {
            return false;
        }
        return videoId.equals(VideoInformation.getVideoId())
                || videoId.equals(VideoInformation.getPlayerResponseVideoId());
    }

    /**
     * @return If what is drawn on the thumbnail of the card is hidden now.
     */
    public static boolean isCardPlayingInline(ComponentHost host) {
        return isPlayingInline(getCardVideoId(host));
    }

    /**
     * @return If the card is a playlist or a mix, which nothing is drawn on.
     */
    public static boolean isPlaylistCard(ComponentHost host) {
        return PLAYLIST.equals(cardVideoIds.get(host));
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

    private static boolean contains(byte[] bytes, byte[] search) {
        final int last = bytes.length - search.length;
        outer:
        for (int i = 0; i <= last; i++) {
            for (int j = 0; j < search.length; j++) {
                if (bytes[i + j] != search[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
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
        if (!ENABLED || bytes == null || !contains(bytes, THUMBNAIL_PATH)) {
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
            String videoId = null;
            if (thumbnailCounts.size() == 1) {
                videoId = thumbnailCounts.keySet().iterator().next();
            } else if (thumbnailCounts.size() == 2 && identifier != null
                    && identifier.startsWith("video_lockup") && !identifier.contains("shelf")) {
                // A video with an attachment that shows another thumbnail, such as the song
                // of a music video. The video shows its thumbnail in more sizes.
                int best = 0;
                for (Map.Entry<String, Integer> entry : thumbnailCounts.entrySet()) {
                    if (entry.getValue() > best) {
                        best = entry.getValue();
                        videoId = entry.getKey();
                    }
                }
            }
            if (videoId == null) {
                // A shelf or another element of many videos.
                return bytes;
            }

            synchronized (textVideoIds) {
                for (ProtoNode node : textNodes) {
                    String text = node.getText().trim();
                    if (!isVideoText(text)) {
                        continue;
                    }
                    String previous = textVideoIds.get(text);
                    if (previous == null) {
                        textVideoIds.put(text, videoId);
                    } else if (!previous.equals(videoId)) {
                        textVideoIds.put(text, AMBIGUOUS);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onElementLoaded failure", ex);
        }
        return bytes;
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
        return (text.length() <= MAX_DURATION_TEXT_LENGTH && DURATION_PATTERN.matcher(text).find())
                || BADGE_PATTERN.matcher(text).matches();
    }

    /**
     * Videos show their duration, or a badge such as the live badge, on the thumbnail.
     * Mixes, playlists and Shorts do not.
     */
    private static boolean hasVideoBadge(ComponentHost host) {
        TextContent textContent = host.getTextContent();
        if (textContent != null) {
            for (CharSequence text : textContent.getTextItems()) {
                if (text != null) {
                    if (isVideoBadgeText(text.toString().trim())) {
                        return true;
                    }
                }
            }
        }
        for (int i = 0, count = host.getChildCount(); i < count; i++) {
            if (host.getChildAt(i) instanceof ComponentHost child && hasVideoBadge(child)) {
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
                    String item = text.toString().trim();
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

    @Nullable
    private static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper wrapper) {
            if (context instanceof Activity activity) {
                return activity;
            }
            context = wrapper.getBaseContext();
        }
        return null;
    }

    /**
     * The share sheet and other bottom sheets are dialogs that can show the thumbnail and the texts
     * of the opened video, so they look like a card. Feeds are in the window of the activity.
     */
    public static boolean isInDialog(ComponentHost host) {
        Activity activity = findActivity(host.getContext());
        if (activity != null && activity.getWindow() != null) {
            View decorView = activity.getWindow().peekDecorView();
            if (decorView != null && host.getRootView() != decorView) {
                return true;
            }
        }
        return isInBottomSheet(host);
    }

    /**
     * Bottom sheets that are shown in the window of the activity.
     */
    private static boolean isInBottomSheet(View view) {
        ViewParent parent = view.getParent();
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
        if (!isVideoShaped(root) || hasHorizontalList(root)) {
            return null;
        }
        List<String> texts = new ArrayList<>();
        collectTexts(root, texts);
        if (texts.isEmpty()) {
            return NOT_READY;
        }
        if (!hasVideoBadge(root)) {
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
        // The texts of a mix or a playlist, which are never shared with a video.
        // A single short text can belong to an element of another video that was not seen.
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

    private static void scheduleUpdate(ComponentHost root) {
        rootsToUpdate.put(root, Boolean.TRUE);
        if (!rootsUpdateScheduled) {
            rootsUpdateScheduled = true;
            Utils.runOnMainThread(updateRootsRunnable);
        }
    }

    /**
     * Checks the card again a little later, such as when what is drawn on it was not found yet.
     */
    public static void scheduleRetry(ComponentHost root) {
        Integer count = retries.get(root);
        final int attempt = count == null ? 0 : count;
        if (attempt >= MAX_RETRIES) {
            return;
        }
        retries.put(root, attempt + 1);
        root.postDelayed(() -> {
            if (root.isAttachedToWindow()) {
                scheduleUpdate(root);
            }
        }, RETRY_DELAY_MS);
    }

    private static final Runnable updateRootsRunnable = () -> {
        rootsUpdateScheduled = false;
        try {
            ComponentHost[] roots = rootsToUpdate.keySet().toArray(new ComponentHost[0]);
            rootsToUpdate.clear();
            for (ComponentHost root : roots) {
                if (root == null || !root.isAttachedToWindow()
                        || root.getParent() instanceof ComponentHost) {
                    continue;
                }
                String videoId = findVideoId(root);
                if (NOT_READY.equals(videoId)
                        || (videoId == null && isVideoShaped(root) && !hasHorizontalList(root))) {
                    scheduleRetry(root);
                } else {
                    retries.remove(root);
                }
                if (NOT_READY.equals(videoId)) {
                    continue;
                }
                if (videoId == null) {
                    cardVideoIds.remove(root);
                } else {
                    cardVideoIds.put(root, videoId);
                }
                final String cardVideoId = PLAYLIST.equals(videoId) ? null : videoId;
                FeedEngagementCountsPatch.onCardLaidOut(root, cardVideoId);
                FeedViewCountPatch.onCardLaidOut(root, cardVideoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateRoots failure", ex);
        }
    };

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
    public static void requestUpdate(ComponentHost root) {
        // A new layout can show another video, which is checked again as many times.
        retries.remove(root);
        scheduleUpdate(root);
    }
}
