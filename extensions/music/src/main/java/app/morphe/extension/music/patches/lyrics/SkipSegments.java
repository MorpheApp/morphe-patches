/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3575
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.sponsorblock.SegmentPlaybackController;
import app.morphe.extension.shared.sponsorblock.objects.SponsorSegment;
import app.morphe.extension.shared.sponsorblock.requests.SBRoutes;

/**
 * SponsorBlock skip segments used to align lyrics with playback.
 *
 * <p>Lyrics fetched from title-keyed providers are written against the released audio: the
 * content that is left after the segments a sponsor block would skip. The player reports
 * video time, which still contains those segments, so playback time has to drop every
 * segment that precedes it before it is compared with lyric timestamps ({@link #contentMs}),
 * and a seek has to push the lyric timestamp forward by those same segments instead
 * ({@link #remapTimestamp}). The provider search filters candidates by duration, so it has
 * to ask with the content duration instead ({@link #effectiveDurationMs}).
 *
 * <p>Every entry point follows the lyrics SponsorBlock switch: while it is off nothing is
 * requested, every answer is null, and the pipeline behaves as if segments did not exist.
 * Failures are silent and retried after a short cooldown, so an
 * unreachable service never costs the lyrics lookup more than one background round trip, and
 * while no own answer exists the segments SponsorBlock itself downloaded for the playing
 * video are used instead ({@link #fromController}).
 *
 * <p>{@link #await} blocks, so it is only called from the lyrics lookup threads - never from
 * the main thread. {@link #peek} never blocks and is the way playback time reads segments.
 */
public final class SkipSegments {

    public record Seg(long startMs, long endMs, @NonNull String category,
                      long reportedDurationMs) {
    }

    private static final long REPORTED_DURATION_TOLERANCE_MS = 5_000;

    private static final int TIMEOUT_TCP_MS = 7000;
    private static final int TIMEOUT_HTTP_MS = 10000;

    private static final long AWAIT_TIMEOUT_MS = 4000;

    private static final int CACHE_MAX_ENTRIES = 16;

    /**
     * Every music relevant category, URL-encoded the way {@code SegmentCategory} builds the
     * list for the API. Every segment of these categories separates the video from the
     * released audio, so all of them count - the local skip behavior decides whether the
     * player jumps over them, not whether they exist in the timeline.
     */
    private static final String FETCH_CATEGORIES = "[%22sponsor%22,%22selfpromo%22,"
            + "%22interaction%22,%22intro%22,%22outro%22,%22preview%22,%22hook%22,"
            + "%22filler%22,%22music_offtopic%22]";

    private static final Object LOCK = new Object();

