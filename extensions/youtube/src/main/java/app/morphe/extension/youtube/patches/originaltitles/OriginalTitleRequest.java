/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.patches.dearrow.DeArrowBrandingRequest;
import app.morphe.extension.youtube.patches.dearrow.DeArrowPatch;
import app.morphe.extension.youtube.patches.dearrow.DeArrowPatch.DeArrowTitlesAvailability;
import app.morphe.extension.youtube.patches.utils.requests.ChannelIdRoutes;

/**
 * Fetches the titles that replace the titles shown by YouTube.
 * <p>
 * Original titles are fetched from the public oEmbed endpoint, which always returns the title
 * as set by the uploader. Titles that oEmbed does not return, such as the titles of the videos
 * whose embedding is disabled, are fetched from the player endpoint without an account.
 * DeArrow titles are fetched with {@link DeArrowBrandingRequest}, and if DeArrow has no title
 * then the original title is used if original titles are restored.
 * <p>
 * DeArrow titles can be used only for some navigations, such as only for the search results,
 * so the title that replaces the title is chosen when the title is shown.
 */
final class OriginalTitleRequest {

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 5000;

    /**
     * Time before a title that failed to fetch because of network errors is fetched again.
     * The translated title is shown meanwhile, so a failing network does not keep loading.
     */
    private static final long FAILED_FETCH_RETRY_MILLISECONDS = 30_000;

    /**
     * Titles that can replace the title of a video.
     *
     * @param deArrowTitle  The DeArrow title, or null if the video has no DeArrow title,
     *                      DeArrow titles are not used, or it failed to fetch.
     * @param originalTitle The original title, or null if original titles are not restored,
     *                      the video has no available title such as a private video,
     *                      the DeArrow title is used for all navigations, or it failed to fetch.
     */
    record Titles(@Nullable String deArrowTitle, @Nullable String originalTitle) {
        /**
         * Can be called on any thread.
         *
         * @return The title that replaces the title of the video for the current navigation,
         *         or null if the title is not replaced.
         */
        @Nullable
        String replacement() {
            if (deArrowTitle != null && DeArrowPatch.useDeArrowTitlesForCurrentNavigation()) {
                return deArrowTitle;
            }
            return originalTitle;
        }
    }

    /**
     * Video id -> titles.
     */
    private static final Map<String, CompletableFuture<Titles>> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Original title and name of the channel of a video, as returned by the oEmbed endpoint,
     * or by the player endpoint if oEmbed does not return the title.
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
     * Video id -> time when the title that failed to fetch can be fetched again.
     */
    private static final Map<String, Long> retryTimes =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    static CompletableFuture<Titles> fetch(String videoId) {
        synchronized (cache) {
            CompletableFuture<Titles> future = cache.get(videoId);
            Long retryTime = retryTimes.get(videoId);
            if (future == null || (retryTime != null && System.currentTimeMillis() >= retryTime)) {
                retryTimes.remove(videoId);
                future = CompletableFuture.supplyAsync(() -> fetchTitles(videoId), Utils::runOnBackgroundThread);
                cache.put(videoId, future);
            }
            return future;
        }
    }

    /**
     * Starts fetching the title if needed, and does not wait for it.
     *
     * @return The title that replaces the title of the video for the current navigation,
     *         or null if not yet available or the title is not replaced.
     */
    @Nullable
    static String getIfAvailable(String videoId) {
        Titles titles = fetch(videoId).getNow(null);
        return titles == null ? null : titles.replacement();
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
     * @return If the title is not yet fetched. Titles that failed to fetch
     *         are not pending until they are fetched again.
     */
    static boolean isPending(String videoId) {
        CompletableFuture<Titles> future = cache.get(videoId);
        return future == null || !future.isDone();
    }

    private static Titles fetchTitles(String videoId) {
        String deArrowTitle = null;
        if (RestoreOriginalTitlesPatch.USE_DEARROW) {
            try {
                deArrowTitle = DeArrowBrandingRequest.fetchTitle(videoId);
            } catch (DeArrowBrandingRequest.DeArrowException ex) {
                // The DeArrow title is fetched again later, and the original title is used meanwhile.
                retryTimes.put(videoId, System.currentTimeMillis() + FAILED_FETCH_RETRY_MILLISECONDS);
            }
        }
        // The original title is not needed if the DeArrow title is used for all navigations.
        final boolean fetchOriginal = RestoreOriginalTitlesPatch.RESTORE_ORIGINAL
                && (deArrowTitle == null || !DeArrowTitlesAvailability.usingDeArrowTitlesEverywhere());
        return new Titles(deArrowTitle, fetchOriginal ? fetchOriginalTitle(videoId) : null);
    }

    @Nullable
    private static String fetchOriginalTitle(String videoId) {
        try {
            //noinspection CharsetObjectCanBeUsed
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
                return originalTitleFetched(videoId, title, json.optString("author_name"));
            }
            Logger.printDebug(() -> "oEmbed request failed for: " + videoId + " code: " + responseCode);

            // oEmbed does not return the title of some videos,
            // such as the videos whose embedding is disabled by the uploader (code 401).
            return fetchPlayerTitle(videoId);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch original title of: " + videoId, ex);
            retryTimes.put(videoId, System.currentTimeMillis() + FAILED_FETCH_RETRY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "fetchOriginalTitle failure", ex);
        }
        return null;
    }

    /**
     * Fetches the original title from the player endpoint without an account,
     * which returns the title as set by the uploader, and not the translated title.
     */
    @Nullable
    private static String fetchPlayerTitle(String videoId) throws IOException, JSONException {
        byte[] requestBody = ChannelIdRoutes.createBody(videoId);
        HttpURLConnection connection = ChannelIdRoutes.getConnection(ChannelIdRoutes.GET_TITLE);
        connection.setFixedLengthStreamingMode(requestBody.length);
        try (OutputStream output = connection.getOutputStream()) {
            output.write(requestBody);
        }

        final int responseCode = connection.getResponseCode();
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            Logger.printDebug(() -> "Player title request failed for: " + videoId + " code: " + responseCode);
            return null;
        }
        JSONObject videoDetails = Requester.parseJSONObject(connection).optJSONObject("videoDetails");
        String title = videoDetails == null ? "" : videoDetails.optString("title");
        if (title.isEmpty()) {
            Logger.printDebug(() -> "Player response has no title for: " + videoId);
            return null;
        }
        Logger.printDebug(() -> "Original title fetched from the player endpoint for: " + videoId);
        return originalTitleFetched(videoId, title, videoDetails.optString("author"));
    }

    private static String originalTitleFetched(String videoId, String title, String channelName) {
        originalVideos.put(videoId, new OriginalVideo(title, channelName.trim()));
        TitleLayouts.originalTitleFetched(videoId, title);
        return title;
    }
}
