/* Copyright 2026 Morphe. See NOTICE for GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.LongSupplier;

/** One source clock. Translation delivery and display geometry never choose new cue times. */
final class CaptionSession {
    interface Translator {
        List<String> translate(String source, String target, List<String> texts) throws IOException;
    }
    static final class Snapshot {
        final long start, end;
        final String primary, secondary;
        final boolean complete;
        Snapshot(StableCaptionBlockPolicy.Block cue, String primary, String secondary, boolean complete) {
            start = cue.start; end = cue.end;
            this.primary = primary; this.secondary = secondary; this.complete = complete;
        }
    }
    private static final int CACHE_CAPACITY = 64;

    private static final class Worker {
        static final Executor INSTANCE = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(1), task -> {
                    Thread thread = new Thread(task, "MorpheCaptionBlocks");
                    thread.setDaemon(true); return thread;
                }, new ThreadPoolExecutor.DiscardOldestPolicy());
    }
    static Executor worker() { return Worker.INSTANCE; }

    final String sourceLanguage, primaryLanguage, secondaryLanguage;
    final List<StableCaptionBlockPolicy.Block> source;
    private final Translator translator;
    private final Executor worker;
    private final Runnable changed, failed;
    private final byte[] status, attempts;
    private final long[] retryAt;
    private final LongSupplier clock;
    private float playbackRate = 1;
    private final Map<Integer, String[]> ready = new LinkedHashMap<Integer, String[]>(CACHE_CAPACITY, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, String[]> entry) { return size() > CACHE_CAPACITY; }
    };
    private boolean active = true, running, failureReported;
    private long position, lastDisplayTime = Long.MIN_VALUE;
    private int lastIndex = -1;
    private Snapshot displayed;

    CaptionSession(List<StableCaptionBlockPolicy.Block> source, String sourceLanguage,
            String primaryLanguage, String secondaryLanguage, Translator translator, Executor worker,
            Runnable changed, Runnable failed) {
        this(source, sourceLanguage, primaryLanguage, secondaryLanguage, translator, worker,
                changed, failed, () -> System.nanoTime() / 1_000_000L);
    }
    CaptionSession(List<StableCaptionBlockPolicy.Block> source, String sourceLanguage,
            String primaryLanguage, String secondaryLanguage, Translator translator, Executor worker,
            Runnable changed, Runnable failed, LongSupplier clock) {
        this.source = List.copyOf(source);
        this.sourceLanguage = Objects.requireNonNull(sourceLanguage);
        this.primaryLanguage = Objects.requireNonNull(primaryLanguage);
        this.secondaryLanguage = primaryLanguage.equalsIgnoreCase(secondaryLanguage) ? null : secondaryLanguage;
        this.translator = translator; this.worker = worker; this.changed = changed; this.failed = failed;
        status = new byte[source.size()]; attempts = new byte[source.size()];
        retryAt = new long[source.size()]; this.clock = clock;
        long previousEnd = -1;
        for (var cue : source) {
            if (cue.start < previousEnd || cue.end <= cue.start) throw new IllegalArgumentException("Invalid source clock");
            previousEnd = cue.end;
        }
    }
    synchronized long playbackTime() { return position; }
    synchronized boolean isActive() { return active; }
    synchronized void invalidate() { active = false; ready.clear(); displayed = null; }
    void demand(long time) { demand(time, 1); }
    void demand(long time, float speed) {
        synchronized (this) {
            if (!active) return;
            // A user seek may recover transient failures; ordinary frames never retry them.
            if (time < position - 250)
                for (int i = 0; i < status.length; i++) if (status[i] == 3) { status[i] = attempts[i] = 0; retryAt[i] = 0; }
            position = time;
            playbackRate = Float.isFinite(speed) && speed > 0 ? speed : 1;
            if (running || !needsTranslation()) return;
            running = true;
        }
        worker.execute(this::pump);
    }
    private boolean needsTranslation() {
        return !primaryLanguage.equalsIgnoreCase(sourceLanguage)
                || secondaryLanguage != null && !secondaryLanguage.equalsIgnoreCase(sourceLanguage);
    }
    private List<Integer> nextBatch() {
        List<Integer> batch = new ArrayList<>();
        int first = 0;
        while (first < source.size() && source.get(first).end <= position) first++;
        // Never prefetch more blocks than can remain cached, or evictions requeue completed work.
        int limit = Math.min(source.size(), first + CACHE_CAPACITY);
        for (int i = first; i < limit && batch.size() < 5; i++) {
            var cue = source.get(i);
            if (cue.start > position + (long) (30000 * Math.min(4, Math.max(1, playbackRate)))) break;
            if (cue.end <= position || status[i] == 1
                    || status[i] == 3 && (attempts[i] >= 3 || clock.getAsLong() < retryAt[i])) continue;
            if (status[i] == 2 && ready.get(i) != null) continue;
            // Planning fit hints must not suppress valid native text translations.
            if (cue.text.codePointCount(0, cue.text.length()) > StableCaptionBlockPolicy.MAX_BLOCK_CHARS) {
                status[i] = attempts[i] = 3; continue;
            }
            status[i] = 1; batch.add(i);
        }
        return batch;
    }
    private List<String> translated(String target, List<Integer> batch, int slot) throws IOException {
        if (target == null) return Collections.nCopies(batch.size(), "");
        List<String> result = new ArrayList<>(), missing = new ArrayList<>();
        for (int index : batch) {
            String value;
            synchronized (this) {
                String[] cached = ready.get(index);
                value = sourceLanguage.equalsIgnoreCase(target) ? source.get(index).text
                        : cached == null ? null : cached[slot];
            }
            result.add(value);
            if (value == null) missing.add(source.get(index).text);
        }
        if (missing.isEmpty()) return result;
        List<String> translated = translator.translate(sourceLanguage, target, missing);
        if (translated == null || translated.size() != missing.size())
            throw new CaptionBlockTranslator.Failure("Incomplete caption batch", 0, false);
        int next = 0;
        for (int i = 0; i < result.size(); i++) if (result.get(i) == null) {
            String value = translated.get(next++);
            result.set(i, value == null || value.isBlank() ? null : value);
        }
        return result;
    }
    private void pump() {
        for (;;) {
            List<Integer> batch;
            synchronized (this) {
                if (!active) { running = false; return; }
                batch = nextBatch();
                if (batch.isEmpty()) { running = false; return; }
            }
            long delay = 0;
            for (int slot = 0; slot < 2; slot++) {
                List<String> output = null;
                try {
                    output = translated(slot == 0 ? primaryLanguage : secondaryLanguage, batch, slot);
                } catch (IOException | RuntimeException failure) {
                    if (failure instanceof CaptionBlockTranslator.Failure typed) {
                        if (!typed.retryable) synchronized (this) {
                            if (!active) { running = false; return; }
                            for (int index : batch) {
                                String[] values = ready.computeIfAbsent(index, ignored -> new String[2]);
                                if (values[slot] == null) values[slot] = "";
                            }
                        }
                        delay = Math.max(delay, typed.retryAfterMs);
                    }
                }
                synchronized (this) {
                    if (!active) { running = false; return; }
                    if (output != null) for (int i = 0; i < batch.size(); i++) {
                        String[] values = ready.computeIfAbsent(batch.get(i), ignored -> new String[2]);
                        if (output.get(i) != null) values[slot] = output.get(i);
                    }
                }
                // One slow target must not hold an already translated language off screen.
                changed.run();
                synchronized (this) { if (!active) { running = false; return; } }
            }
            boolean error = false, notifyFailure;
            synchronized (this) {
                for (int index : batch) {
                    String[] values = ready.get(index);
                    boolean missing = values == null || values[0] == null
                            || secondaryLanguage != null && values[1] == null;
                    boolean failedOutput = values != null && ("".equals(values[0])
                            || secondaryLanguage != null && "".equals(values[1]));
                    if (missing) {
                        attempts[index]++;
                        retryAt[index] = clock.getAsLong() + Math.max(delay, 1000L << (attempts[index] - 1));
                    } else attempts[index] = (byte) (failedOutput ? 3 : 0);
                    status[index] = (byte) (missing || failedOutput ? 3 : 2);
                    error |= missing || failedOutput;
                }
                notifyFailure = error && !failureReported;
                failureReported |= error;
            }
            if (notifyFailure) failed.run();
        }
    }
    synchronized Snapshot snapshot(long time) { return snapshot(time, false); }
    synchronized Snapshot snapshot(long time, boolean paused) {
        if (!active) return null;
        int low = 0, high = source.size() - 1, found = -1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            var cue = source.get(mid);
            if (time < cue.start) high = mid - 1;
            else if (time >= cue.end) low = mid + 1;
            else { found = mid; break; }
        }
        if (found < 0) { lastIndex = -1; displayed = null; lastDisplayTime = time; return null; }
        var cue = source.get(found);
        String[] output = ready.get(found);
        String first = primaryLanguage.equalsIgnoreCase(sourceLanguage) ? cue.text : output == null ? null : output[0];
        String second = secondaryLanguage == null ? "" : secondaryLanguage.equalsIgnoreCase(sourceLanguage) ? cue.text : output == null ? null : output[1];
        if (first != null && first.isEmpty()) first = null;
        if (secondaryLanguage != null && second != null && second.isEmpty()) second = null;
        boolean complete = first != null && second != null;
        Snapshot candidate = new Snapshot(cue, first == null ? cue.text : first, second == null ? "" : second, complete);
        boolean seekBack = lastDisplayTime != Long.MIN_VALUE && time < lastDisplayTime - 250;
        if (found != lastIndex || displayed == null || seekBack) displayed = candidate;
        else if (!displayed.complete && (paused || cue.end - time >= StableCaptionBlockPolicy.MIN_DURATION_MS * (double) playbackRate)
                && (!candidate.primary.equals(displayed.primary) || !candidate.secondary.equals(displayed.secondary)))
            displayed = candidate;
        lastIndex = found; lastDisplayTime = time;
        return displayed;
    }
}