    /** Video id to sorted, merged segments; entries are empty when the fetch found none. */
    private static final Map<String, List<Seg>> CACHE = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, List<Seg>> eldest) {
            return size() > CACHE_MAX_ENTRIES;
        }
    };

    private static final Map<String, FutureTask<List<Seg>>> TASKS = new HashMap<>();

    /**
     * When the last fetch of a video failed, the next attempt may start. A failed fetch is
     * never cached, so without this the playback path would start a new request every frame.
     */
    private static final Map<String, Long> RETRY_AFTER = new HashMap<>();

    private static final Map<String, Integer> FETCH_EPOCH = new HashMap<>();

    private static final long RETRY_COOLDOWN_MS = 15_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MorpheSkipSegments");
        thread.setDaemon(true);
        return thread;
    });

    private SkipSegments() {
    }

    /**
     * Starts fetching the segments of a video in the background. Cheap when the video is
     * already cached or being fetched. Never throws, never blocks.
     */
    public static void prefetch(@Nullable String videoId) {
        if (videoId == null || videoId.isEmpty() || !Settings.LYRICS_SB_MATCHING.get()) {
            return;
        }
        synchronized (LOCK) {
            if (CACHE.containsKey(videoId) || TASKS.containsKey(videoId)) {
                return;
            }
            startFetchLocked(videoId);
        }
    }

    public static void invalidate(@NonNull String videoId) {
        synchronized (LOCK) {
            CACHE.remove(videoId);
            TASKS.remove(videoId);
            RETRY_AFTER.remove(videoId);
            FETCH_EPOCH.merge(videoId, 1, Integer::sum);
        }
    }

    /**
     * Waits for the segments of a video, up to {@link #AWAIT_TIMEOUT_MS}.
     *
     * @param durationMs Duration of the track; a segment whose submitting client recorded
     *                   a different video duration is stale and dropped.
     * @return The sorted, merged segments; empty when the video has none, null when no
     *         answer is available or the switch is off - both mean no correction applies.
     */
    @Nullable
    public static List<Seg> await(@Nullable String videoId, long durationMs) {
        return await(videoId, durationMs, AWAIT_TIMEOUT_MS);
    }

    @Nullable
    public static List<Seg> await(@Nullable String videoId, long durationMs, long timeoutMs) {
        if (videoId == null || videoId.isEmpty() || !Settings.LYRICS_SB_MATCHING.get()) {
            return null;
        }
        FutureTask<List<Seg>> task;
        synchronized (LOCK) {
            if (CACHE.containsKey(videoId)) {
                task = null;
            } else {
                task = TASKS.get(videoId);
                if (task == null) {
                    task = startFetchLocked(videoId);
                }
            }
        }
        List<Seg> segs = null;
        if (task != null) {
            try {
                segs = task.get(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // The fetch keeps running; the next await picks the result up.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException ignored) {
                // fetchFor never throws, so this is only a safety net.
            }
        }
        if (segs == null) {
            synchronized (LOCK) {
                segs = CACHE.get(videoId);
            }
        }
        if (segs == null) {
            // Nothing answered - the fetch failed, is cooling down, or had not started.
            // SponsorBlock may already hold the segments for the video being played.
            segs = fromController(videoId);
        }
        return applicable(segs, durationMs);
    }

    @Nullable
    public static List<Seg> peek(@Nullable String videoId, long durationMs) {
        if (videoId == null || videoId.isEmpty() || !Settings.LYRICS_SB_MATCHING.get()) {
            return null;
        }
        List<Seg> segs;
        synchronized (LOCK) {
            segs = CACHE.get(videoId);
        }
        if (segs == null) {
            segs = fromController(videoId);
        }
        return applicable(segs, durationMs);
    }

    /**
     * The duration the provider search should ask with: the track without the parts the
     * segments skip, so candidates written for the released audio pass the duration filter.
     */
    public static long effectiveDurationMs(long durationMs, @Nullable List<Seg> segs) {
        if (segs == null || segs.isEmpty() || durationMs <= 0) {
            return durationMs;
        }
        long effective = durationMs - unionMs(segs);
        return effective > 0 ? effective : durationMs;
    }

    /**
     * Maps a timestamp of the released audio (content time) onto the video timeline that
     * the player reports: every segment that starts at or before the timestamp adds its
     * length. The segments must be sorted and merged, which {@link #await} guarantees.
     */
    public static long remapTimestamp(long contentMs, @Nullable List<Seg> segs) {
        if (contentMs < 0 || segs == null || segs.isEmpty()) {
            return contentMs;
        }
        long videoMs = contentMs;
        for (Seg seg : segs) {
            if (videoMs < seg.startMs()) {
                break;
            }
            videoMs += seg.endMs() - seg.startMs();
        }
        return videoMs;
    }

    public static long contentMs(long videoMs, @Nullable List<Seg> segs) {
        if (videoMs < 0 || segs == null || segs.isEmpty()) {
            return videoMs;
        }
        long skipped = 0;
        for (Seg seg : segs) {
            if (videoMs < seg.startMs()) {
                break;
            }
            if (videoMs < seg.endMs()) {
                return seg.startMs() - skipped;
            }
            skipped += seg.endMs() - seg.startMs();
        }
        return videoMs - skipped;
    }

    private static long unionMs(@NonNull List<Seg> segs) {
        long union = 0;
        for (Seg seg : segs) {
            union += seg.endMs() - seg.startMs();
        }
        return union;
    }

    /**
     * The check every public answer passes: no answer stays null, a video without segments
     * is answered with an empty list, and a segment whose reported video duration
     * disagrees with the playing one is stale - the video was recut since it was submitted
     * - and is dropped. Nothing is discarded by coverage: identity by video id is exact,
     * and a long intro and outro can legitimately take half the video.
     */
    @Nullable
    private static List<Seg> applicable(@Nullable List<Seg> segs, long durationMs) {
        if (segs == null) {
            return null;
        }
        if (segs.isEmpty()) {
            return List.of();
        }
        if (durationMs <= 0) {
            return segs;
        }
        boolean stale = false;
        for (Seg seg : segs) {
            final long reported = seg.reportedDurationMs();
            if (reported > 0 && Math.abs(reported - durationMs) > REPORTED_DURATION_TOLERANCE_MS) {
                stale = true;
                break;
            }
        }
        if (!stale) {
            return segs;
        }
        final List<Seg> kept = new ArrayList<>(segs.size());
        for (Seg seg : segs) {
            final long reported = seg.reportedDurationMs();
            if (reported > 0 && Math.abs(reported - durationMs) > REPORTED_DURATION_TOLERANCE_MS) {
                continue;
            }
            kept.add(seg);
        }
        return kept.isEmpty() ? List.of() : List.copyOf(kept);
    }

    /**
     * The segments SponsorBlock downloaded for the video that is currently playing. They
     * are the same answer the own fetch asks for, available whenever SponsorBlock itself
     * resolved this video - the fallback for a fetch that has not answered yet. Only the
     * controller's own video id vouches for them: the controller clears its data when the
     * video changes, but a reader can still race that clear, so an id of its own is what
     * makes the segments usable and anything else is ignored. Not cached: the controller
     * owns the data and refreshes it when its own fetch lands.
     */
    @Nullable
    private static List<Seg> fromController(@NonNull String videoId) {
        if (!videoId.equals(SegmentPlaybackController.getCurrentVideoId())) {
            return null;
        }
        SponsorSegment[] controller = SegmentPlaybackController.getSegments();
        if (controller == null || controller.length == 0) {
            return null;
        }
        List<Seg> segs = new ArrayList<>(controller.length);
        for (SponsorSegment segment : controller) {
            if (segment.end > segment.start) {
                segs.add(new Seg(segment.start, segment.end, segment.category.keyValue, 0));
            }
        }
        if (segs.isEmpty()) {
            return null;
        }
        segs.sort(Comparator.comparingLong(Seg::startMs));
        return merge(segs);
    }

    @Nullable
    private static FutureTask<List<Seg>> startFetchLocked(String videoId) {
        Long retryAfter = RETRY_AFTER.get(videoId);
        if (retryAfter != null && System.currentTimeMillis() < retryAfter) {
            return null;
        }
        final int epoch = FETCH_EPOCH.getOrDefault(videoId, 0);
        FutureTask<List<Seg>> task = new FutureTask<>(() -> fetchFor(videoId, epoch));
        TASKS.put(videoId, task);
        EXECUTOR.execute(task);
        return task;
    }

    @Nullable
    private static List<Seg> fetchFor(String videoId, int epoch) {
        List<Seg> result = null;
        try {
            result = fetch(videoId);
        } catch (Exception ignored) {
            // Silent by design: no toast, no log spam - the lyrics lookup must not notice.
        }
        synchronized (LOCK) {
            if (FETCH_EPOCH.getOrDefault(videoId, 0) != epoch) {
                return result;
            }
            if (result == null) {
                // Not an answer: nothing is cached, the cooldown keeps every reader from
                // starting a new attempt until it expires.
                RETRY_AFTER.put(videoId, System.currentTimeMillis() + RETRY_COOLDOWN_MS);
            } else {
                CACHE.put(videoId, result);
                RETRY_AFTER.remove(videoId);
            }
            TASKS.remove(videoId);
        }
        return result;
    }

    /**
     * Asks the SponsorBlock API for the segments of a video. Empty means the video has
     * none; null means the service could not answer, which is not an answer at all.
     */
    @Nullable
    private static List<Seg> fetch(String videoId) throws IOException, JSONException {
        List<Seg> segments = new ArrayList<>();
        HttpURLConnection connection = Requester.getConnectionFromRoute(
                Settings.SB_API_URL.get(), SBRoutes.GET_SEGMENTS, videoId, FETCH_CATEGORIES);
        connection.setConnectTimeout(TIMEOUT_TCP_MS);
        connection.setReadTimeout(TIMEOUT_HTTP_MS);
        final int responseCode = connection.getResponseCode();
        if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
            JSONArray responseArray = Requester.parseJSONArray(connection);
            for (int i = 0, length = responseArray.length(); i < length; i++) {
                JSONObject obj = responseArray.getJSONObject(i);
                JSONArray segment = obj.getJSONArray("segment");
                final long start = (long) (segment.getDouble(0) * 1000);
                final long end = (long) (segment.getDouble(1) * 1000);
                if (end <= start) {
                    continue;
                }
                final double reported = obj.optDouble("videoDuration", 0);
                segments.add(new Seg(start, end, obj.getString("category"),
                        reported > 0 ? (long) (reported * 1000) : 0));
            }
        } else if (responseCode == 404) {
            // No segments for this video - a normal answer.
            return List.of();
        } else {
            connection.disconnect();
            return null;
        }

        segments.sort(Comparator.comparingLong(Seg::startMs));
        return merge(segments);
    }

    /**
     * The segments of {@code base} with one more segment folded in, stored the way the
     * cache keeps them: sorted and merged. A null or empty base leaves only the extra
     * segment. Used by the submit dialog to preview its candidate before submission.
     */
    @NonNull
    public static List<Seg> withSegment(@Nullable List<Seg> base, @NonNull Seg extra) {
        if (base == null || base.isEmpty()) {
            return List.of(extra);
        }
        List<Seg> sorted = new ArrayList<>(base.size() + 1);
        sorted.addAll(base);
        sorted.add(extra);
        sorted.sort(Comparator.comparingLong(Seg::startMs));
        return merge(sorted);
    }

    @NonNull
    private static List<Seg> merge(@NonNull List<Seg> sorted) {
        if (sorted.isEmpty()) {
            return List.of();
        }
        List<Seg> merged = new ArrayList<>(sorted.size());
        Seg current = sorted.get(0);
        for (int i = 1; i < sorted.size(); i++) {
            Seg next = sorted.get(i);
            if (next.startMs() <= current.endMs()) {
                current = new Seg(current.startMs(),
                        Math.max(current.endMs(), next.endMs()), current.category(),
                        current.reportedDurationMs() != 0
                                ? current.reportedDurationMs() : next.reportedDurationMs());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return Collections.unmodifiableList(merged);
    }
}
