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

import java.net.HttpURLConnection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

@SuppressWarnings("unused")
public final class ShowFlairsInHomeFeedPatch {
    public interface CachedPost {
        String patch_getLinkId();
        void patch_addHomeFlair(String flair, String community, String textColor, String backgroundColor);
    }

    private static final String POST_ID_PREFIX = "t3_";
    private static final String DEFAULT_TEXT_COLOR = "LIGHT";
    private static final String DEFAULT_BACKGROUND_COLOR = "#3A3D40";
    private static final int FETCH_TIMEOUT_MILLISECONDS = 4000;

    private static final ConcurrentMap<String, String[]> FLAIRS = new ConcurrentHashMap<>();
    private static final String[] MISSING = new String[0];

    private ShowFlairsInHomeFeedPatch() {
    }

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point. Stores flair metadata exposed by Reddit's full Link model.
     */
    public static void rememberFlair(Link link) {
        if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get()) return;
        try {
            String flair = link.getLinkFlairText();
            if (flair == null || flair.isEmpty()) return;

            String community = link.getSubreddit();
            String textColor = link.getLinkFlairTextColor();
            String backgroundColor = link.getLinkFlairBackgroundColor();
            String[] data = new String[] {
                    flair,
                    community == null ? "" : community,
                    textColor == null || textColor.isEmpty() ? DEFAULT_TEXT_COLOR : textColor,
                    backgroundColor == null || backgroundColor.isEmpty() ? DEFAULT_BACKGROUND_COLOR : backgroundColor
            };
            remember(link.getId(), data);
            remember(link.getKindWithId(), data);
        } catch (Exception ex) {
            Logger.printException(() -> "rememberFlair failure", ex);
        }
    }

    private static void remember(@Nullable String id, String[] data) {
        if (id == null || id.isEmpty()) return;
        FLAIRS.put(getFullId(id), data);
    }

    private static String getFullId(String postId) {
        return postId.startsWith(POST_ID_PREFIX) ? postId : POST_ID_PREFIX + postId;
    }

    /**
     * Injection point for Reddit's common feed-element processor.
     */
    public static List<?> showHomeFlairs(List<?> elements) {
        if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get() || elements == null) return elements;
        try {
            for (Object element : elements) {
                if (element instanceof CachedPost post) {
                    addFlair(post);
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
                addFlair(post);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "showHomeFlair failure", ex);
        }
        return element;
    }

    private static void addFlair(CachedPost post) {
        String[] data = getFlairData(post.patch_getLinkId());
        if (data != null) {
            post.patch_addHomeFlair(data[0], data[1], data[2], data[3]);
        }
    }

    /**
     * Resolves the metadata needed by Reddit's native flair models.
     *
     * @return flair, community, text color and background color; or {@code null}.
     */
    @Nullable
    private static String[] getFlairData(@Nullable String postId) {
        if (postId == null || postId.isEmpty()) return null;

        String fullId = getFullId(postId);
        String[] cached = FLAIRS.get(fullId);
        if (cached != null) return cached == MISSING ? null : cached;

        try {
            return CompletableFuture.supplyAsync(() -> fetchFlairData(fullId))
                    .get(FETCH_TIMEOUT_MILLISECONDS, TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not get flair data", ex);
            return null;
        }
    }

    @Nullable
    private static String[] fetchFlairData(String fullId) {
        try {
            HttpURLConnection connection = Requester.openConnection(
                    "https://www.reddit.com/by_id/" + fullId + ".json?raw_json=1");
            connection.setConnectTimeout(FETCH_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(FETCH_TIMEOUT_MILLISECONDS);
            connection.setRequestProperty("User-Agent", "android:app.morphe.patches:" + Utils.getPatchesReleaseVersion());
            connection.setRequestProperty("Accept", "application/json");

            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                connection.disconnect();
                Logger.printDebug(() -> "Could not fetch flair, response code: " + responseCode);
                FLAIRS.put(fullId, MISSING);
                return null;
            }

            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);
            JSONArray children = json.getJSONObject("data").getJSONArray("children");
            if (children.length() == 0) return null;

            JSONObject data = children.getJSONObject(0).getJSONObject("data");
            String flair = data.optString("link_flair_text", "");
            if (flair.isEmpty()) {
                FLAIRS.put(fullId, MISSING);
                return null;
            }

            String textColor = data.optString("link_flair_text_color", DEFAULT_TEXT_COLOR);
            String backgroundColor = data.optString("link_flair_background_color", DEFAULT_BACKGROUND_COLOR);
            if (backgroundColor.isEmpty()) backgroundColor = DEFAULT_BACKGROUND_COLOR;
            String[] result = new String[] {
                    flair,
                    data.optString("subreddit", ""),
                    textColor,
                    backgroundColor
            };
            FLAIRS.put(fullId, result);
            return result;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not fetch flair", ex);
            return null;
        }
    }
}
