/* Copyright 2026 Morphe. See NOTICE for GPLv3 Section 7 terms. */
package app.morphe.extension.youtube.patches;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

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
    private final byte[] status;
    private final Map<Integer, String[]> ready = new LinkedHashMap<Integer, String[]>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, String[]> entry) { return size() > 64; }
    };
    private boolean active = true, running, failureReported;
    private long position, lastDisplayTime = Long.MIN_VALUE;
    private int lastIndex = -1;
    private Snapshot displayed;

    CaptionSession(List<StableCaptionBlockPolicy.Block> source, String sourceLanguage,
            String primaryLanguage, String secondaryLanguage, Translator translator, Executor worker,
            Runnable changed, Runnable failed) {
        this.source = List.copyOf(source);
        this.sourceLanguage = Objects.requireNonNull(sourceLanguage);
        this.primaryLanguage = Objects.requireNonNull(primaryLanguage);
        this.secondaryLanguage = primaryLanguage.equalsIgnoreCase(secondaryLanguage) ? null : secondaryLanguage;
        this.translator = translator; this.worker = worker; this.changed = changed; this.failed = failed;
        status = new byte[source.size()];
        long previousEnd = -1;
        for (var cue : source) {
            if (cue.start < previousEnd || cue.end <= cue.start) throw new IllegalArgumentException("Invalid source clock");
            previousEnd = cue.end;
        }
    }
    synchronized boolean isActive() { return active; }
    synchronized void invalidate() { active = false; ready.clear(); displayed = null; }
    void demand(long time) {
        synchronized (this) {
            if (!active) return;
            position = time;
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
        for (int i = 0; i < source.size() && batch.size() < 5; i++) {
            var cue = source.get(i);
            if (cue.start > position + 30000) break;
            if (cue.end <= position || status[i] == 1 || status[i] == 3) continue;
            if (status[i] == 2 && ready.containsKey(i)) continue;
            if (!cue.fits || cue.end - cue.start < StableCaptionBlockPolicy.MIN_DURATION_MS) {
                status[i] = 3; continue;
            }
            status[i] = 1; batch.add(i);
        }
        return batch;
    }
    private List<String> translated(String target, List<String> texts) throws IOException {
        if (target == null) return null;
        if (sourceLanguage.equalsIgnoreCase(target)) return texts;
        List<String> result = translator.translate(sourceLanguage, target, texts);
        if (result == null || result.size() != texts.size()) throw new IOException("Incomplete caption batch");
        for (String text : result) if (text == null || text.isBlank()) throw new IOException("Empty caption result");
        return List.copyOf(result);
    }
    private void pump() {
        for (;;) {
            List<Integer> batch;
            synchronized (this) {
                if (!active) { running = false; return; }
                batch = nextBatch();
                if (batch.isEmpty()) { running = false; return; }
            }
            List<String> texts = new ArrayList<>();
            for (int index : batch) texts.add(source.get(index).text);
            List<String> first = null, second = null;
            boolean error = false;
            try {
                first = translated(primaryLanguage, texts);
            } catch (IOException | RuntimeException failure) { error = true; }
            synchronized (this) { if (!active) { running = false; return; } }
            try {
                second = translated(secondaryLanguage, texts);
            } catch (IOException | RuntimeException failure) { error = true; }
            boolean notifyFailure;
            synchronized (this) {
                if (!active) { running = false; return; }
                for (int i = 0; i < batch.size(); i++) {
                    int index = batch.get(i);
                    ready.put(index, new String[] {first == null ? null : first.get(i), second == null ? null : second.get(i)});
                    status[index] = (byte) (error ? 3 : 2);
                }
                notifyFailure = error && !failureReported;
                failureReported |= error;
            }
            changed.run();
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
        boolean complete = first != null && second != null;
        Snapshot candidate = new Snapshot(cue, first == null ? cue.text : first, second == null ? "" : second, complete);
        boolean seekBack = lastDisplayTime != Long.MIN_VALUE && time < lastDisplayTime - 250;
        if (found != lastIndex || displayed == null || seekBack) displayed = candidate;
        else if (!displayed.complete && (paused || cue.end - time >= StableCaptionBlockPolicy.MIN_DURATION_MS)
                && (!candidate.primary.equals(displayed.primary) || !candidate.secondary.equals(displayed.secondary)))
            displayed = candidate;
        lastIndex = found; lastDisplayTime = time;
        return displayed;
    }
}
