/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4396
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The downloaded tracks, read from their folder off the main thread.
 * <p>
 * The downloads screen is a new activity every time it is opened, and reading thousands of
 * tracks takes seconds, so the last catalogue read is kept and shown again at once. The folder
 * is only read again when it has changed, and then only the files the catalogue did not have.
 */
final class OfflineCatalogue {

    /**
     * The tracks read from the folder, with what tells whether the folder changed since.
     *
     * @param modified   Last modification time of the folder, which changes when a file is
     *                   added, renamed or removed. -1 forces the folder to be read again.
     * @param audioFiles Number of audio files, in case two changes share a modification time.
     */
    record Snapshot(long modified, int audioFiles, List<OfflineTrack> tracks) {
    }

    /** Guards {@link #cached}, and the first listing of {@link #videoIds}. */
    private static final Object lock = new Object();

    /**
     * One read at a time, so the screen recreated by a rotation reuses the read of the screen
     * before it rather than reading every file again alongside it.
     */
    private static final Object readLock = new Object();

    @Nullable
    private static Snapshot cached;

    /**
     * The video ids of the audio files of the folder, for the marks of the lists of the app. Each
     * file is named after its id, so listing the folder gives them in milliseconds, where reading
     * the tracks takes seconds. Null until a mark is first needed, and once the folder is emptied.
     */
    @Nullable
    private static volatile Set<String> videoIds;

    private OfflineCatalogue() {
    }

    @Nullable
    static Snapshot cached() {
        synchronized (lock) {
            return cached;
        }
    }

    /**
     * @return If a track with this video id is downloaded. The folder is listed the first time,
     *         which takes a few milliseconds, unlike reading its tracks.
     */
    static boolean contains(String videoId) {
        Set<String> ids = videoIds;
        if (ids == null) {
            // Under the lock of added(), so a track saved while the folder is listed is not lost.
            synchronized (lock) {
                ids = videoIds;
                if (ids == null) {
                    ids = videoIdsOf(audioNames(LocalDownloadManager.getDownloadDirectory()));
                    videoIds = ids;
                }
            }
        }
        return ids.contains(videoId);
    }

    /** Lists a track just saved. */
    static void added(String videoId) {
        synchronized (lock) {
            Set<String> ids = videoIds;
            if (ids != null) ids.add(videoId);
        }
    }

    /**
     * Records the tracks left after a change made from the screen, so the next visit does not
     * show it undone. The folder is still read again on that visit.
     */
    static Snapshot edited(List<OfflineTrack> tracks) {
        Snapshot snapshot = new Snapshot(-1, -1,
                Collections.unmodifiableList(new ArrayList<>(tracks)));
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (OfflineTrack track : tracks) {
            ids.add(track.videoId());
        }
        synchronized (lock) {
            cached = snapshot;
        }
        videoIds = ids;
        return snapshot;
    }

    static void clear() {
        synchronized (lock) {
            cached = null;
        }
        videoIds = null;
    }

    /**
     * Reads the folder again if it changed since the last read, reusing the tracks the catalogue
     * already has. Runs off the main thread. The result is {@link #cached()}.
     */
    static void update(File directory) {
        synchronized (readLock) {
            updateLocked(directory);
        }
    }

    private static void updateLocked(File directory) {
        Snapshot before = cached();

        // Read before listing, so a file added while listing makes the next visit read again.
        final long modified = directory.lastModified();
        List<String> audioNames = audioNames(directory);

        if (before != null && before.modified() == modified
                && before.audioFiles() == audioNames.size()) {
            return;
        }
        // The folder may have changed behind the app.
        videoIds = videoIdsOf(audioNames);

        Map<String, OfflineTrack> known = new HashMap<>();
        if (before != null) {
            for (OfflineTrack track : before.tracks()) {
                known.put(track.audioFile().getName(), track);
            }
        }

        List<OfflineTrack> loaded = new ArrayList<>(audioNames.size());
        for (String name : audioNames) {
            OfflineTrack track = known.get(name);
            loaded.add(track != null ? track : OfflineTrack.load(new File(directory, name)));
        }
        Snapshot snapshot = new Snapshot(modified, audioNames.size(),
                Collections.unmodifiableList(loaded));

        synchronized (lock) {
            // A track deleted from the screen meanwhile may still be in what was read.
            if (cached == before) cached = snapshot;
        }
    }

    private static List<String> audioNames(File directory) {
        String[] names = directory.list();
        List<String> audioNames = new ArrayList<>();
        if (names != null) {
            for (String name : names) {
                if (isAudioFile(name)) audioNames.add(name);
            }
        }
        return audioNames;
    }

    private static Set<String> videoIdsOf(List<String> audioNames) {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (String name : audioNames) {
            ids.add(OfflineTrack.videoIdOf(name));
        }
        return ids;
    }

    /** By name only, since asking the file system about thousands of files takes seconds. */
    private static boolean isAudioFile(String name) {
        return name.endsWith(".webm") || name.endsWith(".m4a");
    }
}
