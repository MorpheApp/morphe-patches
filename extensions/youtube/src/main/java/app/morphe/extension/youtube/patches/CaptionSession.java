

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;
import java.util.function.BooleanSupplier;

import app.morphe.extension.shared.Logger;

/**
 * Text of the caption cues of one source clock.
 * Translation delivery and display geometry never choose new cue times.
 */
final class CaptionSession {
    interface Translator {
        List<String> translate(String source, String target, List<String> texts) throws IOException;

        default List<String> translate(String source, String target, List<String> texts,
                                       BooleanSupplier active) throws IOException {
            return translate(source, target, texts);
        }

        @Nullable
        default String cached(String source, String target, String text) {
            return null;
        }
    }

    static final class Snapshot {
        final long start, end;
        final String primary, secondary;
        final boolean complete;

        Snapshot(StableCaptionBlockPolicy.Block cue, String primary, String secondary, boolean complete) {
            start = cue.start;
            end = cue.end;
            this.primary = primary;
            this.secondary = secondary;
            this.complete = complete;
        }
    }

    private static final byte STATUS_PENDING = 0;
    private static final byte STATUS_REQUESTED = 1;
    private static final byte STATUS_READY = 2;
    private static final byte STATUS_FAILED = 3;

    /**
     * Value of a language that failed permanently for a cue.
     */
    private static final String FAILED_OUTPUT = "";

    private static final int PRIMARY_SLOT = 0;
    private static final int SECONDARY_SLOT = 1;
    private static final int SLOT_COUNT = 2;

    private static final int CACHE_CAPACITY = 64;
    private static final byte MAX_ATTEMPTS = 3;
    private static final long RETRY_BASE_MS = 1000;
    private static final long SEEK_BACK_THRESHOLD_MS = 250;
    private static final long PREFETCH_MS = 30000;
    private static final float MAX_PREFETCH_SPEED = 4;

    final String sourceLanguage, primaryLanguage;
    @Nullable
    final String secondaryLanguage;
    final List<StableCaptionBlockPolicy.Block> source;

    private final Translator translator;
    private final Executor worker;
    private final Runnable changed, failed;
    private final LongSupplier clock;

