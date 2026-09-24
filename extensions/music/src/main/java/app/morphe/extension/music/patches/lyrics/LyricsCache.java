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
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.requests.LrcParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Two level lyrics cache: an in memory map for the current session,
 * and a disk cache so that replaying a track needs no network.
 */
final class LyricsCache {

    private static final int MEMORY_ENTRIES = 200;
    private static final int MEMORY_MAX_ENTRIES = 500;

    /** Maximum number of files kept on disk. Older files are deleted first. */
    private static final int DISK_ENTRIES = 2000;

    private static final String DIRECTORY_NAME = "morphe_lyrics";
    private static final String HEADER_PROVIDER = "#provider=";
    private static final String HEADER_SYNCED = "#synced=";
    private static final String HEADER_SOURCE_URL = "#sourceUrl=";
    private static final String HEADER_SONGWRITERS = "#songwriters=";
    private static final String NOT_FOUND_MARKER = "#notfound";

    private static final Map<String, Lyrics> memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<String, Lyrics>(MEMORY_ENTRIES, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
                    return size() > MEMORY_MAX_ENTRIES;
                }
            });

    private LyricsCache() {
    }

    @Nullable
    static Lyrics get(TrackInfo track, String source) {
        return memoryCache.computeIfAbsent(key(track, source), LyricsCache::readFromDisk);
    }

    static void put(TrackInfo track, String source, Lyrics lyrics) {
        String key = key(track, source);
        memoryCache.put(key, lyrics);
        writeToDisk(key, lyrics);
        writeEmbeddedRomanization(key, lyrics.romanization());
    }

    @Nullable
    static List<String> getTranslation(TrackInfo track,
                                       String source,
                                       String language,
                                       int expectedLineCount) {
        return readStringList(translationFile(track, source, language), expectedLineCount);
    }

    static void putTranslation(TrackInfo track,
                                String source,
                                String language,
                                List<String> lines) {
        writeStringList(translationFile(track, source, language), lines);
    }

    @Nullable
    static List<String> getTranslationAI(TrackInfo track, String source,
            String language, int expectedLineCount) {
        return readStringList(aiTranslationFile(track, source, language), expectedLineCount);
    }

    static void putTranslationAI(TrackInfo track, String source,
            String language, List<String> lines) {
        writeStringList(aiTranslationFile(track, source, language), lines);
    }

    @Nullable
    static List<LyricsLine> getRomanization(TrackInfo track,
                                            String source,
                                            int expectedLineCount) {
        return readLyricsLineList(romanizationFile(track, source), expectedLineCount);
    }

    static void putRomanization(TrackInfo track,
                                String source,
                                List<LyricsLine> lines) {
        writeLyricsLineList(romanizationFile(track, source), lines);
    }

    @Nullable
    static List<LyricsLine> getRomanizationAI(TrackInfo track, String source,
            int expectedLineCount) {
        return readLyricsLineList(aiRomanizationFile(track, source), expectedLineCount);
    }

    static void putRomanizationAI(TrackInfo track, String source,
            List<LyricsLine> lines) {
        writeLyricsLineList(aiRomanizationFile(track, source), lines);
    }

    @Nullable
    private static List<String> readStringList(@Nullable File file, int expectedLineCount) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            return lines.size() == expectedLineCount ? lines : null;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read string list from cache", ex);
            return null;
        }
    }

    private static void writeStringList(@Nullable File file, List<String> lines) {
        if (file == null) {
            return;
        }
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            trimDiskCache();
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not write cache", ex);
        }
    }

    @Nullable
    private static List<LyricsLine> readLyricsLineList(@Nullable File file, int expectedLineCount) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.size() != expectedLineCount) {
                return null;
            }
            List<LyricsLine> result = new ArrayList<>(lines.size());
            for (String line : lines) {
                result.add(new LyricsLine(LyricsLine.NO_TIME, line));
            }
            return result;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read lyrics line list from cache", ex);
            return null;
        }
    }

    private static void writeLyricsLineList(@Nullable File file, List<LyricsLine> lines) {
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
            Logger.printDebug(() -> "Could not write cache", ex);
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
    private static File aiTranslationFile(TrackInfo track, String source, String language) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory,
                Integer.toHexString(key(track, source).hashCode()) + ".ai." + language + ".txt");
    }

    @Nullable
    private static File aiRomanizationFile(TrackInfo track, String source) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory,
                Integer.toHexString(key(track, source).hashCode()) + ".ai.rom.txt");
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
            Logger.printDebug(() -> "Could not read embedded romanization from cache", ex);
            return null;
        }
    }

    private static void writeEmbeddedRomanization(String key, @Nullable List<LyricsLine> romanization) {
        if (!LyricsMerge.hasText(romanization)) {
            return;
        }
        writeLyricsLineList(embeddedRomanizationFile(key), romanization);
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
            Logger.printDebug(() -> "Could not read lyrics from disk cache", ex);
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
            Logger.printDebug(() -> "Could not write the lyrics cache", ex);
        }
    }

    /**
     * Deletes the oldest files once the cache grows past {@link #DISK_ENTRIES}.
     * <p>
     * Providers are queried in parallel and each result is cached, so trims can overlap. A sort
     * needs keys that hold still, and a file written or deleted meanwhile changes its modification
     * time, so each time is read once before sorting. Trims also run one at a time, so two of them
     * never delete the files the other is ordering.
     */
    private static synchronized void trimDiskCache() {
        File directory = cacheDirectory();
        if (directory == null) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null || files.length <= DISK_ENTRIES) {
            return;
        }

        final long[] modified = new long[files.length];
        final Integer[] oldestFirst = new Integer[files.length];
        for (int i = 0; i < files.length; i++) {
            modified[i] = files[i].lastModified();
            oldestFirst[i] = i;
        }
        Arrays.sort(oldestFirst, Comparator.comparingLong(i -> modified[i]));

        final int deleteCount = files.length - DISK_ENTRIES;
        for (int i = 0; i < deleteCount; i++) {
            File file = files[oldestFirst[i]];
            if (!file.delete()) {
                Logger.printDebug(() -> "Could not delete a cached lyrics file: " + file);
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
            Logger.printDebug(() -> "Could not get cache directory", ex);
            return null;
        }
    }
}
