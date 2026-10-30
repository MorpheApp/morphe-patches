package app.morphe.extension.music.patches.downloads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class OfflinePlaylist {
    private final String id;
    private final String name;
    private final List<String> trackPaths;

    public OfflinePlaylist(String id, String name, List<String> trackPaths) {
        this.id = id;
        this.name = name;
        this.trackPaths = new ArrayList<>(trackPaths);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public List<String> trackPaths() {
        return Collections.unmodifiableList(trackPaths);
    }
}
