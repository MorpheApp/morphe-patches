/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.ask;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.utils.requests.ChannelSearchRoutes;

/**
 * Fetches if a video is live now or upcoming, which Ask cannot summarize until the stream ends.
 * The request is the watch page of the web client, which tells it without signing in,
 * and counts an upcoming stream as live. Videos of lists are known without a request,
 * from {@link app.morphe.extension.youtube.patches.components.AskLiveStatusFilter}.
 */
public final class LiveStatusRequest {
    /**
     * Without an answer by then, the video is treated as not live.
     */
    private static final long MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 5 * 1000;

    private static final Map<String, LiveStatusRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private final Future<Boolean> future;

    private LiveStatusRequest(String videoId) {
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId));
    }

    private LiveStatusRequest(boolean isLive) {
        this.future = CompletableFuture.completedFuture(isLive);
    }

    /**
     * Saves that a video is live or upcoming, as a list shows it.
     */
    public static void setLiveOrUpcoming(String videoId) {
        LiveStatusRequest known = cache.get(videoId);
        if (known == null || !known.isLive()) {
            cache.put(videoId, new LiveStatusRequest(true));
        }
    }

    static LiveStatusRequest fetchRequestIfNeeded(String videoId) {
        return cache.computeIfAbsent(videoId, LiveStatusRequest::new);
    }

    boolean fetchIsDone() {
        return future.isDone();
    }

    /**
     * @return If the fetch is done and the video is live or upcoming.
     */
    boolean isLive() {
        return future.isDone() && Boolean.TRUE.equals(getResult(0));
    }

    /**
     * Calls back on the main thread when the fetch is done, with if the video is live or upcoming.
     * A failed fetch calls back with false.
     */
    void whenDone(Consumer<Boolean> onDone) {
        Utils.runOnBackgroundThread(() -> {
            final boolean isLive = Boolean.TRUE.equals(getResult(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH));
            Utils.runOnMainThread(() -> onDone.accept(isLive));
        });
    }

    @Nullable
    private Boolean getResult(long timeoutMilliseconds) {
        try {
            return future.get(timeoutMilliseconds, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "Live status request timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "Live status request interrupted", ex);
            Thread.currentThread().interrupt();
        } catch (ExecutionException ex) {
            Logger.printException(() -> "Live status request failure", ex);
        }
        return null;
    }

    @Nullable
    private static Boolean fetch(String videoId) {
        final long startTime = System.currentTimeMillis();
        JSONObject response = ChannelSearchRoutes.fetch(ChannelSearchRoutes.GET_LIVE_STATUS,
                ChannelSearchRoutes.createWebVideoBody(videoId, Locale.getDefault()));
        if (response == null || !response.has("currentVideoEndpoint")) {
            Logger.printDebug(() -> "No live status of video: " + videoId);
            // Fetch again the next time the menu opens.
            cache.remove(videoId);
            return null;
        }

        // The video is live if any count of the watch page is the count of a live stream.
        final boolean isLive = response.toString().contains("\"isLive\":true");
        Logger.printDebug(() -> "Video: " + videoId + " is live: " + isLive
                + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        return isLive;
    }
}
