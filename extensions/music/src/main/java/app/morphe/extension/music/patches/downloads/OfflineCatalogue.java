/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import androidx.annotation.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The downloaded tracks, read from their folder off the main thread.
 * <p>
 * The downloads screen is a new activity every time it is opened, and reading thousands of
 * tracks takes seconds, so the last catalogue read is kept and shown again at once. The folder
 * is only read again when it has changed, and then only the files the catalogue did not have.
 */
final class OfflineCatalogue {

    /**
     * The tracks read from a folder, with what tells whether the folder changed since.
     *
     * @param modified   Last modification time of the folder, which changes when a file is
     *                   added, renamed or removed. -1 forces the folder to be read again.
     * @param audioFiles Number of audio files, in case two changes share a modification time.
     */
    record Snapshot(String directory, long modified, int audioFiles, List<OfflineTrack> tracks) {
    }

    /** Guards {@link #cached}. */
    private static final Object lock = new Object();

    @Nullable
    private static Snapshot cached;

    private OfflineCatalogue() {
    }

    @Nullable
    static Snapshot cached() {
        synchronized (lock) {
            return cached;
        }
    }

    /**
     * Records the tracks left after a change made from the screen, so the next visit does not
     * show it undone. The folder is still read again on that visit.
     */
    static Snapshot edited(File directory, List<OfflineTrack> tracks) {
        Snapshot snapshot = new Snapshot(directory.getAbsolutePath(), -1, -1,
                Collections.unmodifiableList(new ArrayList<>(tracks)));
        synchronized (lock) {
            cached = snapshot;
        }
        return snapshot;
    }

    static void clear() {
        synchronized (lock) {
            cached = null;
        }
    }

    /**
     * Reuses the last catalogue read while the folder is unchanged, and the tracks it already
     * had when it has. Runs off the main thread.
     *
     * @return The catalogue, or null when the screen changed it meanwhile.
     */
    @Nullable
    static Snapshot read(File directory) {
        Snapshot before = cached();

        // Read before listing, so a file added while listing makes the next visit read again.
        final long modified = directory.lastModified();
        String[] names = directory.list();
        List<String> audioNames = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                if (isAudioFile(name)) audioNames.add(name);
            }
        }

        String path = directory.getAbsolutePath();
        final boolean sameFolder = before != null && before.directory().equals(path);
        if (sameFolder && before.modified() == modified && before.audioFiles() == audioNames.size()) {
            return before;
        }

        Map<String, OfflineTrack> known = new HashMap<>();
        if (sameFolder) {
            for (OfflineTrack track : before.tracks()) {
                known.put(track.audioFile().getName(), track);
            }
        }

        List<OfflineTrack> loaded = new ArrayList<>(audioNames.size());
        for (String name : audioNames) {
            OfflineTrack track = known.get(name);
            loaded.add(track != null ? track : OfflineTrack.load(new File(directory, name)));
        }
        Snapshot snapshot = new Snapshot(path, modified, audioNames.size(),
                Collections.unmodifiableList(loaded));

        synchronized (lock) {
            // A track was deleted from the screen while the folder was read: what was read may
            // still hold it, so it is dropped, and the folder is read again on the next visit.
            if (cached != before) return null;
            cached = snapshot;
        }
        return snapshot;
    }

    /** By name only, since asking the file system about thousands of files takes seconds. */
    private static boolean isAudioFile(String name) {
        return name.endsWith(".webm") || name.endsWith(".m4a");
    }
}
