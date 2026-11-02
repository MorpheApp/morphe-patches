/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Tags, their visibility and the tags of each playlist, stored as JSON in the Morphe settings.
 *
 * <p>Playlists are keyed by playlist id without the 'VL' browse prefix. Titles of the playlists
 * seen in the Library are kept separately, so the settings screen can list playlists to tag.
 */
final class PlaylistTagsStore {

    /**
     * Upper bound of remembered Library playlists, so a very large Library cannot grow the setting forever.
     */
    private static final int MAX_KNOWN_PLAYLISTS = 2000;

    private static final String KEY_TAGS = "tags";
    private static final String KEY_TAG_NAME = "name";
    private static final String KEY_TAG_HIDDEN = "hidden";
    private static final String KEY_ONLY_TAG = "only";
    private static final String KEY_PLAYLISTS = "playlists";

    /**
     * Tag name to hidden state, in creation order.
     */
    private static final Map<String, Boolean> tags = new LinkedHashMap<>();
    private static final Map<String, Set<String>> playlistTags = new LinkedHashMap<>();
    private static final Map<String, String> knownPlaylists = new LinkedHashMap<>();
    /**
     * If not empty, only playlists with this tag are shown.
     */
    private static String onlyTag = "";

    private static boolean loaded;

    private PlaylistTagsStore() {
    }

