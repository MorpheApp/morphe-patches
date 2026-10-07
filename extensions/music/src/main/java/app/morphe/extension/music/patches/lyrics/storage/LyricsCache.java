/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.storage;

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
import java.util.function.Function;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.LyricsPreference;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LrcParser;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Two level lyrics cache: an in memory map for the current session,
 * and a disk cache so that replaying a track needs no network.
 */
public final class LyricsCache {

    private static final int MEMORY_ENTRIES = 200;
    private static final int MEMORY_MAX_ENTRIES = 500;

    /** Maximum number of files kept on disk. Older files are deleted first. */
    private static final int DISK_ENTRIES = 2000;

    private static final int TRIM_EVERY_WRITES = 64;

    private static int writesSinceTrim;

    private static final String DIRECTORY_NAME = "morphe_lyrics";
    private static final String HEADER_PROVIDER = "#provider=";
    private static final String HEADER_SYNCED = "#synced=";
    private static final String HEADER_SOURCE_URL = "#sourceUrl=";
    private static final String HEADER_FORMAT_TYPE = "#formatType=";
    private static final String HEADER_SONGWRITERS = "#songwriters=";
    private static final String HEADER_QUERY_TITLE = "#queryTitle=";
    private static final String HEADER_QUERY_ARTIST = "#queryArtist=";
    private static final String HEADER_QUEUE = "#queue=";
    private static final String HEADER_FINGERPRINT = "#fp=";
    private static final String HEADER_TRACK = "#track=";
    private static final String NOT_FOUND_MARKER = "#notfound";
    private static final String RAW_SUFFIX = ".raw";

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
    public static Lyrics get(TrackInfo track, String source) {
        final String key = key(track, source);
        final Lyrics cached = memoryCache.get(key);
        if (cached != null) {
            return cached;
        }
        final Lyrics read = readFromDisk(key);
        if (read != null) {
            memoryCache.put(key, read);
        }
        return read;
    }

    public static void put(TrackInfo track, String source, Lyrics lyrics) {
        String key = key(track, source);
        memoryCache.put(key, lyrics);
        writeToDisk(key, lyrics);
        writeEmbeddedRomanization(key, lyrics.romanization());
    }

    /**
     * The lyric the user landed on for this track, the order the remaining candidates were
     * left in, and the custom search terms that produced them. Read on a background thread.
     */
    @Nullable
    public static LyricsPreference getPreference(@Nullable String videoId, TrackInfo track) {
        File videoFile = preferenceFile(videoId, track);
        File trackFile = preferenceFile(null, track);
        File file = (videoFile != null && videoFile.exists()) ? videoFile : trackFile;
        if (file == null || !file.exists()) {
            Logger.printInfo(() -> "LyricsPref read miss: videoId=" + videoId
                    + " videoFile=" + name(videoFile) + " trackFile=" + name(file));
            return null;
        }
        final File source = file;
        Logger.printInfo(() -> "LyricsPref read: " + source.getName());

        LyricsPreference preference = readPreferenceFile(source, track);
        if (preference == null) {
            return null;
        }
        String expectedKey = trackKey(track);
        if (preference.trackKey() != null && !preference.trackKey().equals(expectedKey)) {
            if (!source.equals(videoFile) || trackFile == null || !trackFile.exists()
                    || trackFile.equals(videoFile)) {
                return null;
            }
            preference = readPreferenceFile(trackFile, track);
            if (preference == null) {
                return null;
            }
            if (preference.trackKey() != null && !preference.trackKey().equals(expectedKey)) {
                return null;
            }
        }
        return preference;
    }

