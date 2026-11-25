/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import android.text.format.Formatter;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Watch history of the Ghost mode videos, saved only on the device.
 */
public final class GhostHistoryStore {

    private static final GhostJsonFile file = new GhostJsonFile("morphe_ghost_history.json");

    public record Entry(
            String videoId,
            String title,
            String channelName,
            long positionMs,
            long durationMs,
            long watchedAtMs
    ) {
        public int progressPercent() {
            if (durationMs <= 0L || positionMs <= 0L) return 0;
            return (int) Math.min(100L, (positionMs * 100L) / durationMs);
        }

        public boolean matchesFilter(String lowerQuery) {
            if (lowerQuery.isEmpty()) return true;
            return title.toLowerCase(Locale.ROOT).contains(lowerQuery)
                    || channelName.toLowerCase(Locale.ROOT).contains(lowerQuery)
                    || videoId.toLowerCase(Locale.ROOT).contains(lowerQuery);
        }
    }

    @Nullable
    private static List<Entry> cachedEntries;

    private GhostHistoryStore() {
    }

    public static synchronized List<Entry> getAll() {
        ensureLoaded();
        return new ArrayList<>(cachedEntries);
    }

    public static synchronized int count() {
        ensureLoaded();
        return cachedEntries.size();
    }

    /**
     * @return The size of the saved history, formatted for display.
     */
    public static synchronized String getFormattedSize() {
        ensureLoaded();
        long bytes = 0;
        if (!cachedEntries.isEmpty()) {
            try {
                bytes = toJson(cachedEntries).toString().getBytes(StandardCharsets.UTF_8).length;
            } catch (JSONException ex) {
                Logger.printException(() -> "getFormattedSize failure", ex);
            }
        }
        return Formatter.formatShortFileSize(Utils.getContext(), bytes);
    }

    public static synchronized void record(
            String videoId,
            @Nullable String title,
            @Nullable String channelName,
            long positionMs,
            long durationMs
    ) {
        if (videoId.isEmpty()) return;
        ensureLoaded();
        List<Entry> entries = new ArrayList<>(cachedEntries);
        String existingTitle = "";
        String existingChannel = "";
        long existingDuration = 0L;

        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.videoId().equals(videoId)) {
                existingTitle = e.title();
                existingChannel = e.channelName();
                existingDuration = e.durationMs();
                entries.remove(i);
                break;
            }
        }

        String finalTitle = (title != null && !title.isEmpty()) ? title : existingTitle;
        String finalChannel = (channelName != null && !channelName.isEmpty()) ? channelName : existingChannel;
        long finalDuration = durationMs > 0L ? durationMs : existingDuration;

        entries.add(0, new Entry(
                videoId,
                finalTitle,
                finalChannel,
                Math.max(0L, positionMs),
                Math.max(0L, finalDuration),
                System.currentTimeMillis()
        ));

        trimToLimit(entries, Settings.GHOST_PLAY_HISTORY_LIMIT.get());
        save(entries);
    }

    public static synchronized void remove(String videoId) {
        ensureLoaded();
        List<Entry> entries = new ArrayList<>(cachedEntries);
        if (entries.removeIf(e -> e.videoId().equals(videoId))) {
            save(entries);
        }
    }

    public static synchronized void enforceLimit(int maxEntries) {
        ensureLoaded();
        List<Entry> entries = new ArrayList<>(cachedEntries);
        int before = entries.size();
        trimToLimit(entries, maxEntries);
        if (entries.size() != before) {
            save(entries);
        }
    }

    public static synchronized void clear() {
        save(new ArrayList<>());
    }

    /**
     * @param maxEntries Maximum number of entries, or 0 for no limit.
     */
    static void trimToLimit(List<Entry> entries, int maxEntries) {
        if (maxEntries <= 0) {
            return;
        }
        while (entries.size() > maxEntries) {
            entries.remove(entries.size() - 1);
        }
    }

    private static void ensureLoaded() {
        if (cachedEntries == null) {
            cachedEntries = fromJson(file.read());
        }
    }

    private static void save(List<Entry> entries) {
        cachedEntries = entries;
        try {
            file.writeAsync(toJson(entries));
        } catch (JSONException ex) {
            Logger.printException(() -> "GhostHistoryStore save failure", ex);
        }
    }

    private static JSONArray toJson(List<Entry> entries) throws JSONException {
        JSONArray array = new JSONArray();
        for (Entry e : entries) {
            array.put(new JSONObject()
                    .put("v", e.videoId())
                    .put("t", e.title())
                    .put("c", e.channelName())
                    .put("p", e.positionMs())
                    .put("d", e.durationMs())
                    .put("w", e.watchedAtMs()));
        }
        return array;
    }

    private static List<Entry> fromJson(JSONArray array) {
        List<Entry> entries = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String videoId = obj.optString("v");
            if (videoId.isEmpty()) continue;
            entries.add(new Entry(
                    videoId,
                    obj.optString("t"),
                    obj.optString("c"),
                    obj.optLong("p"),
                    obj.optLong("d"),
                    obj.optLong("w")
            ));
        }
        return entries;
    }
}
