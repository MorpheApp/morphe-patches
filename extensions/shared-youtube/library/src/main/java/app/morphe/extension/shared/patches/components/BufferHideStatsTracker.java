/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches.components;

import androidx.annotation.GuardedBy;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.components.BufferPhraseFilter.Source;
import app.morphe.extension.shared.settings.StringSetting;

/**
 * Per-filter 24h hide tracker persisted as a JSON dict in a caller-supplied
 * {@link StringSetting}. Format: {@code {"videoId":{"t":timestampMs,"s":sourceOrdinal}}}.
 * <p>
 * Loads lazily on first use, purges entries older than the 24h horizon on each recorded hide,
 * and caps map size to avoid unbounded growth if video ID extraction misfires.
 */
public final class BufferHideStatsTracker {

    /** Sliding-window horizon for the "last 24 hours" stat. */
    private static final long WINDOW_MS = 24 * 60 * 60 * 1000L;

    /** Safety cap on the map size. */
    private static final int MAX_TRACKED_VIDEOS = 2000;

    private final StringSetting storage;

    @GuardedBy("this")
    private final HashMap<String, Entry> data = new HashMap<>();

    @GuardedBy("this")
    private boolean loaded;

    private record Entry(long timestamp, int sourceOrdinal) {}

    public BufferHideStatsTracker(StringSetting storage) {
        this.storage = storage;
    }

    /** @return total hide count across all sources within the last 24 hours. */
    public synchronized int totalSize(long now) {
        loadIfNeeded();
        purgeOlderThan(now - WINDOW_MS);
        return data.size();
    }

    /** @return hide count for a specific source within the last 24 hours. */
    public synchronized int sourceSize(Source source, long now) {
        loadIfNeeded();
        purgeOlderThan(now - WINDOW_MS);
        final int wanted = source.ordinal();
        int count = 0;
        for (Entry e : data.values()) {
            if (e.sourceOrdinal() == wanted) count++;
        }
        return count;
    }

    /** @return true if the video was newly recorded, false if already present. */
    public synchronized boolean recordHide(String videoId, Source source, long now) {
        loadIfNeeded();
        purgeOlderThan(now - WINDOW_MS);

        if (data.containsKey(videoId)) {
            return false;
        }

        data.put(videoId, new Entry(now, source.ordinal()));
        if (data.size() > MAX_TRACKED_VIDEOS) {
            evictEldest();
        }
        save();
        return true;
    }

    public synchronized void reset() {
        data.clear();
        loaded = true;
        storage.resetToDefault();
    }

    private void loadIfNeeded() {
        if (loaded) return;
        loaded = true;
        String raw = storage.get();
        if (raw.isEmpty()) return;
        try {
            JSONObject obj = new JSONObject(raw);
            Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                JSONObject e = obj.getJSONObject(k);
                data.put(k, new Entry(e.getLong("t"), e.getInt("s")));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "24h store for " + storage.key + " is corrupt, resetting", ex);
            data.clear();
            storage.resetToDefault();
        }
    }

    private void purgeOlderThan(long threshold) {
        data.entrySet().removeIf(e -> e.getValue().timestamp() < threshold);
    }

    private void evictEldest() {
        String eldestKey = null;
        long eldestTime = Long.MAX_VALUE;
        for (Map.Entry<String, Entry> e : data.entrySet()) {
            if (e.getValue().timestamp() < eldestTime) {
                eldestTime = e.getValue().timestamp();
                eldestKey = e.getKey();
            }
        }
        if (eldestKey != null) data.remove(eldestKey);
    }

    private void save() {
        try {
            JSONObject obj = new JSONObject();
            for (Map.Entry<String, Entry> e : data.entrySet()) {
                JSONObject entryObj = new JSONObject();
                entryObj.put("t", e.getValue().timestamp());
                entryObj.put("s", e.getValue().sourceOrdinal());
                obj.put(e.getKey(), entryObj);
            }
            storage.save(obj.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to save 24h store for " + storage.key, ex);
        }
    }
}