    @Nullable
    private static LyricsPreference readPreferenceFile(File file, TrackInfo track) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }

            String queryTitle = null;
            String queryArtist = null;
            String fingerprint = null;
            String storedTrackKey = null;
            List<String> queue = new ArrayList<>();
            int contentStart = 0;

            for (String line : lines) {
                if (line.equals(NOT_FOUND_MARKER)) {
                    return null;
                }
                if (line.startsWith(HEADER_QUERY_TITLE)) {
                    queryTitle = emptyToNull(line.substring(HEADER_QUERY_TITLE.length()));
                } else if (line.startsWith(HEADER_QUERY_ARTIST)) {
                    queryArtist = emptyToNull(line.substring(HEADER_QUERY_ARTIST.length()));
                } else if (line.startsWith(HEADER_QUEUE)) {
                    String value = emptyToNull(line.substring(HEADER_QUEUE.length()));
                    if (value != null) {
                        queue.add(value);
                    }
                } else if (line.startsWith(HEADER_FINGERPRINT)) {
                    fingerprint = emptyToNull(line.substring(HEADER_FINGERPRINT.length()));
                } else if (line.startsWith(HEADER_TRACK)) {
                    storedTrackKey = emptyToNull(line.substring(HEADER_TRACK.length()));
                } else {
                    // The lyric's own headers and its content start here.
                    break;
                }
                contentStart++;
            }

            Lyrics preferred = parseContent(file, lines, contentStart, track);
            if (preferred == null || preferred == Lyrics.NOT_FOUND) {
                return null;
            }
            return new LyricsPreference(queryTitle, queryArtist, preferred,
                    List.copyOf(queue), fingerprint, storedTrackKey);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the lyrics preference", ex);
            return null;
        }
    }

    /**
     * Stores what the user picked for this track. Read and written on a background thread.
     * The lyric is written under the video id, which one playback never changes, and again
     * under the track alone, which answers while the video id is not known yet.
     */
    public static void putPreference(@Nullable String videoId, TrackInfo track,
                              @Nullable String queryTitle,
                              @Nullable String queryArtist, Lyrics preferred,
                              List<String> queue, @Nullable String fingerprint) {
        File file = preferenceFile(videoId, track);
        File fileByTrack = preferenceFile(null, track);
        if ((file == null && fileByTrack == null) || preferred == null
                || preferred == Lyrics.NOT_FOUND || preferred.isEmpty()) {
            return;
        }

        try {
            List<String> fileLines = new ArrayList<>();
            fileLines.add(HEADER_TRACK + trackKey(track));
            if (queryTitle != null && !queryTitle.isEmpty()) {
                fileLines.add(HEADER_QUERY_TITLE + queryTitle);
            }
            if (queryArtist != null && !queryArtist.isEmpty()) {
                fileLines.add(HEADER_QUERY_ARTIST + queryArtist);
            }
            for (String queueEntry : queue) {
                fileLines.add(HEADER_QUEUE + queueEntry);
            }
            if (fingerprint != null && !fingerprint.isEmpty()) {
                fileLines.add(HEADER_FINGERPRINT + fingerprint);
            }
            appendLyrics(fileLines, preferred);

            writePreferenceFile(file, fileLines);
            writeRaw(file, preferred.rawFormat());
            if (fileByTrack != null && !fileByTrack.equals(file)) {
                writePreferenceFile(fileByTrack, fileLines);
                writeRaw(fileByTrack, preferred.rawFormat());
            }
            noteCacheWrite();
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not write the lyrics preference", ex);
        }
    }

    private static void writePreferenceFile(@Nullable File file, List<String> fileLines)
            throws IOException {
        if (file == null) {
            return;
        }
        Files.write(file.toPath(), fileLines, StandardCharsets.UTF_8);
        Logger.printInfo(() -> "LyricsPref wrote: " + file.getName()
                + " lines=" + fileLines.size());
    }

    /** Appends the lyrics of a track to a cache file, provider headers first. */
    private static void appendLyrics(List<String> fileLines, Lyrics lyrics) {
        fileLines.add(HEADER_PROVIDER + lyrics.providerName());
        fileLines.add(HEADER_SYNCED + lyrics.synced());
        if (lyrics.sourceUrl() != null) {
            fileLines.add(HEADER_SOURCE_URL + lyrics.sourceUrl());
        }
        if (lyrics.formatType() != null) {
            fileLines.add(HEADER_FORMAT_TYPE + lyrics.formatType());
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

    @Nullable
    private static String name(@Nullable File file) {
        return file == null ? "null" : file.getName();
    }

    @Nullable
    public static List<String> getTranslation(TrackInfo track,
                                       String source,
                                       String language,
                                       List<String> sourceLines) {
        return readStringList(translationFile(track, source, language, sourceLines), sourceLines);
    }

    public static void putTranslation(TrackInfo track,
                                String source,
                                String language,
                                List<String> sourceLines,
                                List<String> lines) {
        writeStringList(translationFile(track, source, language, sourceLines), lines);
    }

    @Nullable
    public static List<String> getTranslationAI(TrackInfo track, String source,
            String language, List<String> sourceLines) {
        return readStringList(aiTranslationFile(track, source, language, sourceLines),
                sourceLines);
    }

    public static void putTranslationAI(TrackInfo track, String source,
            String language, List<String> sourceLines, List<String> lines) {
        writeStringList(aiTranslationFile(track, source, language, sourceLines), lines);
    }

    @Nullable
    public static List<LyricsLine> getRomanization(TrackInfo track,
                                            String source,
                                            List<String> sourceLines) {
        return readLyricsLineList(romanizationFile(track, source, sourceLines), sourceLines);
    }

    public static void putRomanization(TrackInfo track,
                                String source,
                                List<String> sourceLines,
                                List<LyricsLine> lines) {
        writeLyricsLineList(romanizationFile(track, source, sourceLines), lines);
    }

    @Nullable
    public static List<LyricsLine> getRomanizationAI(TrackInfo track, String source,
            List<String> sourceLines) {
        return readLyricsLineList(aiRomanizationFile(track, source, sourceLines), sourceLines);
    }

    public static void putRomanizationAI(TrackInfo track, String source,
            List<String> sourceLines, List<LyricsLine> lines) {
        writeLyricsLineList(aiRomanizationFile(track, source, sourceLines), lines);
    }

    @Nullable
    private static List<String> readStringList(@Nullable File file, List<String> sourceLines) {
        return readCachedLines(file, sourceLines.size(),
                "Could not read string list from cache", Function.identity());
    }

    private static void writeStringList(@Nullable File file, List<String> lines) {
        if (file == null) {
            return;
        }
        try {
            Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
            noteCacheWrite();
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not write cache", ex);
        }
    }

    @Nullable
    private static List<LyricsLine> readLyricsLineList(@Nullable File file,
            List<String> sourceLines) {
        return readCachedLines(file, sourceLines.size(),
                "Could not read lyrics line list from cache",
                line -> new LyricsLine(LyricsLine.NO_TIME, line));
    }

    /**
     * Reads a derived list that must line up with the lyrics it was computed for, dropping
     * the entry when the file holds a different number of lines.
     */
    @Nullable
    private static <T> List<T> readCachedLines(@Nullable File file, int expectedCount,
            String logTag, Function<String, T> mapper) {
        if (file == null || !file.exists()) {
            return null;
        }
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.size() != expectedCount) {
                discard(file);
                return null;
            }
            List<T> result = new ArrayList<>(lines.size());
            for (String line : lines) {
                result.add(mapper.apply(line));
            }
            return result;
        } catch (Exception ex) {
            Logger.printDebug(() -> logTag, ex);
            return null;
        }
    }

    /** Removes a derived entry that no longer matches the lyrics it was computed for. */
    private static void discard(File file) {
        if (file.delete()) {
            Logger.printDebug(() -> "Dropped a stale lyrics cache entry: " + file);
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
            noteCacheWrite();
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not write cache", ex);
        }
    }

    @Nullable
    private static File translationFile(TrackInfo track, String source, String language,
            List<String> sourceLines) {
        return derivedCacheFile(track, source, "." + language + ".txt", sourceLines);
    }

    @Nullable
    private static File romanizationFile(TrackInfo track, String source,
            List<String> sourceLines) {
        return derivedCacheFile(track, source, ".rom.txt", sourceLines);
    }

    @Nullable
    private static File aiTranslationFile(TrackInfo track, String source, String language,
            List<String> sourceLines) {
        return derivedCacheFile(track, source,
                ".ai." + aiConfigKey() + "." + language + ".txt", sourceLines);
    }

    @Nullable
    private static File aiRomanizationFile(TrackInfo track, String source,
            List<String> sourceLines) {
        return derivedCacheFile(track, source,
                ".ai." + aiConfigKey() + ".rom.txt", sourceLines);
    }

    private static String aiConfigKey() {
        String config = Settings.LYRICS_AI_BASE_URL.get() + '\n'
                + Settings.LYRICS_AI_MODEL.get() + '\n'
                + Settings.LYRICS_AI_PROMPT.get();
        return Integer.toHexString(config.hashCode());
    }

    @Nullable
    private static File derivedCacheFile(TrackInfo track, String source, String suffix,
            List<String> sourceLines) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory,
                Integer.toHexString(key(track, source).hashCode())
                        + "-" + contentKey(sourceLines) + suffix);
    }

    private static String contentKey(List<String> sourceLines) {
        long hash = 0xcbf29ce484222325L;
        for (String line : sourceLines) {
            if (line != null) {
                for (int i = 0; i < line.length(); i++) {
                    hash = (hash ^ line.charAt(i)) * 0x100000001b3L;
                }
            }
            hash = (hash ^ '\n') * 0x100000001b3L;
        }
        return Long.toHexString(hash);
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

    private static String trackKey(TrackInfo track) {
        return track.artist() + "␟" + track.title();
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

            Header header = parseHeaders(lines, 0);
            if (header.notFound()) {
                return Lyrics.NOT_FOUND;
            }

            String content = String.join("\n",
                    lines.subList(header.contentStart(), lines.size()));
            return parseContentLines(content, header.synced(), header.provider(),
                    header.songwriters(), header.sourceUrl(), header.formatType(),
                    readRaw(file), key);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read lyrics from disk cache", ex);
            return null;
        }
    }

    @Nullable
    private static Lyrics parseContent(File file, List<String> lines, int contentStart,
            TrackInfo track) throws Exception {
        Header header = parseHeaders(lines, contentStart);
        String content = String.join("\n",
                lines.subList(header.contentStart(), lines.size()));
        return parseContentLines(content, header.synced(), header.provider(),
                header.songwriters(), header.sourceUrl(), header.formatType(), readRaw(file),
                key(track, header.provider()));
    }

    /** The header lines of a cached lyrics file and the index its content starts at. */
    private record Header(boolean notFound, String provider, boolean synced,
                          @Nullable String sourceUrl, @Nullable String formatType,
                          @Nullable List<String> songwriters, int contentStart) {
    }

    private static Header parseHeaders(List<String> lines, int from) {
        String provider = "";
        boolean synced = false;
        String sourceUrl = null;
        String formatType = null;
        List<String> songwriters = null;
        int contentStart = from;
        boolean notFound = false;

        for (int i = from; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals(NOT_FOUND_MARKER)) {
                notFound = true;
                contentStart = i;
                break;
            }
            if (line.startsWith(HEADER_PROVIDER)) {
                provider = line.substring(HEADER_PROVIDER.length());
            } else if (line.startsWith(HEADER_SYNCED)) {
                synced = Boolean.parseBoolean(line.substring(HEADER_SYNCED.length()));
            } else if (line.startsWith(HEADER_SOURCE_URL)) {
                sourceUrl = emptyToNull(line.substring(HEADER_SOURCE_URL.length()));
            } else if (line.startsWith(HEADER_FORMAT_TYPE)) {
                formatType = emptyToNull(line.substring(HEADER_FORMAT_TYPE.length()));
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
                contentStart = i;
                break;
            }
            contentStart = i + 1;
        }
        return new Header(notFound, provider, synced, sourceUrl, formatType, songwriters,
                contentStart);
    }

    @Nullable
    private static Lyrics parseContentLines(String content, boolean synced, String provider,
                                            @Nullable List<String> songwriters,
                                            @Nullable String sourceUrl,
                                            @Nullable String formatType,
                                            @Nullable String rawFormat, String key) {
        List<LyricsLine> parsed = synced
                ? LrcParser.parseSynced(content)
                : LrcParser.parsePlain(content);
        if (parsed.isEmpty()) {
            return null;
        }
        return new Lyrics(parsed, provider, synced, readEmbeddedRomanization(key),
                null, null, songwriters, rawFormat, formatType, sourceUrl);
    }

    @Nullable
    private static String emptyToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
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
                appendLyrics(fileLines, lyrics);
            }

            Files.write(file.toPath(), fileLines, StandardCharsets.UTF_8);
            writeRaw(file, lyrics.rawFormat());
            noteCacheWrite();
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not write the lyrics cache", ex);
        }
    }

    private static File rawFile(File file) {
        return new File(file.getParentFile(), file.getName() + RAW_SUFFIX);
    }

    private static void writeRaw(@Nullable File file, @Nullable String raw) {
        if (file == null) {
            return;
        }
        File sidecar = rawFile(file);
        try {
            if (raw == null || raw.isEmpty()) {
                Files.deleteIfExists(sidecar.toPath());
                return;
            }
            Files.write(sidecar.toPath(), raw.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not write the lyrics raw cache", ex);
        }
    }

    @Nullable
    private static String readRaw(File file) {
        File sidecar = rawFile(file);
        if (!sidecar.exists()) {
            return null;
        }
        try {
            String raw = new String(Files.readAllBytes(sidecar.toPath()),
                    StandardCharsets.UTF_8);
            return raw.isEmpty() ? null : raw;
        } catch (IOException ex) {
            Logger.printDebug(() -> "Could not read the lyrics raw cache", ex);
            return null;
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
    private static synchronized void noteCacheWrite() {
        if (++writesSinceTrim < TRIM_EVERY_WRITES) {
            return;
        }
        writesSinceTrim = 0;
        trimDiskCache();
    }

    private static synchronized void trimDiskCache() {
        File directory = cacheDirectory();
        if (directory == null) {
            return;
        }

        File[] files = directory.listFiles();
        if (files == null) {
            return;
        }

        List<File> mains = new ArrayList<>(files.length);
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(RAW_SUFFIX)) {
                if (!new File(directory, name.substring(0, name.length() - RAW_SUFFIX.length()))
                        .exists()) {
                    discard(file);
                }
                continue;
            }
            mains.add(file);
        }
        if (mains.size() <= DISK_ENTRIES) {
            return;
        }

        final long[] modified = new long[mains.size()];
        final Integer[] oldestFirst = new Integer[mains.size()];
        for (int i = 0; i < mains.size(); i++) {
            modified[i] = mains.get(i).lastModified();
            oldestFirst[i] = i;
        }
        Arrays.sort(oldestFirst, Comparator.comparingLong(i -> modified[i]));

        final int deleteCount = mains.size() - DISK_ENTRIES;
        for (int i = 0; i < deleteCount; i++) {
            File file = mains.get(oldestFirst[i]);
            File sidecar = rawFile(file);
            if (sidecar.exists() && !sidecar.delete()) {
                Logger.printDebug(() -> "Could not delete a cached lyrics file: " + sidecar);
            }
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

    /**
     * The preference of a video id, or of the track alone while the video id is not known yet.
     * The playing track is rebuilt from two sources that can describe it differently, so the
     * track alone cannot be the name a later playback answers from.
     */
    @Nullable
    private static File preferenceFile(@Nullable String videoId, TrackInfo track) {
        File directory = cacheDirectory();
        if (directory == null) {
            return null;
        }
        // Track titles contain characters that are not valid in file names.
        String name = (videoId == null || videoId.isEmpty())
                ? key(track, "PREF")
                : "VID|" + videoId + "|PREF";
        return new File(directory, Integer.toHexString(name.hashCode()) + ".pref");
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
