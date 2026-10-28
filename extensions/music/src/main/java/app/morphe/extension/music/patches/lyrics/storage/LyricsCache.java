/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.storage;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import app.morphe.extension.music.patches.lyrics.LyricsDirectories;
import app.morphe.extension.music.patches.lyrics.derive.OpenAIClient;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.LyricsPreference;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Two level lyrics store: an in memory map for the current session, and on disk so that replaying
 * a track needs no network and works when there is none. The disk half is trimmed to a fixed
 * number of entries.
 */
public final class LyricsCache {

    private static final String TTML_FORMAT_TYPE = "ttml";
    private static final int MEMORY_INITIAL_CAPACITY = 200;
    private static final int MEMORY_MAX_ENTRIES = 500;

    private static final int DISK_ENTRIES = 5000;

    private static final int TRIM_EVERY_WRITES = 64;

    private static int writesSinceTrim;

    private static final String HEADER_PROVIDER = "#provider=";
    private static final String HEADER_SYNCED = "#synced=";
    private static final String HEADER_SOURCE_URL = "#sourceUrl=";
    private static final String HEADER_FORMAT_TYPE = "#formatType=";
    private static final String HEADER_SONGWRITERS = "#songwriters=";
    private static final String HEADER_ABOUT = "#about=";
    private static final String HEADER_QUERY_TITLE = "#queryTitle=";
    private static final String HEADER_QUERY_ARTIST = "#queryArtist=";
    private static final String HEADER_QUEUE = "#queue=";
    private static final String HEADER_FINGERPRINT = "#fp=";
    private static final String HEADER_VIDEO_ID = "#videoId=";
    private static final String HEADER_TRACK = "#track=";
    private static final String NOT_FOUND_MARKER = "#notfound";
    private static final String RAW_SUFFIX = ".raw";

