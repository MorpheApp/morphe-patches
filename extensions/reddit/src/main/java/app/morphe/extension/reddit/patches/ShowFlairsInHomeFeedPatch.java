/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3260
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import com.reddit.domain.model.Link;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * The home feed data has no flairs. Flairs come from two places:
 * <ul>
 *   <li>After a feed page is shown, Reddit loads the full posts of most feed sections in the
 *   background, and creates a {@link Link} with the flair for each post.</li>
 *   <li>Reddit does not load the full posts of post preview elements, so their flairs
 *   are requested in the background.</li>
 * </ul>
 * Feed posts shown before the flair data is available are kept, and the flair is added
 * when the data arrives. Nothing waits for the data, so loading the feed is not delayed.
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
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 5000;
    private static final int MAX_IDS_PER_REQUEST = 100;
    /**
     * After a failed request, such as rate limiting, the posts are not requested again for this long.
     */
    private static final long RETRY_DELAY_MILLISECONDS = 30_000;
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
    /**
     * Post ids with a request in progress.
     */
    private static final Set<String> REQUESTED = ConcurrentHashMap.newKeySet();
    /**
     * Time after which a post that failed to load can be requested again, by post id.
     */
    private static final ConcurrentMap<String, Long> RETRY_AFTER = new ConcurrentHashMap<>();

    private ShowFlairsInHomeFeedPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
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
            if (flair == null || flair.isEmpty()) {
                if (WAITING_POSTS.containsKey(fullId)) {
                    Logger.printDebug(() -> "Link created without flair: " + fullId);
                }
                return;
            }

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
     * Reddit does not load the full posts of these elements, so their flairs are requested.
     */
    public static List<?> showHomeFlairs(List<?> elements) {
        if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get() || elements == null) return elements;
        try {
            List<String> idsToRequest = new ArrayList<>();
            for (Object element : elements) {
                if (element instanceof CachedPost post) {
                    String fullId = showFlair(post, "feed processor");
                    if (fullId != null) {
                        idsToRequest.add(fullId);
                    }
                }
            }

            requestFlairs(idsToRequest);
        } catch (Exception ex) {
            Logger.printException(() -> "showHomeFlairs failure", ex);
        }
        return elements;
    }

    /**
     * Injection point for a single home-feed section before it enters the common processor.
     * Reddit loads the full posts of these sections after the feed page is shown.
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
     *
     * @return The post id if the post is waiting for flair data.
     */
    @Nullable
    private static String showFlair(CachedPost post, String source) {
        String postId = post.patch_getLinkId();
        if (postId == null || postId.isEmpty()) return null;
        final String fullId = getFullId(postId);

        String[] data = FLAIRS.get(fullId);
        if (data != null) {
            addFlair(post, fullId, data, source);
            return null;
        }

        if (WAITING_POSTS.size() > WAITING_POSTS_CLEANUP_SIZE) {
            removeCollectedPosts();
        }
        WAITING_POSTS.computeIfAbsent(fullId, id -> new CopyOnWriteArrayList<>())
                .add(new WeakReference<>(post));
        Logger.printDebug(() -> source + ": " + fullId + " waiting for flair data");
        return fullId;
    }

    private static void removeCollectedPosts() {
        WAITING_POSTS.values().removeIf(references -> {
            references.removeIf(reference -> reference.get() == null);
            return references.isEmpty();
        });
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

    /**
     * Requests the flairs of posts in the background.
     */
    private static void requestFlairs(List<String> fullIds) {
        final long now = System.currentTimeMillis();
        List<String> idsToRequest = new ArrayList<>();
        for (String fullId : fullIds) {
            Long retryAfter = RETRY_AFTER.get(fullId);
            if (retryAfter != null && now < retryAfter) continue;

            if (REQUESTED.add(fullId)) {
                idsToRequest.add(fullId);
            }
        }
        if (idsToRequest.isEmpty()) return;

        CompletableFuture.runAsync(() -> {
            for (int i = 0, size = idsToRequest.size(); i < size; i += MAX_IDS_PER_REQUEST) {
                List<String> ids = idsToRequest.subList(i, Math.min(i + MAX_IDS_PER_REQUEST, size));
                try {
                    requestFlairData(ids);
                } finally {
                    REQUESTED.removeAll(ids);
                }
            }
        });
    }

    private static void requestFlairData(List<String> fullIds) {
        final long start = System.currentTimeMillis();
        try {
            HttpURLConnection connection = Requester.openConnection("https://www.reddit.com/by_id/"
                    + String.join(",", fullIds) + ".json?raw_json=1&limit=" + fullIds.size());
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setRequestProperty("User-Agent", "android:app.morphe.patches:" + Utils.getPatchesReleaseVersion());
            connection.setRequestProperty("Accept", "application/json");

            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                connection.disconnect();
                Logger.printDebug(() -> "Could not request flairs of " + fullIds.size()
                        + " posts, response code: " + responseCode);
                retryLater(fullIds);
                return;
            }

            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);
            JSONArray children = json.getJSONObject("data").getJSONArray("children");
            final int returned = children.length();
            Logger.printDebug(() -> "Requested flairs of " + fullIds.size() + " posts in "
                    + (System.currentTimeMillis() - start) + "ms, " + returned + " returned");

            for (int i = 0; i < returned; i++) {
                JSONObject data = children.getJSONObject(i).getJSONObject("data");
                String fullId = data.optString("name", "");
                String flair = data.optString("link_flair_text", "");
                if (fullId.isEmpty() || flair.isEmpty()) continue;

                String textColor = data.optString("link_flair_text_color", DEFAULT_TEXT_COLOR);
                String backgroundColor = data.optString("link_flair_background_color", DEFAULT_BACKGROUND_COLOR);
                if (backgroundColor.isEmpty()) backgroundColor = DEFAULT_BACKGROUND_COLOR;
                onFlairData(fullId, new String[] {
                        flair,
                        data.optString("subreddit", ""),
                        textColor,
                        backgroundColor
                }, "flair request");
            }
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not request flairs", ex);
            retryLater(fullIds);
        }
    }

    private static void retryLater(List<String> fullIds) {
        final long retryAfter = System.currentTimeMillis() + RETRY_DELAY_MILLISECONDS;
        for (String fullId : fullIds) {
            RETRY_AFTER.put(fullId, retryAfter);
        }
    }

    private static String getFullId(String postId) {
        return postId.startsWith(POST_ID_PREFIX) ? postId : POST_ID_PREFIX + postId;
    }
}
