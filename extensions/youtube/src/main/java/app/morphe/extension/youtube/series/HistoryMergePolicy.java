package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

/** History snapshots have observation time, not a last-watch timestamp. */
final class HistoryMergePolicy {
    static final class Baseline {
        final String signature;
        final long seenAt;

        Baseline(String signature, long seenAt) {
            this.signature = signature;
            this.seenAt = seenAt;
        }
    }

    static Progress merge(
            Progress local,
            NativeHistoryPage.Row row,
            Baseline previous,
            long observedAt,
            long duration,
            int percentage,
            int seconds) {
        if (!local.videoId.equals(row.id)
                || local.override != TrackerModels.Override.AUTO
                || observedAt <= local.playedAt
                || observedAt <= 0
                || duration <= 0
                || duration > 604_800_000L
                || previous != null
                        && (observedAt <= previous.seenAt
                                || row.signature().equals(previous.signature))) return local;
        Long position = row.positionMs;
        if (position != null && (position < 0 || position > duration)) position = null;
        boolean completed =
                row.percent != null && row.percent >= CompletionPolicy.percent(percentage)
                        || position != null
                                && CompletionPolicy.completed(
                                        position, duration, percentage, seconds);
        if (position == null && !completed) return local; // Percentage is not an exact resume time.
        long target = position == null ? local.positionMs : position;
        if (!completed && (target < local.positionMs || local.autoCompleted)) {
            // A changed server snapshot can establish a rewatch only after a prior observation.
            // Initial snapshots and delayed echoes must not erase a newer local checkpoint.
            if (previous == null || local.playedAt > previous.seenAt) return local;
            if (target < local.positionMs && local.positionMs - target <= 30_000)
                target = local.positionMs; // Keep the precise phone checkpoint for short backsteps.
        }
        if (completed) target = Math.max(target, local.positionMs);
        if (target == local.positionMs && completed == local.autoCompleted) return local;
        return new Progress(
                local.videoId,
                target,
                duration,
                completed,
                local.override,
                observedAt,
                local.editRevision);
    }

    private HistoryMergePolicy() {}
}
