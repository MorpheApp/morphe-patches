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

/**
 * On-device store for auto-ghost channels (channel ID, name, handle, avatar, description,
 * and recent video titles). Stored in app noBackupFilesDir using serialized AtomicFile writes.
 */
public final class GhostChannelStore {

    private static final String FILE_NAME = "morphe_ghost_channels.json";
    private static final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    public record ChannelEntry(
            @NonNull String channelId,
            @NonNull String name,
            @NonNull String handle,
            @NonNull String avatarUrl,
            @NonNull String subscriberText,
            @NonNull String description,
            @NonNull List<String> recentVideos,
            long addedAtMs
    ) {
        public boolean matchesFilter(@NonNull String lowerQuery) {
            if (lowerQuery.isEmpty()) return true;
            if (name.toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
            if (handle.toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
            if (description.toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
            for (String v : recentVideos) {
                if (v.toLowerCase(Locale.ROOT).contains(lowerQuery)) return true;
            }
            return false;
        }
    }

    @Nullable
    private static volatile List<ChannelEntry> cachedEntries;

    private GhostChannelStore() {
    }

    @NonNull
    public static synchronized List<ChannelEntry> getAll() {
        ensureLoaded();
        return new ArrayList<>(cachedEntries);
    }

    public static synchronized int count() {
        ensureLoaded();
        return cachedEntries.size();
    }

    public static synchronized boolean contains(@Nullable String channelId, @Nullable String channelName) {
        ensureLoaded();
        return matchesEntries(cachedEntries, channelId, channelName);
    }

    public static synchronized void addOrUpdate(@NonNull ChannelEntry entry) {
        ensureLoaded();
        List<ChannelEntry> list = new ArrayList<>(cachedEntries);
        for (int i = 0; i < list.size(); i++) {
            ChannelEntry existing = list.get(i);
            boolean sameId = !entry.channelId().isEmpty() && entry.channelId().equals(existing.channelId());
            boolean sameName = !entry.name().isEmpty() && entry.name().equalsIgnoreCase(existing.name());
            if (sameId || (entry.channelId().isEmpty() && sameName)) {
                ChannelEntry merged = new ChannelEntry(
                        !entry.channelId().isEmpty() ? entry.channelId() : existing.channelId(),
                        !entry.name().isEmpty() ? entry.name() : existing.name(),
                        !entry.handle().isEmpty() ? entry.handle() : existing.handle(),
                        !entry.avatarUrl().isEmpty() ? entry.avatarUrl() : existing.avatarUrl(),
                        !entry.subscriberText().isEmpty() ? entry.subscriberText() : existing.subscriberText(),
                        !entry.description().isEmpty() ? entry.description() : existing.description(),
                        !entry.recentVideos().isEmpty() ? entry.recentVideos() : existing.recentVideos(),
                        existing.addedAtMs() > 0 ? existing.addedAtMs() : entry.addedAtMs()
                );
                list.set(i, merged);
                cachedEntries = list;
                persistAsync(List.copyOf(list));
                return;
            }
        }
        list.add(0, entry);
        cachedEntries = list;
        persistAsync(List.copyOf(list));
    }

    public static synchronized boolean remove(@Nullable String channelId, @Nullable String channelName) {
        ensureLoaded();
        List<ChannelEntry> list = new ArrayList<>(cachedEntries);
        boolean removed = removeFromList(list, channelId, channelName);
        if (removed) {
            cachedEntries = list;
            persistAsync(List.copyOf(list));
        }
        return removed;
    }

    static boolean removeFromList(
            @NonNull List<ChannelEntry> list,
            @Nullable String channelId,
            @Nullable String channelName
    ) {
        boolean hasId = channelId != null && !channelId.isEmpty();
        if (hasId) {
            return list.removeIf(e -> channelId.equals(e.channelId())
                    || (e.channelId().isEmpty() && channelName != null && channelName.equalsIgnoreCase(e.name())));
        }
        if (channelName != null && !channelName.isEmpty()) {
            return list.removeIf(e -> channelName.equalsIgnoreCase(e.name()));
        }
        return false;
    }

    static boolean matchesEntries(
            @NonNull List<ChannelEntry> entries,
            @Nullable String channelId,
            @Nullable String channelName
    ) {
        if (entries.isEmpty()) return false;
        String normId = channelId == null ? "" : channelId.trim();
        String normName = channelName == null ? "" : channelName.trim().toLowerCase(Locale.ROOT);
        if (normId.isEmpty() && normName.isEmpty()) return false;

        for (ChannelEntry e : entries) {
            if (!normId.isEmpty() && !e.channelId().isEmpty() && normId.equals(e.channelId())) {
                return true;
            }
            if (!normName.isEmpty()) {
                if (!e.name().isEmpty() && normName.equals(e.name().trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
                if (!e.handle().isEmpty() && normName.equals(e.handle().trim().toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void ensureLoaded() {
        if (cachedEntries != null) {
            return;
        }
        List<ChannelEntry> loaded = new ArrayList<>();
        try {
            File file = getStorageFile();
            if (file != null && file.isFile()) {
                byte[] bytes = new AtomicFile(file).readFully();
                loaded = deserializeJson(new String(bytes, StandardCharsets.UTF_8));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to load GhostChannelStore", ex);
        }
        cachedEntries = loaded;
    }

    private static void persistAsync(@NonNull List<ChannelEntry> snapshot) {
        ioExecutor.execute(() -> {
            File file = getStorageFile();
            if (file == null) return;
            AtomicFile atomic = new AtomicFile(file);
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
                Logger.printException(() -> "Failed to save GhostChannelStore", ex);
            }
        });
    }

    @Nullable
    private static File getStorageFile() {
        Context ctx = Utils.getContext();
        if (ctx == null) return null;
        return new File(ctx.getNoBackupFilesDir(), FILE_NAME);
    }

    static String serializeJson(@NonNull List<ChannelEntry> entries) {
        JSONArray arr = new JSONArray();
        for (ChannelEntry e : entries) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", e.channelId());
                obj.put("name", e.name());
                obj.put("handle", e.handle());
                obj.put("avatar", e.avatarUrl());
                obj.put("subs", e.subscriberText());
                obj.put("desc", e.description());
                obj.put("added", e.addedAtMs());
                JSONArray vids = new JSONArray();
                for (String v : e.recentVideos()) {
                    vids.put(v);
                }
                obj.put("videos", vids);
                arr.put(obj);
            } catch (Exception ignored) {
            }
        }
        return arr.toString();
    }

    @NonNull
    static List<ChannelEntry> deserializeJson(@Nullable String raw) {
        List<ChannelEntry> out = new ArrayList<>();
        if (raw == null || raw.trim().isEmpty()) {
            return out;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                String name = obj.optString("name", "");
                if (id.isEmpty() && name.isEmpty()) continue;
                List<String> vids = new ArrayList<>();
                JSONArray vidsArr = obj.optJSONArray("videos");
                if (vidsArr != null) {
                    for (int j = 0; j < vidsArr.length(); j++) {
                        String t = vidsArr.optString(j, "");
                        if (!t.isEmpty()) vids.add(t);
                    }
                }
                out.add(new ChannelEntry(
                        id,
                        name,
                        obj.optString("handle", ""),
                        obj.optString("avatar", ""),
                        obj.optString("subs", ""),
                        obj.optString("desc", ""),
                        vids,
                        obj.optLong("added", 0L)
                ));
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
