/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4896
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.notifications;

import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.settings.preference.SharedPrefCategory;

/**
 * Video ids with the time they were added, kept in their own preferences file.
 * Once there are more than the maximum, the oldest are removed down to 90% of it,
 * and none are kept longer than the maximum age.
 */
final class TimedVideoIds {

    private final SharedPreferences prefs;
    private final Map<String, Long> times = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final long maxAgeMillis;

    TimedVideoIds(String prefsName, int maxEntries, long maxAgeMillis) {
        prefs = new SharedPrefCategory(prefsName).preferences;
        this.maxEntries = maxEntries;
        this.maxAgeMillis = maxAgeMillis;
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (entry.getValue() instanceof Long) {
                times.put(entry.getKey(), (Long) entry.getValue());
            }
        }
        trim(System.currentTimeMillis());
    }

    boolean contains(String videoId) {
        Long time = times.get(videoId);
        return time != null && System.currentTimeMillis() - time <= maxAgeMillis;
    }

    int size() {
        return times.size();
    }

    void add(String videoId) {
        addAll(List.of(videoId));
    }

    synchronized void addAll(Collection<String> videoIds) {
        long now = System.currentTimeMillis();
        boolean added = false;
        SharedPreferences.Editor editor = prefs.edit();
        for (String videoId : videoIds) {
            if (videoId != null && !videoId.isEmpty() && !contains(videoId)) {
                times.put(videoId, now);
                editor.putLong(videoId, now);
                added = true;
            }
        }
        if (added) {
            editor.apply();
            if (times.size() > maxEntries) {
                trim(now);
            }
        }
    }

    synchronized void removeAll(Collection<String> videoIds) {
        SharedPreferences.Editor editor = prefs.edit();
        boolean removed = false;
        for (String videoId : videoIds) {
            if (times.remove(videoId) != null) {
                editor.remove(videoId);
                removed = true;
            }
        }
        if (removed) {
            editor.apply();
        }
    }

    private synchronized void trim(long now) {
        List<Map.Entry<String, Long>> entries = new ArrayList<>(times.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        int keptEntries = entries.size() > maxEntries ? maxEntries * 9 / 10 : entries.size();
        List<String> removed = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            Map.Entry<String, Long> entry = entries.get(i);
            if (i >= keptEntries || now - entry.getValue() > maxAgeMillis) {
                removed.add(entry.getKey());
            }
        }
        removeAll(removed);
    }
}
