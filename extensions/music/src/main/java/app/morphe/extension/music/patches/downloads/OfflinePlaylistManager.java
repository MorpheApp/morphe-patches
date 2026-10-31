/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4232
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Logger;

/**
 * Stores each local playlist as a JSON file named after its id.
 * <p>
 * Tracks are saved by file name, because the absolute path of the app storage changes if the app
 * is moved to another storage volume. Tracks that no longer exist are skipped when loading, and
 * are dropped from the file the next time the playlist is saved.
 */
final class OfflinePlaylistManager {

    private static final String FILE_EXTENSION = ".json";
    private static final String KEY_NAME = "name";
    private static final String KEY_TRACKS = "tracks";

    private final File musicRoot;
    private final File playlistsDirectory;

    OfflinePlaylistManager(File musicRoot) {
        this.musicRoot = musicRoot;
        this.playlistsDirectory = new File(musicRoot, "playlists");
    }

    /**
     * @return All playlists that can be read, sorted by name.
     */
    List<OfflinePlaylist> loadPlaylists() {
        List<OfflinePlaylist> playlists = new ArrayList<>();
        File[] files = playlistsDirectory.listFiles(
                file -> file.isFile() && file.getName().endsWith(FILE_EXTENSION));
        if (files == null) return playlists;

        for (File file : files) {
            OfflinePlaylist playlist = read(file);
            if (playlist != null) playlists.add(playlist);
        }
        playlists.sort(Comparator.comparing(OfflinePlaylist::name, String.CASE_INSENSITIVE_ORDER));
        return playlists;
    }

    @Nullable
    OfflinePlaylist getPlaylist(String id) {
        File file = fileOf(id);
        return file.isFile() ? read(file) : null;
    }

    void createPlaylist(String name, List<String> trackPaths) throws IOException {
        String id = "playlist_" + System.currentTimeMillis();
        save(new OfflinePlaylist(id, name, new ArrayList<>(new LinkedHashSet<>(trackPaths))));
    }

    /**
     * Tracks that are already in the playlist keep their position.
     */
    void addTracks(String id, List<String> trackPaths) throws IOException {
        OfflinePlaylist playlist = require(id);
        Set<String> updated = new LinkedHashSet<>(playlist.trackPaths());
        updated.addAll(trackPaths);
        setTracks(playlist, new ArrayList<>(updated));
    }

    /**
     * Only changes the playlist. The downloaded file is kept.
     */
    void removeTrack(String id, String trackPath) throws IOException {
        OfflinePlaylist playlist = require(id);
        List<String> updated = new ArrayList<>(playlist.trackPaths());
        updated.remove(trackPath);
        setTracks(playlist, updated);
    }

    void setTrackOrder(String id, List<String> trackPaths) throws IOException {
        setTracks(require(id), trackPaths);
    }

    void renamePlaylist(String id, String name) throws IOException {
        OfflinePlaylist playlist = require(id);
        save(new OfflinePlaylist(playlist.id(), name, playlist.trackPaths()));
    }

    /**
     * Only removes the playlist. The downloaded files are kept.
     */
    void deletePlaylist(String id) {
        OfflineStorage.delete(fileOf(id));
    }

    private File fileOf(String id) {
        return new File(playlistsDirectory, id + FILE_EXTENSION);
    }

    private OfflinePlaylist require(String id) throws IOException {
        OfflinePlaylist playlist = getPlaylist(id);
        if (playlist == null) throw new IOException("Playlist not found: " + id);
        return playlist;
    }

    private void setTracks(OfflinePlaylist playlist, List<String> trackPaths) throws IOException {
        save(new OfflinePlaylist(playlist.id(), playlist.name(), trackPaths));
    }

    @Nullable
    private OfflinePlaylist read(File file) {
        try {
            JSONObject json = new JSONObject(OfflineStorage.readText(file));

            List<String> trackPaths = new ArrayList<>();
            JSONArray tracks = json.getJSONArray(KEY_TRACKS);
            for (int i = 0; i < tracks.length(); i++) {
                File track = new File(musicRoot, tracks.getString(i));
                if (track.isFile()) trackPaths.add(track.getAbsolutePath());
            }

            String fileName = file.getName();
            String id = fileName.substring(0, fileName.length() - FILE_EXTENSION.length());
            return new OfflinePlaylist(id, json.getString(KEY_NAME), trackPaths);
        } catch (IOException | JSONException ex) {
            Logger.printException(() -> "Could not read playlist: " + file, ex);
            return null;
        }
    }

    private void save(OfflinePlaylist playlist) throws IOException {
        if (!playlistsDirectory.isDirectory() && !playlistsDirectory.mkdirs()) {
            throw new IOException("Could not create " + playlistsDirectory);
        }

        JSONArray tracks = new JSONArray();
        for (String trackPath : playlist.trackPaths()) {
            tracks.put(new File(trackPath).getName());
        }

        try {
            JSONObject json = new JSONObject()
                    .put(KEY_NAME, playlist.name())
                    .put(KEY_TRACKS, tracks);
            OfflineStorage.writeText(fileOf(playlist.id()), json.toString());
        } catch (JSONException ex) {
            throw new IOException("Could not serialize playlist: " + playlist.id(), ex);
        }
    }
}
