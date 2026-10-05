/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.parsers.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Normalizes YouTube Music metadata into what a lyrics database expects.
 *
 * <p>All cleanup is driven by the user-configured {@link Settings#LYRICS_CUSTOM_REGEX}.
 * When the regex is blank no filtering is applied.
 */
public final class MetadataCleaner {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private static final int MAX_DOWNLOAD_CHARS = 256 * 1024;

    private static final int MAX_CACHED_PATTERNS = 8;

    private static final ConcurrentMap<String, String> resolveCache = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ResolveTask> pendingResolves = new ConcurrentHashMap<>();
    private static final ExecutorService resolveExecutor = Executors.newFixedThreadPool(1);
    private static final ConcurrentMap<String, Pattern> compiledPatterns = new ConcurrentHashMap<>();

    private MetadataCleaner() {
    }

    public static String resolveSetting(@Nullable String value) {
        SettingLookup lookup = classifySetting(value);
        if (!lookup.remote()) {
            return lookup.local();
        }
        if (lookup.local() == null) {
            pendingResolves.computeIfAbsent(lookup.trimmed(), ResolveTask::new).schedule();
            return "";
        }
        return lookup.local();
    }

    public static void resolveSettingBlocking(@Nullable String value) {
        SettingLookup lookup = classifySetting(value);
        if (!lookup.remote()) {
            return;
        }
        if (lookup.local() != null) {
            return;
        }

        ResolveTask task = pendingResolves.computeIfAbsent(lookup.trimmed(), ResolveTask::new);
        task.schedule();

        task.await();
        String cached = resolveCache.get(lookup.trimmed());
        if (cached != null) {
            return;
        }

        try {
            cached = download(lookup.trimmed());
            resolveCache.put(lookup.trimmed(), cached);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to download setting: " + lookup.trimmed(), ex);
        }
    }

    /**
     * Splits a setting value into the value a caller returns as is and whether it is a remote
     * URL that still has to be resolved; {@code local} holds the resolved answer in that case.
     */
    private record SettingLookup(String trimmed, boolean remote, @Nullable String local) {
    }

    private static SettingLookup classifySetting(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return new SettingLookup("", false, value == null ? "" : value);
        }

        String trimmed = value.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return new SettingLookup(trimmed, false, trimmed);
        }

        return new SettingLookup(trimmed, true, resolveCache.get(trimmed));
    }

    static String cleanTitle(@Nullable String title) {
        return cleanField(title);
    }

    static String cleanArtist(@Nullable String artist) {
        if (artist == null) {
            return "";
        }
        String clean = artist;

        // Multi artist strings such as "A, B & C" rarely match a database entry,
        // so only the first credited artist is used for the lookup.
        int separator = indexOfFirstSeparator(clean);
        if (separator > 0) {
            clean = clean.substring(0, separator);
        }
        return cleanField(clean);
    }

    public static String cleanAlbum(@Nullable String album) {
        return cleanField(album);
    }

    /** Null safe regex cleanup shared by the title, artist and album fields. */
    private static String cleanField(@Nullable String value) {
        if (value == null) {
            return "";
        }
        return collapseWhitespace(
                applyRegex(value, resolveSetting(Settings.LYRICS_CUSTOM_REGEX.get())));
    }

    static String applyRegex(String input, String regex) {
        if (regex == null || regex.trim().isEmpty()) {
            return input;
        }
        final Pattern pattern = compiled(regex);
        if (pattern == null) {
            return input;
        }
        try {
            return pattern.matcher(CharactersConverter.normalizePreserveCase(input)).replaceAll("");
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to apply regex", ex);
            return input;
        }
    }

    /**
     * Like {@link #applyRegex}, but a pattern that matches nothing returns the input
     * untouched instead of its normalized form. Lyrics line text passes through this
     * before display, so rebuilding an untouched line from the normalizer output would
     * fold its fullwidth punctuation ({@code ，！？～（）} …) to ASCII even though the
     * filter never fired on it.
     */
    public static String applyRegexPreserveOriginal(String input, String regex) {
        if (regex == null || regex.trim().isEmpty()) {
            return input;
        }
        final Pattern pattern = compiled(regex);
        if (pattern == null) {
            return input;
        }
        try {
            final String normalized = CharactersConverter.normalizePreserveCase(input);
            if (!pattern.matcher(normalized).find()) {
                return input;
            }
            return pattern.matcher(normalized).replaceAll("");
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to apply regex", ex);
            return input;
        }
    }

    @Nullable
    private static Pattern compiled(String regex) {
        try {
            Pattern pattern = compiledPatterns.get(regex);
            if (pattern == null) {
                pattern = Pattern.compile(regex);
                if (compiledPatterns.size() >= MAX_CACHED_PATTERNS) {
                    compiledPatterns.clear();
                }
                compiledPatterns.put(regex, pattern);
            }
            return pattern;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to apply regex", ex);
            return null;
        }
    }

    private static final String DASH_SEPARATOR = " - ";

    @Nullable
    private static String[] splitDashRaw(@Nullable String rawTitle) {
        if (rawTitle == null) {
            return null;
        }
        int idx = rawTitle.indexOf(DASH_SEPARATOR);
        if (idx <= 0 || idx >= rawTitle.length() - DASH_SEPARATOR.length()) {
            return null;
        }
        String left = rawTitle.substring(0, idx).trim();
        String right = rawTitle.substring(idx + DASH_SEPARATOR.length()).trim();
        if (left.isEmpty() || right.isEmpty()) {
            return null;
        }
        return new String[]{ left, right };
    }

    public static String[] parseCleanTitleAndArtist(@Nullable String rawTitle, @Nullable String rawArtist) {
        return new String[]{ cleanArtist(rawArtist), cleanTitle(rawTitle) };
    }

    @Nullable
    public static TrackInfo trustedDashSplit(@Nullable String rawTitle, @Nullable String rawArtist,
                                      @Nullable String album, int durationSeconds) {
        String[] sides = splitDashRaw(rawTitle);
        if (sides == null || rawArtist == null || rawArtist.trim().isEmpty()) {
            return null;
        }
        String left = sides[0];
        String right = sides[1];
        if (approxArtistEqual(left, rawArtist)) {
            return buildSplit(cleanTitle(right), cleanArtist(left), album, durationSeconds);
        }
        if (approxArtistEqual(right, rawArtist)) {
            return buildSplit(cleanTitle(left), cleanArtist(right), album, durationSeconds);
        }
        return null;
    }

    @Nullable
    public static TrackInfo anyDashSplit(@Nullable String rawTitle, @Nullable String album,
                                  int durationSeconds) {
        String[] sides = splitDashRaw(rawTitle);
        if (sides == null) {
            return null;
        }
        return buildSplit(cleanTitle(sides[1]), cleanArtist(sides[0]), album, durationSeconds);
    }

    @Nullable
    public static TrackInfo anyDashSplitReversed(@Nullable String rawTitle, @Nullable String album,
                                          int durationSeconds) {
        String[] sides = splitDashRaw(rawTitle);
        if (sides == null) {
            return null;
        }
        return buildSplit(cleanTitle(sides[0]), cleanArtist(sides[1]), album, durationSeconds);
    }

    @Nullable
    private static TrackInfo buildSplit(String title, String artist, String album,
                                        int durationSeconds) {
        if (title.isEmpty() || artist.isEmpty()) {
            return null;
        }
        return new TrackInfo(title, artist, album == null ? "" : album, durationSeconds);
    }

    private static boolean approxArtistEqual(String side, String rawArtist) {
        return LyricsRequests.containsEither(
                LyricsRequests.normalizeForMatch(side),
                LyricsRequests.normalizeForMatch(rawArtist));
    }

    private static final String[] ARTIST_SEPARATORS =
            {" & ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " с ", " 和 ", "/", "×"};

    private static int indexOfFirstSeparator(String artist) {
        int result = -1;
        for (String separator : ARTIST_SEPARATORS) {
            int index = artist.indexOf(separator);
            if (index > 0 && (result < 0 || index < result)) {
                result = index;
            }
        }
        return result;
    }

    public static String[] splitArtists(String artist) {
        String[] raw = artist.split("\\s*(?:和|&|feat\\.?|ft\\.?|,|/|×)\\s*");
        List<String> parts = new ArrayList<>();
        for (String part : raw) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                parts.add(trimmed);
            }
        }
        return parts.toArray(new String[0]);
    }

    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private static String collapseWhitespace(String value) {
        return WHITESPACE.matcher(value).replaceAll(" ").trim();
    }

    @NonNull
    private static String download(String urlStr) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(urlStr).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", "MorpheMusic/1.0");

        try {
            String charset = "UTF-8";
            String contentType = conn.getContentType();
            if (contentType != null) {
                for (String part : contentType.split(";")) {
                    part = part.trim();
                    if (part.regionMatches(true, 0, "charset=", 0, 8)) {
                        charset = part.substring(8).trim();
                        break;
                    }
                }
            }

            StringBuilder sb = createStringBuilder(conn, charset);
            return sb.toString().trim();
        } finally {
            conn.disconnect();
        }
    }

    @NonNull
    private static StringBuilder createStringBuilder(HttpURLConnection conn, String charset) throws IOException {
        StringBuilder sb = new StringBuilder(4096);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), charset))) {
            String line;
            while ((line = br.readLine()) != null) {
                //noinspection SizeReplaceableByIsEmpty
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line);
                if (sb.length() > MAX_DOWNLOAD_CHARS) {
                    break;
                }
            }
        }
        return sb;
    }

    private static final class ResolveTask {
        final String url;
        final CountDownLatch latch = new CountDownLatch(1);
        volatile boolean scheduled;

        ResolveTask(String url) {
            this.url = url;
        }

        void schedule() {
            if (scheduled) return;
            scheduled = true;
            resolveExecutor.execute(() -> {
                try {
                    String content = download(url);
                    resolveCache.put(url, content);
                } catch (Exception ex) {
                    Logger.printDebug(() -> "Failed to download URL: " + url, ex);
                } finally {
                    latch.countDown();
                }
            });
        }

        void await() {
            try {
                boolean completed = latch.await(READ_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
                if (!completed) {
                    Logger.printDebug(() -> "Timeout waiting for resolve task: " + url);
                }
            } catch (InterruptedException ex) {
                Logger.printDebug(() -> "Interrupted waiting for resolve task", ex);
                Thread.currentThread().interrupt();
            }
        }
    }
}
