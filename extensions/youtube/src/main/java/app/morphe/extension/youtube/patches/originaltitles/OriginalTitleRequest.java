/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3384
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Fetches original video titles from the public oEmbed endpoint,
 * which always returns the title as set by the uploader.
 */
final class OriginalTitleRequest {

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 5000;

    /**
     * Time before a title that failed to fetch because of network errors is fetched again.
     * The translated title is shown meanwhile, so a failing network does not keep loading.
     */
    private static final long FAILED_FETCH_RETRY_MILLISECONDS = 30_000;

    /**
     * Video id -> original title. A null title means the video has no available title,
     * such as a private video, or the title failed to fetch because of network errors.
     */
    private static final Map<String, CompletableFuture<String>> cache =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    /**
     * Video id -> time when the title that failed to fetch can be fetched again.
     */
    private static final Map<String, Long> retryTimes =
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000));

    static CompletableFuture<String> fetch(String videoId) {
        synchronized (cache) {
            CompletableFuture<String> future = cache.get(videoId);
            if (future == null || canRetry(videoId)) {
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

    private static boolean canRetry(String videoId) {
        Long retryTime = retryTimes.get(videoId);
        return retryTime != null && System.currentTimeMillis() >= retryTime;
    }

    /**
     * @return If the title is not yet fetched. Titles that failed to fetch
     *         are not pending until they are fetched again.
     */
    static boolean isPending(String videoId) {
        CompletableFuture<String> future = cache.get(videoId);
        return future == null || !future.isDone();
    }

    /**
     * Calls the callback on the main thread when the title is fetched.
     * The title is null if the video has no available title, or if it failed to fetch.
     */
    static void getAsync(String videoId, Consumer<String> callback) {
        fetch(videoId).thenAccept(title ->
                Utils.runOnMainThreadNowOrLater(() -> callback.accept(title)));
    }

    @Nullable
    private static String fetchTitle(String videoId) {
        try {
            String url = "https://www.youtube.com/oembed?format=json&url="
                    + URLEncoder.encode("https://www.youtube.com/watch?v=" + videoId, "UTF-8");

            HttpURLConnection connection = Requester.openConnection(url);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

            final int responseCode = connection.getResponseCode();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                String title = Requester.parseJSONObjectAndDisconnect(connection).optString("title");
                return title.isEmpty() ? null : title;
            }

            connection.disconnect();
            Logger.printDebug(() -> "oEmbed request failed for: " + videoId + " code: " + responseCode);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch original title of: " + videoId, ex);
            retryTimes.put(videoId, System.currentTimeMillis() + FAILED_FETCH_RETRY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "fetchTitle failure", ex);
        }
        return null;
    }
}
