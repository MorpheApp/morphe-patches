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
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;

/**
 * Two level lyrics cache: an in memory map for the current session,
 * and a disk cache so that replaying a track needs no network.
 */
final class LyricsCache {


    private static final int MEMORY_ENTRIES = 200;

    /** Maximum number of files kept on disk. Older files are deleted first. */
    private static final int DISK_ENTRIES = 250;

    private static final String DIRECTORY_NAME = "morphe_lyrics";
    private static final String HEADER_PROVIDER = "#provider=";
    private static final String HEADER_SYNCED = "#synced=";
    private static final String HEADER_SOURCE_URL = "#sourceUrl=";
    private static final String HEADER_SONGWRITERS = "#songwriters=";
    private static final String NOT_FOUND_MARKER = "#notfound";

    private static final Map<String, Lyrics> memoryCache =
            new ConcurrentHashMap<>(MEMORY_ENTRIES);

    private LyricsCache() {
    }

    @Nullable
    static Lyrics get(TrackInfo track, String source) {
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

    static void put(TrackInfo track, String source, Lyrics lyrics) {
        String key = key(track, source);
        memoryCache.put(key, lyrics);
        writeToDisk(key, lyrics);
        writeEmbeddedRomanization(key, lyrics.romanization());
    }

    /**
     * @return Cached translation, or {@code null} if the track was not translated into
     * this language yet, or if the cached line count no longer matches the lyrics.
     */
    @Nullable
    static List<String> getTranslation(TrackInfo track,
                                       String source,
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
            return null;
        }
    }

    static void putTranslation(TrackInfo track,
                                String source,
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
        }
    }

    /**
     * @return Cached romanization, or {@code null} if the track was not romanized yet, or
     * if the cached line count no longer matches the lyrics.
     */
    @Nullable
    static List<LyricsLine> getRomanization(TrackInfo track,
                                            String source,
                                            int expectedLineCount) {
        File file = romanizationFile(track, source);
        if (file == null || !file.exists()) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            // The lyrics may have been refetched from another provider since, in which
            // case the stored romanization no longer lines up and has to be discarded.
            if (lines.size() != expectedLineCount) {
                return null;
            }
            List<LyricsLine> result = new ArrayList<>(lines.size());
            for (String line : lines) {
                result.add(new LyricsLine(LyricsLine.NO_TIME, line));
            }
            return result;
        } catch (Exception ex) {
            return null;
        }
    }

    static void putRomanization(TrackInfo track,
                                String source,
                                List<LyricsLine> lines) {
        File file = romanizationFile(track, source);
        if (file == null) {
            return;
        }

        try {
            List<String> fileLines = new ArrayList<>(lines.size());
            for (LyricsLine line : lines) {
                fileLines.add(line.text());
            }
            Files.write(file.toPath(), fileLines, StandardCharsets.UTF_8);
            trimDiskCache();
        } catch (IOException ex) {
        }
    }

    @Nullable
    private static File translationFile(TrackInfo track, String source, String language) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory,
                Integer.toHexString(key(track, source).hashCode()) + "." + language + ".txt");
    }

    @Nullable
    private static File romanizationFile(TrackInfo track, String source) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory,
                Integer.toHexString(key(track, source).hashCode()) + ".rom.txt");
    }

    @Nullable
    private static File embeddedRomanizationFile(String key) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory, Integer.toHexString(key.hashCode()) + ".rombed.txt");
    }

    @Nullable
    private static List<LyricsLine> readEmbeddedRomanization(String key) {
        File file = embeddedRomanizationFile(key);
        if (file == null || !file.exists()) {
            return null;
        }

        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }
            List<LyricsLine> result = new ArrayList<>(lines.size());
            for (String line : lines) {
                result.add(new LyricsLine(LyricsLine.NO_TIME, line));
            }
            return result;
        } catch (Exception ex) {
            return null;
        }
    }

    private static void writeEmbeddedRomanization(String key, @Nullable List<LyricsLine> romanization) {
        File file = embeddedRomanizationFile(key);
        if (file == null || !LyricsMerge.hasText(romanization)) {
            return;
        }

        try {
            List<String> fileLines = new ArrayList<>(romanization.size());
            for (LyricsLine line : romanization) {
                fileLines.add(line.text());
            }
            Files.write(file.toPath(), fileLines, StandardCharsets.UTF_8);
            trimDiskCache();
        } catch (IOException ex) {
        }
    }

    /**
     * Cache key that also captures the chosen lyrics source, so that switching
     * the source (for example to QQ or NetEase) forces a fresh fetch instead of
     * returning lyrics cached under a different source.
     */
    private static String key(TrackInfo track, String source) {
        return track.cacheKey() + "|" + source;
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
            String sourceUrl = null;
            List<String> songwriters = null;
            int contentStart = 0;

            for (String line : lines) {
                if (line.equals(NOT_FOUND_MARKER)) {
                    return Lyrics.NOT_FOUND;
                }
                if (line.startsWith(HEADER_PROVIDER)) {
                    provider = line.substring(HEADER_PROVIDER.length());
                } else if (line.startsWith(HEADER_SYNCED)) {
                    synced = Boolean.parseBoolean(line.substring(HEADER_SYNCED.length()));
                } else if (line.startsWith(HEADER_SOURCE_URL)) {
                    sourceUrl = line.substring(HEADER_SOURCE_URL.length());
                    if (sourceUrl.isEmpty()) {
                        sourceUrl = null;
                    }
                } else if (line.startsWith(HEADER_SONGWRITERS)) {
                    String value = line.substring(HEADER_SONGWRITERS.length());
                    if (!value.isEmpty()) {
                        songwriters = new ArrayList<>();
                        for (String s : value.split("␟")) {
                            if (!s.isEmpty()) {
                                songwriters.add(s);
                            }
                        }
                        if (songwriters.isEmpty()) {
                            songwriters = null;
                        }
                    }
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
            return new Lyrics(parsed, provider, synced, readEmbeddedRomanization(key),
                    null, null, songwriters, null, null, sourceUrl);
        } catch (Exception ex) {
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
                if (lyrics.sourceUrl() != null) {
                    fileLines.add(HEADER_SOURCE_URL + lyrics.sourceUrl());
                }
                if (lyrics.songwriters() != null && !lyrics.songwriters().isEmpty()) {
                    fileLines.add(HEADER_SONGWRITERS + String.join("␟", lyrics.songwriters()));
                }
                for (LyricsLine line : lyrics.lines()) {
                    fileLines.add(lyrics.synced()
                            ? LrcParser.formatLine(line)
                            : line.text());
                }
            }

            Files.write(file.toPath(), fileLines, StandardCharsets.UTF_8);
            trimDiskCache();
        } catch (IOException ex) {
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
            return null;
        }
    }
}
