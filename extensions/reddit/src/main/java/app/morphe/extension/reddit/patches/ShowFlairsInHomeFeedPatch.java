/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3260
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.net.URL;
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

    /** Stores flair metadata exposed by Reddit's full Link model. */
    public static void rememberFlair(String id, String kindWithId, String community, String flair,
                                     String backgroundColor, String textColor) {
        if (flair == null || flair.isEmpty()) return;
        String[] data = new String[] {
                flair,
                community == null ? "" : community,
                textColor == null || textColor.isEmpty() ? "LIGHT" : textColor,
                backgroundColor == null || backgroundColor.isEmpty() ? "#3A3D40" : backgroundColor
        };
        remember(id, data);
        remember(kindWithId, data);
    }

    private static void remember(String id, String[] data) {
        if (id == null || id.isEmpty()) return;
        String fullId = id.startsWith("t3_") ? id : "t3_" + id;
        FLAIRS.put(fullId, data);
        FLAIRS.put(id.startsWith("t3_") ? id.substring(3) : id, data);
    }

    /** Injection point for Reddit's common feed-element processor. */
    public static List<?> showHomeFlairs(List<?> elements) {
        if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get() || elements == null) return elements;
        try {
            for (Object element : elements) {
                if (!(element instanceof CachedPost post)) {
                    continue;
                }
                String[] data = getFlairData(post.patch_getLinkId());
                if (data != null) post.patch_addHomeFlair(data[0], data[1], data[2], data[3]);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "showHomeFlairs failure", ex);
        }
        return elements;
    }

    /** Injection point for a single home-feed section before it enters the common processor. */
    public static Object showHomeFlair(Object element) {
        try {
            if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get() || !(element instanceof CachedPost post)) {
                return element;
            }
            String[] data = getFlairData(post.patch_getLinkId());
            if (data != null) post.patch_addHomeFlair(data[0], data[1], data[2], data[3]);
        } catch (Exception ex) {
            Logger.printException(() -> "showHomeFlair failure", ex);
        }
        return element;
    }

    /**
     * Resolves the metadata needed by Reddit's native flair models.
     *
     * @return flair, community, text color and background color; or {@code null}.
     */
    @Nullable
    private static String[] getFlairData(String postId) {
        if (!Settings.SHOW_FLAIRS_IN_HOME_FEED.get() || postId == null || postId.isEmpty()) return null;

        String fullId = postId.startsWith("t3_") ? postId : "t3_" + postId;
        String[] cached = FLAIRS.get(fullId);
        if (cached != null) return cached == MISSING ? null : cached;

        try {
            return CompletableFuture.supplyAsync(() -> fetchFlairData(fullId))
                    .get(4, TimeUnit.SECONDS);
        } catch (Exception ex) {
            Logger.printInfo(() -> "Flair data fetch timed out", ex);
            return null;
        }
    }

    @Nullable
    private static String[] fetchFlairData(String fullId) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(
                    "https://www.reddit.com/by_id/" + fullId + ".json?raw_json=1"
            ).openConnection();
            connection.setConnectTimeout(4000);
            connection.setReadTimeout(4000);
            connection.setRequestProperty("User-Agent", "android:app.morphe.patches:" + Utils.getPatchesReleaseVersion());
            connection.setRequestProperty("Accept", "application/json");
            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                Logger.printDebug(() -> "Could not fetch flair, response code: " + responseCode);
                FLAIRS.put(fullId, MISSING);
                return null;
            }

            JSONObject json = Requester.parseJSONObject(connection);
            JSONArray children = json.getJSONObject("data").getJSONArray("children");
            if (children.length() == 0) return null;

            JSONObject data = children.getJSONObject(0).getJSONObject("data");
            String flair = data.optString("link_flair_text", "");
            if (flair.isEmpty()) {
                FLAIRS.put(fullId, MISSING);
                return null;
            }

            String textColor = data.optString("link_flair_text_color", "LIGHT");
            String backgroundColor = data.optString("link_flair_background_color", "#3A3D40");
            if (backgroundColor.isEmpty()) backgroundColor = "#3A3D40";
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
