package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import java.util.*;
import java.util.concurrent.*;

/** One recent native History page per automatic check; bounded catch-up on Continue. */
final class NativeProgressSync {
    static final class Result {
        final Map<String, NativeHistoryPage.Row> rows = new LinkedHashMap<>();
        final Map<String, Long> durations = new HashMap<>();
        final Map<String, Long> observedAt = new HashMap<>();
        String account = "";
        int percent = CompletionPolicy.DEFAULT_PERCENT, seconds = CompletionPolicy.DEFAULT_SECONDS;
        boolean complete = true;
    }

    static Result fetch(
            List<Series> library, long generation, int percent, int seconds, boolean force)
            throws Exception {
        Result result = new Result();
        result.account = RecordingPrivacy.syncScope(generation);
        if (result.account.isEmpty()) throw new CancellationException("Account changed");
        result.percent = percent;
        result.seconds = seconds;
        Set<String> scope = new HashSet<>();
        for (Series series : library) {
            for (Episode ep : series.episodes)
                if (ep.available) {
                    scope.add(ep.videoId);
                    long duration =
                            ep.durationMs > 0
                                    ? ep.durationMs
                                    : series.progress(ep.videoId).durationMs;
                    result.durations.merge(ep.videoId, duration, Math::max);
                }
            if (!series.bookmarkId.isEmpty()) {
                scope.add(series.bookmarkId);
                result.durations.merge(
                        series.bookmarkId,
                        series.progress(series.bookmarkId).durationMs,
                        Math::max);
            }
        }
        if (scope.isEmpty()) return result;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        Set<String> tokens = new HashSet<>();
        String continuation = "";
        for (int n = 0; n < (force ? 3 : 1); n++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                result.complete = false;
                break;
            }
            NativeHistoryTransport.Page response =
                    NativeHistoryTransport.page(
                            continuation, generation, force ? 10_000 : 30_000, remaining);
            for (NativeHistoryPage.Row row : response.value.rows.values()) {
                if (!scope.contains(row.id) || result.rows.containsKey(row.id)) continue;
                result.rows.put(row.id, row);
                result.observedAt.put(row.id, response.startedAt);
            }
            continuation = response.value.continuation;
            if (continuation.isEmpty() || !tokens.add(continuation)) break;
            // Continue needs deeper pages only when some followed series is absent from recent
            // History.
            boolean missing = false;
            for (Series series : library) {
                boolean found = result.rows.containsKey(series.bookmarkId);
                for (Episode ep : series.episodes)
                    if (result.rows.containsKey(ep.videoId)) {
                        found = true;
                        break;
                    }
                if (!found) {
                    missing = true;
                    break;
                }
            }
            if (!missing) break;
        }
        return result;
    }

    private NativeProgressSync() {}
}
