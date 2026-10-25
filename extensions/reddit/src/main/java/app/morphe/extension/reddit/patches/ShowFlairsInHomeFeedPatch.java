/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3260
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import com.reddit.domain.model.Link;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * The home feed data has no flairs. After a feed page is shown, Reddit loads the full posts
 * of the page in the background, and creates a {@link Link} with the flair for each post.
 * Feed posts shown before the flair data is available are kept, and the flair is added
 * when the Link is created. Nothing waits for the data, so loading the feed is not delayed.
 */
@SuppressWarnings("unused")
public final class ShowFlairsInHomeFeedPatch {
    public interface CachedPost {
        String patch_getLinkId();

        /**
         * @return If the flair was added. False if the post already has flairs.
         */
        boolean patch_addHomeFlair(String flair, String community, String textColor, String backgroundColor);
    }

    private static final String POST_ID_PREFIX = "t3_";
    private static final String DEFAULT_TEXT_COLOR = "LIGHT";
    private static final String DEFAULT_BACKGROUND_COLOR = "#3A3D40";
    /**
     * Number of waiting post ids, after which posts that no longer exist are removed.
     */
    private static final int WAITING_POSTS_CLEANUP_SIZE = 500;

    /**
     * Flair, community, text color and background color, by post id.
     */
    private static final ConcurrentMap<String, String[]> FLAIRS = new ConcurrentHashMap<>();
    /**
     * Feed posts shown before the flair data was available, by post id.
     */
    private static final ConcurrentMap<String, List<WeakReference<CachedPost>>> WAITING_POSTS
            = new ConcurrentHashMap<>();

    private ShowFlairsInHomeFeedPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     * <p>
     * After a feed page is shown, Reddit loads the full posts of the page. A feature flag
     * removes this secondary fetch for post previews (text posts), and then the post previews
     * never get flair data. Keep the secondary fetch so they have flairs.
     */
    public static boolean isPostPreviewSecondaryFetchRemoved(boolean original) {
        return original && !Settings.SHOW_FLAIRS_IN_HOME_FEED.get();
    }

    /**
     * Injection point. Called when Reddit creates a post model.
     * The feed itself also creates post models without flair data,
     * so a post without flair does not mean the post has no flair.
     */
    public static void rememberFlair(Link link) {
        if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get()) return;
        try {
            String postId = link.getKindWithId();
            if (postId == null || postId.isEmpty()) return;
            final String fullId = getFullId(postId);

            String flair = link.getLinkFlairText();
            if (flair == null || flair.isEmpty()) return;

            String community = link.getSubreddit();
            String textColor = link.getLinkFlairTextColor();
            String backgroundColor = link.getLinkFlairBackgroundColor();
            onFlairData(fullId, new String[] {
                    flair,
                    community == null ? "" : community,
                    textColor == null || textColor.isEmpty() ? DEFAULT_TEXT_COLOR : textColor,
                    backgroundColor == null || backgroundColor.isEmpty() ? DEFAULT_BACKGROUND_COLOR : backgroundColor
            }, "link created");
        } catch (Exception ex) {
            Logger.printException(() -> "rememberFlair failure", ex);
        }
    }

    /**
     * Injection point for Reddit's common feed-element processor.
     */
    public static List<?> showHomeFlairs(List<?> elements) {
        if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get() || elements == null) return elements;
        try {
            for (Object element : elements) {
                if (element instanceof CachedPost post) {
                    showFlair(post, "feed processor");
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "showHomeFlairs failure", ex);
        }
        return elements;
    }

    /**
     * Injection point for a single home-feed section before it enters the common processor.
     */
    public static Object showHomeFlair(Object element) {
        if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get()) return element;
        try {
            if (element instanceof CachedPost post) {
                showFlair(post, "section mapper");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "showHomeFlair failure", ex);
        }
        return element;
    }

    /**
     * Adds the flair if the data is available, otherwise keeps the post until the data arrives.
     */
    private static void showFlair(CachedPost post, String source) {
        String postId = post.patch_getLinkId();
        if (postId == null || postId.isEmpty()) return;
        final String fullId = getFullId(postId);

        String[] data = FLAIRS.get(fullId);
        if (data != null) {
            addFlair(post, fullId, data, source);
            return;
        }

        if (WAITING_POSTS.size() > WAITING_POSTS_CLEANUP_SIZE) {
            WAITING_POSTS.values().removeIf(references -> {
                references.removeIf(reference -> reference.get() == null);
                return references.isEmpty();
            });
        }
        WAITING_POSTS.computeIfAbsent(fullId, id -> new CopyOnWriteArrayList<>())
                .add(new WeakReference<>(post));
    }

    /**
     * Caches the flair data, and adds the flair to the posts waiting for it.
     */
    private static void onFlairData(String fullId, String[] data, String source) {
        FLAIRS.put(fullId, data);

        List<WeakReference<CachedPost>> waitingPosts = WAITING_POSTS.remove(fullId);
        if (waitingPosts == null) return;

        for (WeakReference<CachedPost> reference : waitingPosts) {
            CachedPost post = reference.get();
            if (post != null) {
                addFlair(post, fullId, data, source);
            }
        }
    }

    private static void addFlair(CachedPost post, String fullId, String[] data, String source) {
        final boolean added = post.patch_addHomeFlair(data[0], data[1], data[2], data[3]);
        Logger.printDebug(() -> source + ": " + fullId + (added
                ? " flair added: " + data[0]
                : " already has flairs, not added: " + data[0]));
    }

    private static String getFullId(String postId) {
        return postId.startsWith(POST_ID_PREFIX) ? postId : POST_ID_PREFIX + postId;
    }
}
