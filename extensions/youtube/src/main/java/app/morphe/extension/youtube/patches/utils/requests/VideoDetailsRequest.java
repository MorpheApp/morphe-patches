/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3684
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The exact publish date, view count, length, description and tags of videos, one request per video.
 * Only used for search results that the uploads of the channel do not tell about.
 */
public final class VideoDetailsRequest {

    public static final class VideoDetails {
        /** Seconds since the epoch. */
        public final long publishTimeSeconds;
        public final long viewCount;
        public final long lengthSeconds;
        /** Description and tags. */
        public final String text;

        private VideoDetails(long publishTimeSeconds, long viewCount, long lengthSeconds, String text) {
            this.publishTimeSeconds = publishTimeSeconds;
            this.viewCount = viewCount;
            this.lengthSeconds = lengthSeconds;
            this.text = text;
        }
    }

    /**
     * Descriptions can be long, and only need to be searched.
     */
    private static final int MAX_TEXT_LENGTH = 5000;

    /**
     * Each request is small, but a channel can have hundreds of results,
     * so only a few run at the same time.
     */
    private static final int MAX_PARALLEL_REQUESTS = 8;

    /**
     * Results refresh after this many videos, instead of after every one of them.
     */
    private static final int PROGRESS_INTERVAL = 25;

    private static final ThreadPoolExecutor executor = new ThreadPoolExecutor(
            MAX_PARALLEL_REQUESTS, MAX_PARALLEL_REQUESTS,
            10, TimeUnit.SECONDS, new LinkedBlockingQueue<>());

    static {
        executor.allowCoreThreadTimeOut(true);
    }

    private static final Map<String, VideoDetails> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(1000));

    /**
     * Videos whose details are being fetched, so a video is never requested twice at once.
     */
    private static final Set<String> pending = ConcurrentHashMap.newKeySet();

    public interface Callback {
        /**
         * Called on a background thread after some of the details arrived, and once
         * more when all of them did or the fetch was cancelled.
         */
        void onProgress(boolean isComplete);
    }

    private VideoDetailsRequest() {
    }

    /**
     * @return The details, or null if they were not fetched yet or could not be fetched.
     */
    @Nullable
    public static VideoDetails get(String videoId) {
        return cache.get(videoId);
    }

    /**
     * Fetches the details of the videos that are not cached or already being fetched.
     */
    public static void fetch(Collection<String> videoIds, BooleanSupplier isCancelled, Callback callback) {
        AtomicInteger remaining = new AtomicInteger(1);
        AtomicInteger done = new AtomicInteger();

        for (String videoId : videoIds) {
            if (cache.containsKey(videoId) || !pending.add(videoId)) {
                continue;
            }

            remaining.incrementAndGet();
            executor.execute(() -> {
                try {
                    if (!isCancelled.getAsBoolean()) {
                        VideoDetails details = fetchDetails(videoId);
                        if (details != null) {
                            cache.put(videoId, details);
                        }
                    }
                } finally {
                    pending.remove(videoId);
                    finishOne(remaining, done, callback);
                }
            });
        }

        // The initial count keeps the callback from completing while tasks are still added.
        finishOne(remaining, done, callback);
    }

    private static void finishOne(AtomicInteger remaining, AtomicInteger done, Callback callback) {
        if (remaining.decrementAndGet() == 0) {
            callback.onProgress(true);
        } else if (done.incrementAndGet() % PROGRESS_INTERVAL == 0) {
            callback.onProgress(false);
        }
    }

    @Nullable
    private static VideoDetails fetchDetails(String videoId) {
        Utils.verifyOffMainThread();

        try {
            JSONObject json = ChannelSearchRoutes.fetch(ChannelSearchRoutes.GET_VIDEO_DETAILS,
                    ChannelSearchRoutes.createVideoBody(videoId, Locale.US));
            if (json == null) {
                return null;
            }

            JSONObject microformat = json.optJSONObject("microformat");
            JSONObject renderer = microformat == null ? null
                    : microformat.optJSONObject("playerMicroformatRenderer");
            String publishDate = renderer == null ? "" : renderer.optString("publishDate");
            if (publishDate.isEmpty()) {
                // Videos that need a sign in, such as age restricted ones, have no details.
                Logger.printDebug(() -> "Video has no details: " + videoId);
                return null;
            }

            JSONObject videoDetails = json.optJSONObject("videoDetails");
            if (videoDetails == null) {
                videoDetails = new JSONObject();
            }

            StringBuilder text = new StringBuilder(videoDetails.optString("shortDescription"));
            JSONArray keywords = videoDetails.optJSONArray("keywords");
            if (keywords != null) {
                for (int i = 0, length = keywords.length(); i < length; i++) {
                    text.append(' ').append(keywords.optString(i));
                }
            }
            if (text.length() > MAX_TEXT_LENGTH) {
                text.setLength(MAX_TEXT_LENGTH);
            }

            return new VideoDetails(
                    OffsetDateTime.parse(publishDate).toEpochSecond(),
                    videoDetails.optLong("viewCount"),
                    videoDetails.optLong("lengthSeconds"),
                    text.toString()
            );
        } catch (Exception ex) {
            Logger.printException(() -> "Video details failed: " + videoId, ex);
        }
        return null;
    }
}
