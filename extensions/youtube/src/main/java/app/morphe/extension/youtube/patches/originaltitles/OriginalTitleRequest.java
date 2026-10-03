/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Fetches the titles that replace the titles shown by YouTube.
 * <p>
 * Original titles are fetched from the public oEmbed endpoint, which always returns the title
 * as set by the uploader. DeArrow titles are fetched with {@link DeArrowTitleRequest},
 * and if DeArrow has no title then the original title or no title is used,
 * depending on the {@link RestoreOriginalTitlesPatch.TitleType}.
 */
final class OriginalTitleRequest {

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 5000;

    /**
     * Time before a title that failed to fetch because of network errors is fetched again.
     * The translated title is shown meanwhile, so a failing network does not keep loading.
     */
    private static final long FAILED_FETCH_RETRY_MILLISECONDS = 30_000;

    /**
     * Video id -> title. A null title means the video has no available title,
     * such as a private video, or the title failed to fetch because of network errors.
     */
    private static final Map<String, CompletableFuture<String>> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Original title and name of the channel of a video, as returned by the oEmbed endpoint.
     */
    record OriginalVideo(String title, String channelName) {
    }

    /**
     * Video id -> original title and channel name, of the original titles that were fetched.
     * The title that replaces the title of the video can be a DeArrow title instead.
     */
    private static final Map<String, OriginalVideo> originalVideos =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Video ids whose title can be the name of the channel, such as titles found with an accessibility
     * label. The name of the channel is fetched with the DeArrow title, as the original title is not fetched
     * if the video has a DeArrow title.
     */
    private static final Set<String> channelNameRequired =
            Collections.newSetFromMap(Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000)));

    /**
     * Video id -> time when the title that failed to fetch can be fetched again.
     */
    private static final Map<String, Long> retryTimes =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    static CompletableFuture<String> fetch(String videoId) {
        synchronized (cache) {
            CompletableFuture<String> future = cache.get(videoId);
            Long retryTime = retryTimes.get(videoId);
            if (future == null || (retryTime != null && System.currentTimeMillis() >= retryTime)) {
                retryTimes.remove(videoId);
                future = CompletableFuture.supplyAsync(() -> fetchTitle(videoId), Utils::runOnBackgroundThread);
                cache.put(videoId, future);
            }
            return future;
        }
    }

    /**
     * Starts fetching the title if needed, and does not wait for it.
     *
     * @return The original title, or null if not yet available.
     */
    @Nullable
    static String getIfAvailable(String videoId) {
        return fetch(videoId).getNow(null);
    }

    /**
     * Does not start fetching the title.
     *
     * @return The original title and the name of the channel of the video,
     *         or null if the original title was not fetched.
     */
    @Nullable
    static OriginalVideo getOriginalIfFetched(String videoId) {
        return originalVideos.get(videoId);
    }

    /**
     * Fetches the name of the channel with the title, if the title is not yet fetched.
     * Must be called before the title is fetched.
     */
    static void requireChannelName(String videoId) {
        channelNameRequired.add(videoId);
    }

    /**
     * @return If the title is not yet fetched. Titles that failed to fetch
     *         are not pending until they are fetched again.
     */
    static boolean isPending(String videoId) {
        CompletableFuture<String> future = cache.get(videoId);
        return future == null || !future.isDone();
    }

    @Nullable
    private static String fetchTitle(String videoId) {
        RestoreOriginalTitlesPatch.TitleType type = RestoreOriginalTitlesPatch.TITLE_TYPE;
        if (type.usesDeArrow) {
            try {
                String title = DeArrowTitleRequest.fetchTitle(videoId);
                if (title != null) {
                    if (channelNameRequired.contains(videoId)) {
                        fetchOriginalTitle(videoId);
                    }
                    return title;
                }
            } catch (IOException ex) {
                // The DeArrow title is fetched again later, and the original title is used meanwhile.
                retryTimes.put(videoId, System.currentTimeMillis() + FAILED_FETCH_RETRY_MILLISECONDS);
            }
        }
        return type.restoresOriginal ? fetchOriginalTitle(videoId) : null;
    }

    @Nullable
    private static String fetchOriginalTitle(String videoId) {
        try {
            String url = "https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, StandardCharsets.UTF_8.name());

            HttpURLConnection connection = Requester.openConnection(url);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                JSONObject json = Requester.parseJSONObject(connection);
                String title = json.optString("title");
                if (title.isEmpty()) {
                    return null;
                }
                originalVideos.put(videoId, new OriginalVideo(title, json.optString("author_name").trim()));
                TitleLayouts.originalTitleFetched(videoId, title);
                return title;
            }
            Logger.printDebug(() -> "oEmbed request failed for: " + videoId + " code: " + responseCode);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch original title of: " + videoId, ex);
            retryTimes.put(videoId, System.currentTimeMillis() + FAILED_FETCH_RETRY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "fetchOriginalTitle failure", ex);
        }
        return null;
    }
}