    private static final Map<String, Lyrics> memoryCache = Collections.synchronizedMap(
            new LinkedHashMap<>(MEMORY_INITIAL_CAPACITY, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Lyrics> eldest) {
                    return size() > MEMORY_MAX_ENTRIES;
                }
            });

    /**
     * The video each entry was stored for, per cache key, mirroring {@link #memoryCache}. Null
     * means the entry was written without one and stays valid for whatever plays next.
     */
    private static final Map<String, String> videoIdByKey =
            Collections.synchronizedMap(new HashMap<>());

    private LyricsCache() {
    }

    @Nullable
    public static Lyrics get(TrackInfo track, String source, @Nullable String videoId) {
        String key = key(track, source);
        Lyrics lyrics = memoryCache.get(key);
        if (lyrics == null) {
            lyrics = readFromDisk(key);
            if (lyrics != null) {
                memoryCache.put(key, lyrics);
            }
        }
        return sameVideo(videoIdByKey.get(key), videoId) ? lyrics : null;
    }

    public static void put(TrackInfo track, String source, Lyrics lyrics,
                           @Nullable String videoId) {
        String key = key(track, source);
        if (videoId != null && !videoId.isEmpty()) {
            videoIdByKey.put(key, videoId);
        } else {
            videoIdByKey.remove(key);
        }
        memoryCache.put(key, lyrics);
        writeToDisk(key, lyrics);
        discardEmbedded(key);
        writeEmbedded(key, lyrics);
    }

    /**
     * Whether an entry stored for {@code storedVideoId} may answer the video that plays now.
     * The same metadata is served by more than one upload, and the providers keyed by video id
     * would otherwise hand back the lyrics of the one that stopped playing.
     */
    private static boolean sameVideo(@Nullable String storedVideoId,
                                     @Nullable String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return true;
        }
        return storedVideoId == null || storedVideoId.equals(videoId);
    }

    private static void writeEmbedded(String key, Lyrics lyrics) {
        if (LyricsMerge.hasText(lyrics.romanization())) {
            writeLyricsLineList(embeddedRomanizationFile(key), lyrics.romanization());
        }
        writeEmbeddedTranslations(key, lyrics.translations());
    }

    /**
     * Drops the sidecars a previous write left under the key. They describe the lines that
     * write held, so one that survives into a different set of lines would be read back as
     * if it belonged to it.
     */
    private static void discardEmbedded(String key) {
        discardEmbeddedFile(embeddedRomanizationFile(key));
        for (String language : embeddedTranslationLanguages(key)) {
            discardEmbeddedFile(embeddedTranslationsFile(key, language));
        }
    }

    private static void discardEmbeddedFile(@Nullable File file) {
        if (file != null && file.exists()) {
            discard(file);
        }
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
        File source = file;
        Logger.printInfo(() -> "LyricsPref read: " + source.getName());

        LyricsPreference preference = readPreferenceFile(source,
                source.equals(trackFile) ? preferenceKey(null, track) : preferenceKey(videoId, track));
        if (preference == null || !sameVideo(preference.videoId(), videoId)) {
            return null;
        }
        String expectedKey = trackKey(track);
        if (preference.trackKey() != null && !preference.trackKey().equals(expectedKey)) {
            if (!source.equals(videoFile) || trackFile == null || !trackFile.exists()
                    || trackFile.equals(videoFile)) {
                return null;
            }
            preference = readPreferenceFile(trackFile, preferenceKey(null, track));
            if (preference == null || !sameVideo(preference.videoId(), videoId)) {
                return null;
            }
            if (preference.trackKey() != null && !preference.trackKey().equals(expectedKey)) {
                return null;
            }
        }
        return preference;
    }

    @Nullable
    private static LyricsPreference readPreferenceFile(File file, String prefKey) {
        try {
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            if (lines.isEmpty()) {
                return null;
            }

            String queryTitle = null;
            String queryArtist = null;
            String fingerprint = null;
            String storedTrackKey = null;
            String videoId = null;
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
                } else if (line.startsWith(HEADER_VIDEO_ID)) {
                    videoId = emptyToNull(line.substring(HEADER_VIDEO_ID.length()));
                } else if (line.startsWith(HEADER_TRACK)) {
                    storedTrackKey = emptyToNull(line.substring(HEADER_TRACK.length()));
                } else {
                    // The lyric's own headers and its content start here.
                    break;
                }
                contentStart++;
            }

            Lyrics preferred = parseContent(file, lines, contentStart, prefKey);
            if (preferred == null || preferred == Lyrics.NOT_FOUND) {
                return null;
            }
            return new LyricsPreference(queryTitle, queryArtist, preferred,
                    List.copyOf(queue), fingerprint, storedTrackKey, videoId);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the lyrics preference", ex);
            return null;
        }
    }

    /**
     * Stores what the user picked for this track. Read and written on a background thread.
     * The lyric is written under the video id, which one playback never changes, and again
     * under the track alone, which answers while the video id is not known yet. Both name
     * the video they were written for, so neither answers a different upload of the same
     * metadata, whose lyrics are the provider's to pick again.
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
            appendLyrics(fileLines, preferred, videoId);

            writePreference(videoId, track, file, fileLines, preferred);
            if (fileByTrack != null && !fileByTrack.equals(file)) {
                writePreference(null, track, fileByTrack, fileLines, preferred);
            }
            noteCacheWrite();
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not write the lyrics preference", ex);
        }
    }

    private static void appendVideoId(List<String> fileLines, @Nullable String videoId) {
        if (videoId != null && !videoId.isEmpty()) {
            fileLines.add(HEADER_VIDEO_ID + videoId);
        }
    }

    /**
     * Writes the preference file and the translations and romanization that go with it. The
     * sidecars are dropped first, so a write that only gets as far as the file leaves the
     * content without them instead of with the ones of an older, differently filtered lyric.
     */
    private static void writePreference(@Nullable String videoId, TrackInfo track,
                                        @Nullable File file, List<String> fileLines,
                                        Lyrics preferred) throws IOException {
        if (file == null) {
            return;
        }
        String prefKey = preferenceKey(videoId, track);
        discardEmbedded(prefKey);
        writePreferenceFile(file, fileLines);
        writeRaw(file, preferred.rawFormat());
        writeEmbedded(prefKey, preferred);
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
    private static void appendLyrics(List<String> fileLines, Lyrics lyrics,
                                     @Nullable String videoId) {
        appendVideoId(fileLines, videoId);
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
        // Paragraphs split on a character no line can hold: an unescaped newline would end
        // the headers and the description would read back as lyrics.
        if (lyrics.about() != null && !lyrics.about().isEmpty()) {
            fileLines.add(HEADER_ABOUT + lyrics.about().replace("\n", "␟"));
        }
        // Unsynced lyrics are bare text and the section label is not part of it: the parser
        // moved it into LyricsLine.songPart(), so it is written back as its own heading line
        // before each section's first line, the shape parsePlain reads.
        String pendingPart = null;
        for (LyricsLine line : lyrics.lines()) {
            if (lyrics.synced()) {
                fileLines.add(LRCParser.formatLine(line));
                continue;
            }
            String songPart = line.songPart();
            if (songPart != null && !songPart.isEmpty() && !songPart.equals(pendingPart)) {
                fileLines.add("[" + songPart + "]");
                pendingPart = songPart;
            }
            fileLines.add(line.text());
        }
    }

    private static String name(@Nullable File file) {
        return file == null ? "null" : file.getName();
    }

    @Nullable
    private static List<String> sanitizeTranslationList(@Nullable List<String> list, @Nullable File file) {
        if (list == null) return null;
        boolean anyMeaningful = false;
        List<String> sanitized = new ArrayList<>(list.size());
        for (String s : list) {
            if (OpenAIClient.isSkipToken(s)) {
                sanitized.add("");
            } else {
                sanitized.add(s);
                anyMeaningful = true;
            }
        }
        if (!anyMeaningful) {
            if (file != null) discard(file);
            return null;
        }
        return sanitized;
    }

    @Nullable
    public static List<String> getTranslation(TrackInfo track,
                                       String source,
                                       String language,
                                       List<String> sourceLines) {
        File file = translationFile(track, source, language, sourceLines);
        List<String> raw = readStringList(file, sourceLines);
        return sanitizeTranslationList(raw, file);
    }

    public static void putTranslation(TrackInfo track,
                                String source,
                                String language,
                                List<String> sourceLines,
                                List<String> lines) {
        File file = translationFile(track, source, language, sourceLines);
        List<String> clean = sanitizeTranslationList(lines, null);
        if (clean != null) {
            writeStringList(file, clean);
        } else if (file != null) {
            discard(file);
        }
    }

    @Nullable
    public static List<String> getTranslationAI(TrackInfo track, String source,
            String language, List<String> sourceLines) {
        File file = aiTranslationFile(track, source, language, sourceLines);
        List<String> raw = readStringList(file, sourceLines);
        return sanitizeTranslationList(raw, file);
    }

    public static void putTranslationAI(TrackInfo track, String source,
            String language, List<String> sourceLines, List<String> lines) {
        File file = aiTranslationFile(track, source, language, sourceLines);
        List<String> clean = sanitizeTranslationList(lines, null);
        if (clean != null) {
            writeStringList(file, clean);
        } else if (file != null) {
            discard(file);
        }
    }

    @Nullable
    public static List<String> getSectionAI(TrackInfo track, String source,
            List<String> sourceLines) {
        return readStringList(aiSectionFile(track, source, sourceLines), sourceLines);
    }

    public static void putSectionAI(TrackInfo track, String source,
            List<String> sourceLines, List<String> sections) {
        writeStringList(aiSectionFile(track, source, sourceLines), sections);
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
        // Only a chosen language changes the result, so "automatic" keeps the file it had.
        String language = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        return derivedCacheFile(track, source,
                LyricsRequests.isDefaultRomanizationLanguage()
                        ? ".rom.txt" : ".rom." + language.toLowerCase(Locale.ROOT) + ".txt",
                sourceLines);
    }

    @Nullable
    private static File aiTranslationFile(TrackInfo track, String source, String language,
            List<String> sourceLines) {
        return derivedCacheFile(track, source,
                ".ai." + aiConfigKey(Settings.LYRICS_AI_TRANSLATION_PROMPT.get())
                        + "." + language + ".txt", sourceLines);
    }

    @Nullable
    private static File aiSectionFile(TrackInfo track, String source,
            List<String> sourceLines) {
        return derivedCacheFile(track, source,
                ".ai." + aiConfigKey(Settings.LYRICS_AI_SECTION_PROMPT.get()) + ".sec.txt",
                sourceLines);
    }

    @Nullable
    private static File aiRomanizationFile(TrackInfo track, String source,
            List<String> sourceLines) {
        // The prompt names the target language, so one file per language is needed.
        return derivedCacheFile(track, source,
                ".ai." + aiConfigKey(Settings.LYRICS_AI_ROMANIZATION_PROMPT.get())
                        + ".rom." + LyricsRequests.romanizationLanguage() + ".txt", sourceLines);
    }

    private static String aiConfigKey(String prompt) {
        String config = Settings.LYRICS_AI_BASE_URL.get() + '\n'
                + Settings.LYRICS_AI_MODEL.get() + '\n'
                + prompt;
        return Integer.toHexString(config.hashCode());
    }

    @Nullable
    private static File derivedCacheFile(TrackInfo track, String source, String suffix,
            List<String> sourceLines) {
        File directory = offlineDirectory();
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
        File directory = offlineDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory, Integer.toHexString(key.hashCode()) + ".rombed.txt");
    }

    @Nullable
    private static File embeddedTranslationsFile(String key, String language) {
        File directory = offlineDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory, Integer.toHexString(key.hashCode())
                + ".trans." + sanitizeFileComponent(language) + ".txt");
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private static String sanitizeFileComponent(String value) {
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            sb.append(Character.isLetterOrDigit(c) || c == '-' ? c : '_');
        }
        return sb.length() == 0 ? "und" : sb.toString();
    }

    @Nullable
    private static Map<String, List<LyricsLine>> readEmbeddedTranslations(String key) {
        Map<String, List<LyricsLine>> result = new HashMap<>();
        for (String language : embeddedTranslationLanguages(key)) {
            File file = embeddedTranslationsFile(key, language);
            if (file == null || !file.exists()) {
                continue;
            }
            try {
                List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
                if (lines.isEmpty()) {
                    continue;
                }
                List<LyricsLine> parsed = new ArrayList<>(lines.size());
                for (String line : lines) {
                    parsed.add(new LyricsLine(LyricsLine.NO_TIME, line));
                }
                result.put(language, parsed);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not read embedded translations from cache", ex);
            }
        }
        return result.isEmpty() ? null : result;
    }

    private static List<String> embeddedTranslationLanguages(String key) {
        File directory = offlineDirectory();
        if (directory == null) {
            return List.of();
        }
        String prefix = Integer.toHexString(key.hashCode()) + ".trans.";
        File[] files = directory.listFiles((dir, name) ->
                name.startsWith(prefix) && name.endsWith(".txt"));
        if (files == null) {
            return List.of();
        }
        List<String> languages = new ArrayList<>(files.length);
        for (File file : files) {
            String name = file.getName();
            languages.add(name.substring(prefix.length(), name.length() - ".txt".length()));
        }
        return languages;
    }

    private static void writeEmbeddedTranslations(String key,
                                                  @Nullable Map<String, List<LyricsLine>> translations) {
        if (translations == null) {
            return;
        }
        for (Map.Entry<String, List<LyricsLine>> entry : translations.entrySet()) {
            List<LyricsLine> lines = entry.getValue();
            if (!LyricsMerge.hasText(lines)) {
                continue;
            }
            writeLyricsLineList(embeddedTranslationsFile(key, entry.getKey()), lines);
        }
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
            if (header.videoId() != null) {
                videoIdByKey.put(key, header.videoId());
            }
            if (header.notFound()) {
                return Lyrics.NOT_FOUND;
            }

            String content = String.join("\n",
                    lines.subList(header.contentStart(), lines.size()));
            Lyrics lyrics = parseContentLines(content, header.synced(), header.provider(),
                    header.songwriters(), header.sourceUrl(), header.formatType(), readRaw(file),
                    key, header.about());
            if (lyrics != null && isCorruptedPetitLyrics(header.provider(), lyrics)) {
                discard(file);
                return null;
            }
            return lyrics;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read lyrics from disk cache", ex);
            return null;
        }
    }

    private static boolean isCorruptedPetitLyrics(@Nullable String provider, Lyrics lyrics) {
        if (provider == null || (!provider.equalsIgnoreCase("PetitLyrics") && !provider.toLowerCase(Locale.ROOT).contains("petitlyrics"))) {
            return false;
        }
        int singleLetterTokens = 0;
        int totalTokens = 0;
        for (LyricsLine line : lyrics.lines()) {
            if (line.words() != null && !line.words().isEmpty()) {
                for (Word w : line.words()) {
                    String wt = w.text();
                    if (wt != null && !wt.isEmpty()) {
                        totalTokens++;
                        if (wt.length() == 1 && Character.isLetter(wt.charAt(0))
                                && !ScriptDetector.containsCjk(wt)) {
                            singleLetterTokens++;
                        }
                    }
                }
            }
        }
        return totalTokens >= 8 && ((float) singleLetterTokens / totalTokens) > 0.35f;
    }

    @Nullable
    private static Lyrics parseContent(File file, List<String> lines, int contentStart,
                                       String sidecarKey) {
        Header header = parseHeaders(lines, contentStart);
        String content = String.join("\n",
                lines.subList(header.contentStart(), lines.size()));
        return parseContentLines(content, header.synced(), header.provider(),
                header.songwriters(), header.sourceUrl(), header.formatType(), readRaw(file),
                sidecarKey, header.about());
    }

    /** The header lines of a cached lyrics file and the index its content starts at. */
    private record Header(boolean notFound, String provider, boolean synced,
                          @Nullable String sourceUrl, @Nullable String formatType,
                          @Nullable List<String> songwriters, @Nullable String about,
                          @Nullable String videoId, int contentStart) {
    }

    private static Header parseHeaders(List<String> lines, int from) {
        String provider = "";
        boolean synced = false;
        String sourceUrl = null;
        String formatType = null;
        List<String> songwriters = null;
        String about = null;
        String videoId = null;
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
            } else if (line.startsWith(HEADER_VIDEO_ID)) {
                videoId = emptyToNull(line.substring(HEADER_VIDEO_ID.length()));
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
            } else if (line.startsWith(HEADER_ABOUT)) {
                String value = line.substring(HEADER_ABOUT.length());
                if (!value.isEmpty()) {
                    about = value.replace('␟', '\n');
                }
            } else {
                contentStart = i;
                break;
            }
            contentStart = i + 1;
        }
        return new Header(notFound, provider, synced, sourceUrl, formatType, songwriters, about,
                videoId, contentStart);
    }

    @Nullable
    private static Lyrics parseContentLines(String content, boolean synced, String provider,
                                            @Nullable List<String> songwriters,
                                            @Nullable String sourceUrl,
                                            @Nullable String formatType,
                                            @Nullable String rawFormat, String key,
                                            @Nullable String about) {
        // An LRC line cannot hold a reading on the word or the reading keyed by language, and
        // both are gone for good once written as one. Reading the file back brings them.
        if (TTML_FORMAT_TYPE.equals(formatType) && rawFormat != null && !rawFormat.isEmpty()) {
            Lyrics reread = TTMLParser.ttmlToLyrics(rawFormat, provider, sourceUrl);
            if (reread != null && !reread.isEmpty()) {
                return withAbout(reread, about);
            }
        }

        List<LyricsLine> parsed = synced
                ? LRCParser.parseSynced(content)
                : LRCParser.parsePlain(content);
        if (parsed.isEmpty()) {
            return null;
        }
        return new Lyrics(parsed, provider, synced,
                alignedSidecar(readEmbeddedRomanization(key), parsed.size()),
                alignedSidecarMap(readEmbeddedTranslations(key), parsed.size()),
                null, songwriters, rawFormat, formatType, sourceUrl, about);
    }

    /** The description was never in the file, so it is carried over rather than dropped. */
    private static Lyrics withAbout(Lyrics lyrics, @Nullable String about) {
        if (about == null || about.equals(lyrics.about())) {
            return lyrics;
        }
        return new Lyrics(lyrics.lines(), lyrics.providerName(), lyrics.synced(),
                lyrics.romanization(), lyrics.translations(), lyrics.romanizations(),
                lyrics.songwriters(), lyrics.rawFormat(), lyrics.formatType(),
                lyrics.sourceUrl(), about);
    }

    /**
     * A sidecar is read by line index, so one that does not hold an entry for every line
     * describes a different write and would put a reading under the wrong line. It is dropped
     * instead, which leaves the lyric to be fetched again rather than shown one line off.
     */
    @Nullable
    private static List<LyricsLine> alignedSidecar(@Nullable List<LyricsLine> lines, int lineCount) {
        return lines != null && lines.size() == lineCount ? lines : null;
    }

    @Nullable
    private static Map<String, List<LyricsLine>> alignedSidecarMap(
            @Nullable Map<String, List<LyricsLine>> byLang, int lineCount) {
        if (byLang == null || byLang.isEmpty()) {
            return byLang;
        }
        Map<String, List<LyricsLine>> result = null;
        for (Map.Entry<String, List<LyricsLine>> entry : byLang.entrySet()) {
            List<LyricsLine> lines = alignedSidecar(entry.getValue(), lineCount);
            if (lines == null) {
                Logger.printDebug(() -> "Dropped a cached translation that no longer fits: "
                        + entry.getKey());
                continue;
            }
            if (result == null) {
                result = new HashMap<>(byLang.size());
            }
            result.put(entry.getKey(), lines);
        }
        return result;
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
            String videoId = videoIdByKey.get(key);
            if (lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
                // The marker ends the headers, so the video has to be named before it.
                appendVideoId(fileLines, videoId);
                fileLines.add(NOT_FOUND_MARKER);
            } else {
                appendLyrics(fileLines, lyrics, videoId);
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
            //noinspection ReadWriteStringCanBeUsed
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
            //noinspection ReadWriteStringCanBeUsed
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
        File directory = offlineDirectory();
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

        long[] modified = new long[mains.size()];
        Integer[] oldestFirst = new Integer[mains.size()];
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
        File directory = offlineDirectory();
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
        File directory = offlineDirectory();
        if (directory == null) {
            return null;
        }
        return new File(directory, preferenceFileName(videoId, track));
    }

    private static String preferenceFileName(@Nullable String videoId, TrackInfo track) {
        return Integer.toHexString(preferenceKey(videoId, track).hashCode()) + ".pref";
    }

    /**
     * The key the preference of this track is stored under. Its sidecars are keyed by it too,
     * which is what keeps them apart from the ones the main cache entry carries: the preference
     * holds the filtered lyric and the main entry the one the provider returned, so the two
     * have a different number of lines and cannot share a file.
     */
    private static String preferenceKey(@Nullable String videoId, TrackInfo track) {
        return (videoId == null || videoId.isEmpty())
                ? key(track, "PREF")
                : "VID|" + videoId + "|PREF";
    }

    /**
     * Empties the stored lyrics.
     *
     * <p>The dictionaries sit beside them and cost tens of megabytes and a download apiece to have
     * back, so they are left alone. A track asked for afterwards fetches as it did the first time.
     *
     * @return how many bytes were freed.
     */
    public static long clear() {
        long freed = 0;
        File directory = offlineDirectory();
        File[] files = directory == null ? null : directory.listFiles();
        if (files != null) {
            for (File file : files) {
                freed += file.isDirectory() ? 0 : file.length();
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        memoryCache.clear();
        return freed;
    }

    @Nullable
    private static File offlineDirectory() {
        return LyricsDirectories.directory(LyricsDirectories.OFFLINE);
    }
}
