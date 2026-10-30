package app.morphe.extension.music.patches.downloads;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public final class OfflinePlaylistManager {

    private final File root;

    public OfflinePlaylistManager(File musicRoot) {
        this.root = new File(musicRoot, "playlists");
        if (!root.exists()) {
            root.mkdirs();
        }
    }

    public List<OfflinePlaylist> loadPlaylists() {
        List<OfflinePlaylist> playlists = new ArrayList<>();
        File[] files = root.listFiles(file -> file.isFile() && file.getName().endsWith(".json"));
        if (files == null) return playlists;

        for (File file : files) {
            try {
                playlists.add(OfflinePlaylist.fromFile(file));
            } catch (Exception ignored) {
            }
        }
        return playlists;
    }

    public void savePlaylist(OfflinePlaylist playlist) throws Exception {
        File file = new File(root, playlist.id() + ".json");
        playlist.save(file);
    }

    public OfflinePlaylist createNewPlaylist(String name, List<OfflineTrack> tracks) {
        String id = "playlist_" + System.currentTimeMillis();
        List<String> videoIds = new ArrayList<>();
        for (OfflineTrack track : tracks) {
            if (track.videoId() != null && !track.videoId().isBlank()) {
                videoIds.add(track.videoId());
            }
        }
        return new OfflinePlaylist(id, name, videoIds);
    }
}
