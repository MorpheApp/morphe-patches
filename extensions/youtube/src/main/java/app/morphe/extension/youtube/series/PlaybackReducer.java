package app.morphe.extension.youtube.series;

import java.util.function.Consumer;
import java.util.function.IntSupplier;

/** Pure observation reducer. It never reads changing host getters or writes storage. */
public final class PlaybackReducer {
    public static final long CHECKPOINT_MS = 5000;

    public static final class Snapshot {
        public final String videoId;
        public final long generation, sequence, positionMs, durationMs, playedAt;
        public final boolean completed;

        Snapshot(
                String id,
                long generation,
                long sequence,
                long position,
                long duration,
                boolean completed,
                long playedAt) {
            videoId = id;
            this.generation = generation;
            this.sequence = sequence;
            positionMs = position;
            durationMs = duration;
            this.completed = completed;
            this.playedAt = playedAt;
        }
    }

    private final Consumer<Snapshot> output;
    private final IntSupplier completionPercent, completionSeconds;
    private String id = "";
    private long generation,
            sequence,
            position,
            duration,
            playedAt,
            candidateAt,
            sampleAt,
            queuedAt;
    private long stableDurationAt, previousCandidatePosition = -1;
    private boolean valid, dirty, completed, growingDuration;

    public PlaybackReducer(Consumer<Snapshot> output) {
        this(
                output,
                () -> CompletionPolicy.DEFAULT_PERCENT,
                () -> CompletionPolicy.DEFAULT_SECONDS);
    }

    public PlaybackReducer(Consumer<Snapshot> output, IntSupplier percentage, IntSupplier seconds) {
        this.output = output;
        completionPercent = percentage;
        completionSeconds = seconds;
    }

    public void newVideo(long now) {
        flush(now);
        resetSession();
    }

    public void clear() {
        resetSession();
    }

    private void resetSession() {
        generation++;
        id = "";
        position = duration = 0;
        valid = dirty = completed = growingDuration = false;
        candidateAt = stableDurationAt = sampleAt = queuedAt = 0;
        previousCandidatePosition = -1;
    }

    public void sample(
            String beforeId,
            String afterId,
            long positionMs,
            long durationMs,
            boolean excluded,
            long now,
            long wallTime) {
        if (excluded || beforeId == null || !beforeId.matches("[A-Za-z0-9_-]{11}")) {
            if (valid) newVideo(now);
            return;
        }
        if (!beforeId.equals(afterId) || positionMs < 0) return;
        if (!beforeId.equals(id)) {
            newVideo(now);
            id = beforeId;
            candidateAt = now;
            previousCandidatePosition = positionMs;
            if (durationMs > 0) {
                duration = durationMs;
                stableDurationAt = now;
            }
            return;
        }
        if (durationMs > 0) {
            if (duration > 0 && Math.abs(durationMs - duration) > 1000) growingDuration = true;
            if (duration != durationMs) stableDurationAt = now;
            duration = durationMs;
        }
        if (!valid) {
            boolean advancing = positionMs > previousCandidatePosition;
            previousCandidatePosition = positionMs;
            // The controller ID can exist before time initialization. Admit playback
            // only after advancing time corroborates the same controller identity.
            if (now - candidateAt < 500 || !advancing) return;
            valid = true;
        }
        boolean finite =
                !growingDuration
                        && duration > 0
                        && duration <= 7L * 24 * 3600000
                        && now - stableDurationAt >= 3000;
        boolean reachedEnd =
                finite
                        && CompletionPolicy.completed(
                                positionMs,
                                duration,
                                completionPercent.getAsInt(),
                                completionSeconds.getAsInt());
        boolean newlyCompleted = !completed && reachedEnd;
        if (position != positionMs || newlyCompleted || sampleAt == 0) {
            position = positionMs;
            completed |= reachedEnd;
            playedAt = wallTime;
            sequence++;
            dirty = true;
        }
        sampleAt = now;
        if (newlyCompleted || now - queuedAt >= CHECKPOINT_MS) flush(now);
    }

    public void state(String state, long now) {
        // An untagged ENDED signal alone cannot complete an incoming session.
        if (valid && now - sampleAt <= 2000 && (state.equals("PAUSED") || state.equals("ENDED")))
            flush(now);
    }

    public void flush(long now) {
        if (valid && dirty) {
            output.accept(
                    new Snapshot(
                            id,
                            generation,
                            sequence,
                            position,
                            growingDuration ? 0 : duration,
                            completed,
                            playedAt));
            queuedAt = now;
        }
    }

    public void acknowledge(long generation, long sequence) {
        if (this.generation == generation && this.sequence == sequence) dirty = false;
    }
}
