/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Two level lyrics cache: an in memory map for the current session,
 * and a disk cache so that replaying a track needs no network.
 */
final class LyricsCache {

    private static final int MEMORY_ENTRIES = 30;

    /** Maximum number of files kept on disk. Older files are deleted first. */
    private static final int DISK_ENTRIES = 250;

    private static final String DIRECTORY_NAME = "morphe_lyrics";
    private static final String HEADER_PROVIDER = "#provider=";
    private static final String HEADER_SYNCED = "#synced=";
    private static final String NOT_FOUND_MARKER = "#notfound";

    private static final Map<String, Lyrics> memoryCache =
            Utils.createSizeRestrictedMap(MEMORY_ENTRIES);

    private LyricsCache() {
    }

    @Nullable
    static synchronized Lyrics get(@NonNull TrackInfo track) {
        String key = track.cacheKey();
        Lyrics cached = memoryCache.get(key);
        if (cached != null) {
            return cached;
        }

        Lyrics fromDisk = readFromDisk(key);
        if (fromDisk != null) {
            memoryCache.put(key, fromDisk);
        }
        return fromDisk;
    }

    static synchronized void put(@NonNull TrackInfo track, @NonNull Lyrics lyrics) {
        String key = track.cacheKey();
        memoryCache.put(key, lyrics);
        writeToDisk(key, lyrics);
    }

    @Nullable
    private static Lyrics readFromDisk(@NonNull String key) {
        File file = cacheFile(key);
        if (file == null || !file.exists()) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }

            String provider = "";
            boolean synced = false;
            int contentStart = 0;

            for (String line : lines) {
                if (line.equals(NOT_FOUND_MARKER)) {
                    return Lyrics.NOT_FOUND;
                }
                if (line.startsWith(HEADER_PROVIDER)) {
                    provider = line.substring(HEADER_PROVIDER.length());
                } else if (line.startsWith(HEADER_SYNCED)) {
                    synced = Boolean.parseBoolean(line.substring(HEADER_SYNCED.length()));
                } else {
                    break;
                }
                contentStart++;
            }

            String content = String.join("\n", lines.subList(contentStart, lines.size()));
            List<LyricsLine> parsed = synced
                    ? LrcParser.parseSynced(content)
                    : LrcParser.parsePlain(content);
            if (parsed.isEmpty()) {
                return null;
            }
            return new Lyrics(parsed, provider, synced);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read cached lyrics: " + file, ex);
            return null;
        }
    }

    private static void writeToDisk(@NonNull String key, @NonNull Lyrics lyrics) {
        File file = cacheFile(key);
        if (file == null) {
            return;
        }

        try {
            List<String> fileLines = new ArrayList<>();
            if (lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
                fileLines.add(NOT_FOUND_MARKER);
            } else {
                fileLines.add(HEADER_PROVIDER + lyrics.providerName());
                fileLines.add(HEADER_SYNCED + lyrics.synced());
                for (LyricsLine line : lyrics.lines()) {
                    fileLines.add(lyrics.synced()
                            ? formatTimestamp(line.startTimeMs()) + line.text()
                            : line.text());
                }
            }

            Files.write(file.toPath(), fileLines, StandardCharsets.UTF_8);
            trimDiskCache();
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not cache lyrics: " + file, ex);
        }
    }

    /**
     * Deletes the oldest files once the cache grows past {@link #DISK_ENTRIES}.
     */
    private static void trimDiskCache() {
        File directory = cacheDirectory();
        if (directory == null) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null || files.length <= DISK_ENTRIES) {
            return;
        }

        List<File> sorted = new ArrayList<>(Arrays.asList(files));
        sorted.sort(Comparator.comparingLong(File::lastModified));

        final int deleteCount = sorted.size() - DISK_ENTRIES;
        for (int i = 0; i < deleteCount; i++) {
            File file = sorted.get(i);
            if (!file.delete()) {
                Logger.printDebug(() -> "Could not delete " + file);
            }
        }
    }

    @NonNull
    private static String formatTimestamp(long timeMs) {
        final long minutes = timeMs / 60_000;
        final long seconds = (timeMs / 1000) % 60;
        final long hundredths = (timeMs % 1000) / 10;
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, hundredths);
    }

    @Nullable
    private static File cacheFile(@NonNull String key) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        // Track titles contain characters that are not valid in file names.
        return new File(directory, Integer.toHexString(key.hashCode()) + ".lrc");
    }

    @Nullable
    private static File cacheDirectory() {
        try {
            Context context = Utils.getContext();
            if (context == null) {
                return null;
            }
            File directory = new File(context.getCacheDir(), DIRECTORY_NAME);
            if (!directory.exists() && !directory.mkdirs()) {
                return null;
            }
            return directory;
        } catch (Exception ex) {
            Logger.printException(() -> "Could not open lyrics cache directory", ex);
            return null;
        }
    }
}
