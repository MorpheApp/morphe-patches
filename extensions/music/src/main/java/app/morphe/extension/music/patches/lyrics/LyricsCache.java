/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.content.Context;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
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
    static synchronized Lyrics get(TrackInfo track, LyricsSource source) {
        String key = key(track, source);
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

    static synchronized void put(TrackInfo track, LyricsSource source, Lyrics lyrics) {
        String key = key(track, source);
        memoryCache.put(key, lyrics);
        writeToDisk(key, lyrics);
    }

    /**
     * @return Cached translation, or {@code null} if the track was not translated into
     * this language yet, or if the cached line count no longer matches the lyrics.
     */
    @Nullable
    static synchronized List<String> getTranslation(TrackInfo track,
                                                    LyricsSource source,
                                                    String language,
                                                    int expectedLineCount) {
        File file = translationFile(track, source, language);
        if (file == null || !file.exists()) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            // The lyrics may have been refetched from another provider since, in which
            // case the stored translation no longer lines up and has to be discarded.
            return lines.size() == expectedLineCount ? lines : null;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read cached translation: " + file, ex);
            return null;
        }
    }

    static synchronized void putTranslation(TrackInfo track,
                                            LyricsSource source,
                                            String language,
                                            List<String> lines) {
        File file = translationFile(track, source, language);
        if (file == null) {
            return;
        }

        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            trimDiskCache();
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not cache translation: " + file, ex);
        }
    }

    @Nullable
    private static File translationFile(TrackInfo track, LyricsSource source, String language) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory,
                Integer.toHexString(key(track, source).hashCode()) + "." + language + ".txt");
    }

    /**
     * Cache key that also captures the chosen lyrics source, so that switching
     * the source (for example to QQ or NetEase) forces a fresh fetch instead of
     * returning lyrics cached under a different source.
     */
    private static String key(TrackInfo track, LyricsSource source) {
        return track.cacheKey() + "|" + source.name();
    }

    @Nullable
    private static Lyrics readFromDisk(String key) {
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

    private static void writeToDisk(String key, Lyrics lyrics) {
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
                            ? LrcParser.formatLine(line)
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

    @Nullable
    private static File cacheFile(String key) {
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
