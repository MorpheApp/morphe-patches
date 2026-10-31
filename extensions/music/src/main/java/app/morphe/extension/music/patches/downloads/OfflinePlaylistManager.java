/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4232
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import app.morphe.extension.shared.Logger;

/** Manages local offline playlist storage, retrieval, and updates on disk. */
public final class OfflinePlaylistManager {

    private final File musicRoot;
    private final File playlistsDir;

    public OfflinePlaylistManager(File musicRoot) {
        this.musicRoot = musicRoot;
        this.playlistsDir = new File(musicRoot, "playlists");
        if (!playlistsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            playlistsDir.mkdirs();
        }
    }

    /** Loads and parses all local playlist JSON files stored on disk. */
    public List<OfflinePlaylist> loadPlaylists() {
        List<OfflinePlaylist> playlists = new ArrayList<>();
        File[] files = playlistsDir.listFiles(f -> f.isFile() && f.getName().endsWith(".json"));
        if (files == null) return playlists;

        for (File file : files) {
            try {
                JSONObject json = new JSONObject(OfflineStorage.readText(file));
                String id = json.optString("id", file.getName().replace(".json", ""));
                String name = json.optString("name", "Untitled Playlist");

                List<String> paths = new ArrayList<>();
                JSONArray pathArray = json.optJSONArray("trackPaths");
                if (pathArray != null) {
                    for (int i = 0; i < pathArray.length(); i++) {
                        String path = pathArray.optString(i, "");
                        if (!path.isEmpty() && new File(path).isFile()) {
                            paths.add(path);
                        }
                    }
                }
                playlists.add(new OfflinePlaylist(id, name, paths));
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to load playlist: " + file, ex);
            }
        }

        playlists.sort(Comparator.comparing(p -> p.name().toLowerCase()));
        return playlists;
    }

    public OfflinePlaylist getPlaylist(String playlistId) {
        for (OfflinePlaylist playlist : loadPlaylists()) {
            if (playlist.id().equals(playlistId)) {
                return playlist;
            }
        }
        return null;
    }

    public OfflinePlaylist createNewPlaylist(String name, List<OfflineTrack> selectedTracks) {
        String id = "playlist_" + System.currentTimeMillis();
        List<String> paths = new ArrayList<>();
        for (OfflineTrack track : selectedTracks) {
            String path = track.audioFile().getAbsolutePath();
            if (!paths.contains(path)) {
                paths.add(path);
            }
        }
        return new OfflinePlaylist(id, name, paths);
    }

    public OfflinePlaylist createNewPlaylistFromPaths(String name, List<String> trackPaths) {
        String id = "playlist_" + System.currentTimeMillis();
        List<String> uniquePaths = new ArrayList<>();
        for (String path : trackPaths) {
            if (!uniquePaths.contains(path)) {
                uniquePaths.add(path);
            }
        }
        return new OfflinePlaylist(id, name, uniquePaths);
    }

    /** Serializes and saves a playlist object to a JSON file on disk. */
    public void savePlaylist(OfflinePlaylist playlist) throws IOException {
        if (!playlistsDir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            playlistsDir.mkdirs();
        }
        try {
            JSONObject json = new JSONObject();
            json.put("id", playlist.id());
            json.put("name", playlist.name());

            JSONArray arr = new JSONArray();
            for (String path : playlist.trackPaths()) {
                arr.put(path);
            }
            json.put("trackPaths", arr);

            File outFile = new File(playlistsDir, playlist.id() + ".json");
            OfflineStorage.writeText(outFile, json.toString());
        } catch (Exception ex) {
            throw new IOException("Could not serialize playlist", ex);
        }
    }

    public void addTrackPathsToPlaylist(String playlistId, List<String> newPaths) throws IOException {
        OfflinePlaylist existing = getPlaylist(playlistId);
        if (existing == null) throw new IOException("Playlist not found: " + playlistId);

        List<String> updated = new ArrayList<>(existing.trackPaths());
        for (String path : newPaths) {
            if (!updated.contains(path)) {
                updated.add(path);
            }
        }
        savePlaylist(new OfflinePlaylist(existing.id(), existing.name(), updated));
    }

    public void removeTrackPathFromPlaylist(String playlistId, String pathToRemove) throws IOException {
        OfflinePlaylist existing = getPlaylist(playlistId);
        if (existing == null) return;

        List<String> updated = new ArrayList<>(existing.trackPaths());
        updated.remove(pathToRemove);
        savePlaylist(new OfflinePlaylist(existing.id(), existing.name(), updated));
    }

    /** Updates and persists the new sequence of track paths after reordering. */
    public void updateTrackOrder(String playlistId, List<String> orderedPaths) throws IOException {
        OfflinePlaylist existing = getPlaylist(playlistId);
        if (existing == null) throw new IOException("Playlist not found: " + playlistId);

        savePlaylist(new OfflinePlaylist(existing.id(), existing.name(), orderedPaths));
    }

        /** Updates the display name of an existing playlist. */
    public void renamePlaylist(String playlistId, String newName) throws IOException {
        OfflinePlaylist existing = getPlaylist(playlistId);
        if (existing == null) throw new IOException("Playlist not found: " + playlistId);

        savePlaylist(new OfflinePlaylist(existing.id(), newName, existing.trackPaths()));
    }

    public void deletePlaylist(String playlistId) {
        File file = new File(playlistsDir, playlistId + ".json");
        OfflineStorage.delete(file);
    }
}