    private static void loadIfNeeded() {
        if (loaded) return;
        loaded = true;

        try {
            String data = Settings.PLAYLIST_TAGS_DATA.get();
            if (!data.isEmpty()) {
                JSONObject root = new JSONObject(data);

                JSONArray tagArray = root.optJSONArray(KEY_TAGS);
                if (tagArray != null) {
                    for (int i = 0, length = tagArray.length(); i < length; i++) {
                        JSONObject tag = tagArray.getJSONObject(i);
                        String name = normalizeTagName(tag.optString(KEY_TAG_NAME));
                        if (!name.isEmpty()) {
                            tags.put(name, tag.optBoolean(KEY_TAG_HIDDEN, false));
                        }
                    }
                }

                onlyTag = root.optString(KEY_ONLY_TAG, "");
                if (!tags.containsKey(onlyTag)) onlyTag = "";

                JSONObject playlists = root.optJSONObject(KEY_PLAYLISTS);
                if (playlists != null) {
                    for (Iterator<String> ids = playlists.keys(); ids.hasNext(); ) {
                        String id = ids.next();
                        JSONArray names = playlists.getJSONArray(id);
                        Set<String> set = new LinkedHashSet<>();
                        for (int i = 0, length = names.length(); i < length; i++) {
                            String name = names.getString(i);
                            if (tags.containsKey(name)) set.add(name);
                        }
                        if (!set.isEmpty()) playlistTags.put(id, set);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not load playlist tags", ex);
        }

        try {
            String known = Settings.PLAYLIST_TAGS_KNOWN_PLAYLISTS.get();
            if (!known.isEmpty()) {
                JSONObject root = new JSONObject(known);
                for (Iterator<String> ids = root.keys(); ids.hasNext(); ) {
                    String id = ids.next();
                    knownPlaylists.put(id, root.optString(id, id));
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not load known playlists", ex);
        }
    }

    private static void save() {
        try {
            JSONObject root = new JSONObject();

            JSONArray tagArray = new JSONArray();
            for (Map.Entry<String, Boolean> entry : tags.entrySet()) {
                JSONObject tag = new JSONObject();
                tag.put(KEY_TAG_NAME, entry.getKey());
                tag.put(KEY_TAG_HIDDEN, entry.getValue());
                tagArray.put(tag);
            }
            root.put(KEY_TAGS, tagArray);
            root.put(KEY_ONLY_TAG, onlyTag);

            JSONObject playlists = new JSONObject();
            for (Map.Entry<String, Set<String>> entry : playlistTags.entrySet()) {
                playlists.put(entry.getKey(), new JSONArray(entry.getValue()));
            }
            root.put(KEY_PLAYLISTS, playlists);

            Settings.PLAYLIST_TAGS_DATA.save(root.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save playlist tags", ex);
        }
    }

    private static void saveKnownPlaylists() {
        try {
            Settings.PLAYLIST_TAGS_KNOWN_PLAYLISTS.save(new JSONObject(knownPlaylists).toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save known playlists", ex);
        }
    }

    @NonNull
    static String normalizeTagName(@Nullable String name) {
        return name == null ? "" : name.trim().replaceAll("\\s+", " ");
    }

    // region Tags

    static synchronized List<String> getTags() {
        loadIfNeeded();
        return new ArrayList<>(tags.keySet());
    }

    /**
     * @return False if the name is empty or the tag already exists.
     */
    static synchronized boolean addTag(String name) {
        loadIfNeeded();
        name = normalizeTagName(name);
        if (name.isEmpty() || tags.containsKey(name)) return false;
        tags.put(name, false);
        save();
        return true;
    }

    /**
     * @return False if the new name is empty or used by another tag.
     */
    static synchronized boolean renameTag(String oldName, String newName) {
        loadIfNeeded();
        newName = normalizeTagName(newName);
        if (newName.isEmpty() || !tags.containsKey(oldName)) return false;
        if (newName.equals(oldName)) return true;
        if (tags.containsKey(newName)) return false;

        // Rebuild the map to keep the tag at the same position.
        Map<String, Boolean> renamed = new LinkedHashMap<>();
        for (Map.Entry<String, Boolean> entry : tags.entrySet()) {
            renamed.put(entry.getKey().equals(oldName) ? newName : entry.getKey(), entry.getValue());
        }
        tags.clear();
        tags.putAll(renamed);

        for (Set<String> set : playlistTags.values()) {
            if (set.remove(oldName)) set.add(newName);
        }
        if (onlyTag.equals(oldName)) onlyTag = newName;

        save();
        return true;
    }

    static synchronized void deleteTag(String name) {
        loadIfNeeded();
        if (tags.remove(name) == null) return;

        for (Iterator<Set<String>> sets = playlistTags.values().iterator(); sets.hasNext(); ) {
            Set<String> set = sets.next();
            set.remove(name);
            if (set.isEmpty()) sets.remove();
        }
        if (onlyTag.equals(name)) onlyTag = "";

        save();
    }

    static synchronized boolean isTagHidden(String name) {
        loadIfNeeded();
        return Boolean.TRUE.equals(tags.get(name));
    }

    /**
     * @return If the hidden state changed.
     */
    static synchronized boolean setTagHidden(String name, boolean hidden) {
        loadIfNeeded();
        if (!tags.containsKey(name) || isTagHidden(name) == hidden) return false;
        tags.put(name, hidden);
        save();
        return true;
    }

    @NonNull
    static synchronized String getOnlyTag() {
        loadIfNeeded();
        return onlyTag;
    }

    /**
     * @return If the tag to show only changed.
     */
    static synchronized boolean setOnlyTag(@Nullable String name) {
        loadIfNeeded();
        if (name == null || !tags.containsKey(name)) name = "";
        if (onlyTag.equals(name)) return false;
        onlyTag = name;
        save();
        return true;
    }

    static synchronized int getHiddenTagCount() {
        loadIfNeeded();
        int count = 0;
        for (Boolean hidden : tags.values()) {
            if (hidden) count++;
        }
        return count;
    }

    // endregion

    // region Playlists

    static synchronized Set<String> getPlaylistTags(String playlistId) {
        loadIfNeeded();
        Set<String> set = playlistTags.get(playlistId);
        return set == null ? Collections.emptySet() : new LinkedHashSet<>(set);
    }

    /**
     * @return How many playlists have a tag.
     */
    static synchronized int getPlaylistCount(String tag) {
        loadIfNeeded();
        int count = 0;
        for (Set<String> set : playlistTags.values()) {
            if (set.contains(tag)) count++;
        }
        return count;
    }

    static synchronized void setPlaylistTags(String playlistId, Set<String> names) {
        loadIfNeeded();
        Set<String> set = new LinkedHashSet<>();
        // Keep the order of the tag list.
        for (String tag : tags.keySet()) {
            if (names.contains(tag)) set.add(tag);
        }
        if (set.equals(playlistTags.getOrDefault(playlistId, Collections.emptySet()))) return;
        if (set.isEmpty()) {
            playlistTags.remove(playlistId);
        } else {
            playlistTags.put(playlistId, set);
        }
        save();
    }

    /**
     * @return If the playlist is hidden by the current tag filter. A playlist is hidden
     *         only when all of its tags are hidden, so a visible tag keeps it shown.
     */
    static synchronized boolean isPlaylistHidden(String playlistId) {
        loadIfNeeded();
        Set<String> set = playlistTags.get(playlistId);
        if (!onlyTag.isEmpty()) {
            return set == null || !set.contains(onlyTag);
        }
        // Playlists without tags are not stored, so a stored set is never empty.
        if (set == null) return false;
        for (String tag : set) {
            if (!Boolean.TRUE.equals(tags.get(tag))) return false;
        }
        return true;
    }

    /**
     * Remembers the title of a playlist seen in the Library.
     */
    static synchronized void rememberPlaylist(String playlistId, @Nullable String title) {
        loadIfNeeded();
        if (title == null || title.isEmpty()) title = playlistId;
        if (title.equals(knownPlaylists.get(playlistId))) return;

        knownPlaylists.put(playlistId, title);
        if (knownPlaylists.size() > MAX_KNOWN_PLAYLISTS) {
            // Forget the oldest untagged playlist.
            for (Iterator<String> ids = knownPlaylists.keySet().iterator(); ids.hasNext(); ) {
                if (!playlistTags.containsKey(ids.next())) {
                    ids.remove();
                    break;
                }
            }
        }
        saveKnownPlaylists();
    }

    /**
     * Forgets a playlist and its tags, after it was deleted or removed from the Library.
     *
     * @return If the playlist was known or tagged.
     */
    static synchronized boolean forgetPlaylist(String playlistId) {
        loadIfNeeded();
        boolean tagged = playlistTags.remove(playlistId) != null;
        boolean known = knownPlaylists.remove(playlistId) != null;
        if (tagged) save();
        if (known) saveKnownPlaylists();
        return tagged || known;
    }

    @NonNull
    static synchronized String getPlaylistTitle(String playlistId) {
        loadIfNeeded();
        String title = knownPlaylists.get(playlistId);
        return title == null ? playlistId : title;
    }

    /**
     * @return Playlist id to title, of the playlists seen in the Library and of all tagged playlists.
     */
    static synchronized Map<String, String> getKnownPlaylists() {
        loadIfNeeded();
        Map<String, String> playlists = new LinkedHashMap<>(knownPlaylists);
        for (String id : playlistTags.keySet()) {
            playlists.putIfAbsent(id, id);
        }
        return playlists;
    }

    // endregion
}