    private final byte[] status, attempts;
    private final long[] retryAt;
    private final Map<Integer, String[]> ready = new LinkedHashMap<>(CACHE_CAPACITY, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, String[]> entry) {
            return size() > CACHE_CAPACITY;
        }
    };

    private float playbackRate = 1;
    private boolean active = true, running, failureReported;
    private long position, lastDisplayTime = Long.MIN_VALUE;
    private int lastIndex = -1;
    @Nullable
    private Snapshot displayed;

    CaptionSession(List<StableCaptionBlockPolicy.Block> source, String sourceLanguage,
                   String primaryLanguage, @Nullable String secondaryLanguage, Translator translator,
                   Executor worker, Runnable changed, Runnable failed) {
        this(source, sourceLanguage, primaryLanguage, secondaryLanguage, translator, worker,
                changed, failed, () -> System.nanoTime() / 1_000_000L);
    }

    CaptionSession(List<StableCaptionBlockPolicy.Block> source, String sourceLanguage,
                   String primaryLanguage, @Nullable String secondaryLanguage, Translator translator,
                   Executor worker, Runnable changed, Runnable failed, LongSupplier clock) {
        this.source = List.copyOf(source);
        this.sourceLanguage = Objects.requireNonNull(sourceLanguage);
        this.primaryLanguage = Objects.requireNonNull(primaryLanguage);
        this.secondaryLanguage = primaryLanguage.equalsIgnoreCase(secondaryLanguage) ? null : secondaryLanguage;
        this.translator = translator;
        this.worker = worker;
        this.changed = changed;
        this.failed = failed;
        this.clock = clock;
        status = new byte[source.size()];
        attempts = new byte[source.size()];
        retryAt = new long[source.size()];

        long previousEnd = -1;
        for (StableCaptionBlockPolicy.Block cue : source) {
            if (cue.start < previousEnd || cue.end <= cue.start) {
                throw new IllegalArgumentException("Invalid source clock");
            }
            previousEnd = cue.end;
        }
    }

    synchronized long playbackTime() {
        return position;
    }

    synchronized boolean isActive() {
        return active;
    }

    synchronized void invalidate() {
        active = false;
        ready.clear();
        displayed = null;
    }

    void demand(long time) {
        demand(time, 1);
    }

    void demand(long time, float speed) {
        synchronized (this) {
            if (!active) return;

            // A user seek may recover transient failures. Ordinary frames never retry them.
            if (time < position - SEEK_BACK_THRESHOLD_MS) {
                for (int i = 0; i < status.length; i++) {
                    if (status[i] == STATUS_FAILED) {
                        status[i] = STATUS_PENDING;
                        attempts[i] = 0;
                        retryAt[i] = 0;
                    }
                }
            }
            position = time;
            playbackRate = Float.isFinite(speed) && speed > 0 ? speed : 1;

            if (running || !needsTranslation()) return;
            running = true;
        }
        worker.execute(this::pump);
    }

    private boolean needsTranslation() {
        return !primaryLanguage.equalsIgnoreCase(sourceLanguage)
                || (secondaryLanguage != null && !secondaryLanguage.equalsIgnoreCase(sourceLanguage));
    }

    /**
     * @return The cues to translate next, marked as requested. Empty if there are none.
     */
    private List<Integer> nextBatch() {
        List<Integer> batch = new ArrayList<>();
        int first = 0;
        while (first < source.size() && source.get(first).end <= position) first++;

        final long prefetchEnd = position + (long) (PREFETCH_MS * Math.min(MAX_PREFETCH_SPEED, Math.max(1, playbackRate)));
        // Never prefetch more blocks than can remain cached, or evictions requeue completed work.
        final int limit = Math.min(source.size(), first + CACHE_CAPACITY);
        for (int i = first; i < limit && batch.size() < CaptionBlockTranslator.MAX_BATCH_TEXTS; i++) {
            StableCaptionBlockPolicy.Block cue = source.get(i);
            if (cue.start > prefetchEnd) break;

            if (cue.end <= position
                    || status[i] == STATUS_REQUESTED
                    || (status[i] == STATUS_READY && ready.get(i) != null)
                    || (status[i] == STATUS_FAILED
                    && (attempts[i] >= MAX_ATTEMPTS || clock.getAsLong() < retryAt[i]))) {
                continue;
            }

            // Planning fit hints must not suppress valid native text translations.
            if (cue.text.codePointCount(0, cue.text.length()) > StableCaptionBlockPolicy.MAX_BLOCK_CHARS) {
                status[i] = STATUS_FAILED;
                attempts[i] = MAX_ATTEMPTS;
                continue;
            }

            status[i] = STATUS_REQUESTED;
            batch.add(i);
        }
        return batch;
    }

    private List<String> translated(@Nullable String target, List<Integer> batch, int slot) throws IOException {
        if (target == null) return Collections.nCopies(batch.size(), "");

        List<String> result = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (int index : batch) {
            String value;
            synchronized (this) {
                String[] cached = ready.get(index);
                value = sourceLanguage.equalsIgnoreCase(target)
                        ? source.get(index).text
                        : cached == null ? null : cached[slot];
            }
            result.add(value);
            if (value == null) missing.add(source.get(index).text);
        }
        if (missing.isEmpty()) return result;

        List<String> translated = translator.translate(sourceLanguage, target, missing, this::isActive);
        if (translated == null || translated.size() != missing.size()) {
            throw new CaptionBlockTranslator.Failure("Incomplete caption batch", 0, false);
        }
        int next = 0;
        for (int i = 0; i < result.size(); i++) {
            if (result.get(i) == null) {
                String value = translated.get(next++);
                result.set(i, value == null || value.isBlank() ? null : value);
            }
        }
        return result;
    }

    /**
     * Translates one language of a batch and stores the result.
     *
     * @return Minimum time to wait before retrying the batch.
     */
    private long translateSlot(List<Integer> batch, int slot) {
        List<String> output = null;
        long retryDelay = 0;
        try {
            output = translated(slot == PRIMARY_SLOT ? primaryLanguage : secondaryLanguage, batch, slot);
        } catch (CaptionBlockTranslator.Failure failure) {
            Logger.printInfo(() -> "Caption translation unavailable", failure);
            retryDelay = failure.retryAfterMs;
            if (!failure.retryable) {
                synchronized (this) {
                    for (int index : batch) {
                        String[] values = ready.computeIfAbsent(index, ignored -> new String[SLOT_COUNT]);
                        if (values[slot] == null) values[slot] = FAILED_OUTPUT;
                    }
                }
            }
        } catch (CancellationException ex) {
            Logger.printDebug(() -> "Caption translation cancelled");
        } catch (IOException ex) {
            Logger.printInfo(() -> "Caption translation failed", ex);
        } catch (RuntimeException ex) {
            Logger.printException(() -> "translateSlot failure", ex);
        }

        if (output != null) {
            synchronized (this) {
                for (int i = 0; i < batch.size(); i++) {
                    if (output.get(i) != null) {
                        ready.computeIfAbsent(batch.get(i), ignored -> new String[SLOT_COUNT])[slot] = output.get(i);
                    }
                }
            }
        }
        return retryDelay;
    }

    /**
     * Updates the status of the cues of a translated batch.
     *
     * @return If this is the first failure of the session.
     */
    private synchronized boolean finishBatch(List<Integer> batch, long retryDelay) {
        boolean error = false;
        for (int index : batch) {
            String[] values = ready.get(index);
            final boolean missing = values == null || values[PRIMARY_SLOT] == null
                    || (secondaryLanguage != null && values[SECONDARY_SLOT] == null);
            final boolean failedOutput = values != null && (FAILED_OUTPUT.equals(values[PRIMARY_SLOT])
                    || (secondaryLanguage != null && FAILED_OUTPUT.equals(values[SECONDARY_SLOT])));
            if (missing) {
                attempts[index]++;
                retryAt[index] = clock.getAsLong() + Math.max(retryDelay, RETRY_BASE_MS << (attempts[index] - 1));
            } else {
                attempts[index] = failedOutput ? MAX_ATTEMPTS : 0;
            }
            status[index] = missing || failedOutput ? STATUS_FAILED : STATUS_READY;
            error |= missing || failedOutput;
        }

        final boolean firstFailure = error && !failureReported;
        failureReported |= error;
        return firstFailure;
    }

    private void pump() {
        while (true) {
            List<Integer> batch;
            synchronized (this) {
                batch = active ? nextBatch() : List.of();
                if (batch.isEmpty()) {
                    running = false;
                    return;
                }
            }

            long retryDelay = 0;
            for (int slot = 0; slot < SLOT_COUNT; slot++) {
                retryDelay = Math.max(retryDelay, translateSlot(batch, slot));
                if (!isActive()) return;

                // One slow target must not hold an already translated language off screen.
                changed.run();
                if (!isActive()) return;
            }

            if (finishBatch(batch, retryDelay)) failed.run();
        }
    }

    @Nullable
    synchronized Snapshot snapshot(long time) {
        return snapshot(time, false);
    }

    /**
     * @return The cue to show at a time. Null if no cue is shown.
     */
    @Nullable
    synchronized Snapshot snapshot(long time, boolean paused) {
        if (!active) return null;

        int found = indexOfCue(time);
        if (found < 0) {
            lastIndex = -1;
            displayed = null;
            lastDisplayTime = time;
            return null;
        }

        StableCaptionBlockPolicy.Block cue = source.get(found);
        String[] output = ready.get(found);
        String first = primaryLanguage.equalsIgnoreCase(sourceLanguage)
                ? cue.text
                : output == null ? null : output[PRIMARY_SLOT];
        String second;
        if (secondaryLanguage == null) {
            second = "";
        } else if (secondaryLanguage.equalsIgnoreCase(sourceLanguage)) {
            second = cue.text;
        } else {
            second = output == null ? null : output[SECONDARY_SLOT];
        }
        if (first == null) first = translator.cached(sourceLanguage, primaryLanguage, cue.text);
        if (second == null && secondaryLanguage != null)
            second = translator.cached(sourceLanguage, secondaryLanguage, cue.text);
        boolean unavailable = FAILED_OUTPUT.equals(first) || (first == null && attempts[found] > 0);
        if (FAILED_OUTPUT.equals(first)) first = null;
        if (secondaryLanguage != null && FAILED_OUTPUT.equals(second)) second = null;

        // Do not briefly show another language while the requested translation is pending.
        Snapshot candidate = new Snapshot(cue, first == null ? (unavailable ? cue.text : "") : first,
                second == null ? "" : second, first != null && second != null);

        // Text does not change while it is being read, unless enough of the cue remains.
        final boolean seekBack = lastDisplayTime != Long.MIN_VALUE && time < lastDisplayTime - SEEK_BACK_THRESHOLD_MS;
        if (found != lastIndex || displayed == null || seekBack) {
            displayed = candidate;
        } else if (!displayed.complete
                && (paused || cue.end - time >= StableCaptionBlockPolicy.MIN_DURATION_MS * (double) playbackRate)
                && (!candidate.primary.equals(displayed.primary) || !candidate.secondary.equals(displayed.secondary))) {
            displayed = candidate;
        }
        lastIndex = found;
        lastDisplayTime = time;
        return displayed;
    }

    private int indexOfCue(long time) {
        int low = 0, high = source.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            StableCaptionBlockPolicy.Block cue = source.get(mid);
            if (time < cue.start) high = mid - 1;
            else if (time >= cue.end) low = mid + 1;
            else return mid;
        }
        return -1;
    }
}
