/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import android.content.Context;
import android.util.AtomicFile;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * On-device watch history for Ghost Play videos.
 * Stored in app noBackupFilesDir (excluded from Android Auto Backup) using serialized AtomicFile writes.
 */
public final class GhostHistoryStore {

    public static final int DEFAULT_MAX_ENTRIES = 500;
    private static final String FILE_NAME = "morphe_ghost_history.json";
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public record Entry(
            @NonNull String videoId,
            @NonNull String title,
            @NonNull String channelName,
            long positionMs,
            long durationMs,
            long watchedAtMs
    ) {
        public int progressPercent() {
            if (durationMs <= 0L || positionMs <= 0L) return 0;
            return (int) Math.min(100L, (positionMs * 100L) / durationMs);
        }

        public boolean matchesFilter(@NonNull String lowerQuery) {
            if (lowerQuery.isEmpty()) return true;
            return title.toLowerCase(Locale.ROOT).contains(lowerQuery)
                    || channelName.toLowerCase(Locale.ROOT).contains(lowerQuery)
                    || videoId.toLowerCase(Locale.ROOT).contains(lowerQuery);
        }
    }

    @Nullable
    private static volatile List<Entry> cachedEntries;

    private GhostHistoryStore() {
    }

    public static int getConfiguredLimit() {
        try {
            return parseLimit(Settings.GHOST_PLAY_HISTORY_LIMIT.get());
        } catch (Exception ignored) {
            return DEFAULT_MAX_ENTRIES;
        }
    }

    public static int parseLimit(@Nullable String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return DEFAULT_MAX_ENTRIES;
        }
        try {
            return Math.max(0, Integer.parseInt(raw.trim())); // 0 means unlimited.
        } catch (NumberFormatException ex) {
            return DEFAULT_MAX_ENTRIES;
        }
    }

    @NonNull
    public static synchronized List<Entry> getAll() {
        ensureLoaded();
        return new ArrayList<>(cachedEntries);
    }

    public static synchronized int count() {
        ensureLoaded();
        return cachedEntries.size();
    }

    public static synchronized long getApproxSizeBytes() {
        ensureLoaded();
        File file = getStorageFile();
        if (file != null && file.isFile()) {
            return file.length();
        }
        if (cachedEntries.isEmpty()) {
            return 0L;
        }
        return serializeJson(cachedEntries).getBytes(StandardCharsets.UTF_8).length;
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double kb = bytes / 1024.0;
        if (kb < 1024.0) {
            return String.format(Locale.US, "%.1f KB", kb);
        }
        return String.format(Locale.US, "%.2f MB", kb / 1024.0);
    }

    public static synchronized void record(
            @NonNull String videoId,
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

        trimToLimit(entries, getConfiguredLimit());
        cachedEntries = entries;
        persistAsync(List.copyOf(entries));
    }

    public static synchronized void remove(@NonNull String videoId) {
        ensureLoaded();
        List<Entry> entries = new ArrayList<>(cachedEntries);
        if (entries.removeIf(e -> e.videoId().equals(videoId))) {
            cachedEntries = entries;
            persistAsync(List.copyOf(entries));
        }
    }

    public static synchronized void enforceLimit(int maxEntries) {
        ensureLoaded();
        List<Entry> entries = new ArrayList<>(cachedEntries);
        int before = entries.size();
        trimToLimit(entries, maxEntries);
        if (entries.size() != before) {
            cachedEntries = entries;
            persistAsync(List.copyOf(entries));
        }
    }

    public static synchronized void clear() {
        cachedEntries = new ArrayList<>();
        persistAsync(List.of());
    }

    static void trimToLimit(@NonNull List<Entry> entries, int maxEntries) {
        if (maxEntries <= 0) {
            return;
        }
        while (entries.size() > maxEntries) {
            entries.remove(entries.size() - 1);
        }
    }

    private static void ensureLoaded() {
        if (cachedEntries != null) {
            return;
        }
        List<Entry> loaded = new ArrayList<>();
        try {
            File file = getStorageFile();
            if (file != null && file.isFile()) {
                byte[] bytes = new AtomicFile(file).readFully();
                loaded = deserializeJson(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "GhostHistoryStore.ensureLoaded failure", ex);
        }
        cachedEntries = loaded;
    }

    private static void persistAsync(@NonNull List<Entry> snapshot) {
        ioExecutor.execute(() -> {
            File file = getStorageFile();
            if (file == null) return;
            AtomicFile atomic = new AtomicFile(file);
            if (snapshot.isEmpty()) {
                atomic.delete();
                return;
            }
            byte[] data = serializeJson(snapshot).getBytes(StandardCharsets.UTF_8);
            FileOutputStream fos = null;
            try {
                fos = atomic.startWrite();
                fos.write(data);
                atomic.finishWrite(fos);
            } catch (Exception ex) {
                if (fos != null) {
                    atomic.failWrite(fos);
                }
                Logger.printException(() -> "Failed to persist GhostHistoryStore", ex);
            }
        });
    }

    @Nullable
    private static File getStorageFile() {
        Context ctx = Utils.getContext();
        if (ctx == null) return null;
        return new File(ctx.getNoBackupFilesDir(), FILE_NAME);
    }

    static String serializeJson(@NonNull List<Entry> entries) {
        if (entries.isEmpty()) return "[]";
        JSONArray arr = new JSONArray();
        for (Entry e : entries) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("v", e.videoId());
                obj.put("t", e.title());
                obj.put("c", e.channelName());
                obj.put("p", e.positionMs());
                obj.put("d", e.durationMs());
                obj.put("w", e.watchedAtMs());
                arr.put(obj);
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }

    @NonNull
    static List<Entry> deserializeJson(@Nullable String raw) {
        List<Entry> result = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return result;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String videoId = obj.optString("v", "");
                if (videoId.isEmpty()) continue;
                result.add(new Entry(
                        videoId,
                        obj.optString("t", ""),
                        obj.optString("c", ""),
                        obj.optLong("p", 0L),
                        obj.optLong("d", 0L),
                        obj.optLong("w", 0L)
                ));
            }
        } catch (Exception ignored) {
        }
        return result;
    }
}
