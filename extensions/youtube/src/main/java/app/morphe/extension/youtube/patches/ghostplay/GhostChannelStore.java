/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5197
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.ghostplay;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;

/**
 * On-device store for auto-ghost channels (channel ID, name, handle, avatar, description,
 * and recent video titles). Stored in app noBackupFilesDir using serialized AtomicFile writes.
 */
public final class GhostChannelStore {

    private static final GhostJsonFile file = new GhostJsonFile("morphe_ghost_channels.json");

    public record ChannelEntry(
            String channelId,
            String name,
            String handle,
            String avatarUrl,
            String subscriberText,
            String description,
            List<String> recentVideos,
            long addedAtMs
    ) {
        public boolean matchesFilter(String lowerQuery) {
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
    private static List<ChannelEntry> cachedEntries;

    private GhostChannelStore() {
    }

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

    public static synchronized void addOrUpdate(ChannelEntry entry) {
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
                save(list);
                return;
            }
        }
        list.add(0, entry);
        save(list);
    }

    public static synchronized boolean remove(@Nullable String channelId, @Nullable String channelName) {
        ensureLoaded();
        List<ChannelEntry> list = new ArrayList<>(cachedEntries);
        boolean removed = removeFromList(list, channelId, channelName);
        if (removed) {
            save(list);
        }
        return removed;
    }

    static boolean removeFromList(
            List<ChannelEntry> list,
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
            List<ChannelEntry> entries,
            @Nullable String channelId,
            @Nullable String channelName
    ) {
        if (entries.isEmpty()) return false;
        String normId = channelId == null ? "" : channelId.trim();
        String normName = channelName == null ? "" : channelName.trim().toLowerCase(Locale.ROOT);
        if (normId.isEmpty() && normName.isEmpty()) return false;

        for (ChannelEntry e : entries) {
            if (!normId.isEmpty() && !e.channelId().isEmpty()) {
                // Different channels can have the same name, so only the ids are compared.
                if (normId.equals(e.channelId())) {
                    return true;
                }
            } else if (!normName.isEmpty()) {
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
        if (cachedEntries == null) {
            cachedEntries = fromJson(file.read());
        }
    }

    private static void save(List<ChannelEntry> entries) {
        cachedEntries = entries;
        try {
            file.writeAsync(toJson(entries));
        } catch (JSONException ex) {
            Logger.printException(() -> "GhostChannelStore save failure", ex);
        }
    }

    private static JSONArray toJson(List<ChannelEntry> entries) throws JSONException {
        JSONArray array = new JSONArray();
        for (ChannelEntry e : entries) {
            array.put(new JSONObject()
                    .put("id", e.channelId())
                    .put("name", e.name())
                    .put("handle", e.handle())
                    .put("avatar", e.avatarUrl())
                    .put("subs", e.subscriberText())
                    .put("desc", e.description())
                    .put("added", e.addedAtMs())
                    .put("videos", new JSONArray(e.recentVideos())));
        }
        return array;
    }

    private static List<ChannelEntry> fromJson(JSONArray array) {
        List<ChannelEntry> entries = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj == null) continue;
            String id = obj.optString("id");
            String name = obj.optString("name");
            if (id.isEmpty() && name.isEmpty()) continue;
            List<String> videos = new ArrayList<>();
            JSONArray videosArray = obj.optJSONArray("videos");
            if (videosArray != null) {
                for (int j = 0; j < videosArray.length(); j++) {
                    String title = videosArray.optString(j);
                    if (!title.isEmpty()) videos.add(title);
                }
            }
            entries.add(new ChannelEntry(
                    id,
                    name,
                    obj.optString("handle"),
                    obj.optString("avatar"),
                    obj.optString("subs"),
                    obj.optString("desc"),
                    videos,
                    obj.optLong("added")
            ));
        }
        return entries;
    }
}
