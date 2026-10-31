/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Immutable model representing a local offline playlist and its associated track file paths. */
public final class OfflinePlaylist {
    private final String id;
    private final String name;
    private final List<String> trackPaths;

    /** Constructs a new offline playlist instance with a defensive copy of the track paths. */
    public OfflinePlaylist(String id, String name, List<String> trackPaths) {
        this.id = id;
        this.name = name;
        this.trackPaths = new ArrayList<>(trackPaths);
    }

    /** Returns the unique identifier for this playlist. */
    public String id() {
        return id;
    }

    /** Returns the display name of this playlist. */
    public String name() {
        return name;
    }

    /** Returns an unmodifiable list of absolute track file paths contained in this playlist. */
    public List<String> trackPaths() {
        return Collections.unmodifiableList(trackPaths);
    }
}
