/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedReader;
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

import app.morphe.extension.music.patches.lyrics.requests.CharactersConverter;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Normalizes YouTube Music metadata into what a lyrics database expects.
 *
 * <p>All cleanup is driven by the user-configured {@link Settings#LYRICS_CUSTOM_REGEX}.
 * When the regex is blank no filtering is applied.
 */
final class MetadataCleaner {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private static final ConcurrentMap<String, String> resolveCache = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ResolveTask> pendingResolves = new ConcurrentHashMap<>();
    private static final ExecutorService resolveExecutor = Executors.newCachedThreadPool();

    private MetadataCleaner() {
    }

    static String resolveSetting(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return value == null ? "" : value;
        }

        String trimmed = value.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return trimmed;
        }

        String cached = resolveCache.get(trimmed);
        if (cached != null) {
            return cached;
        }

        pendingResolves.computeIfAbsent(trimmed, ResolveTask::new).schedule();
        return "";
    }

    static String resolveSettingBlocking(@Nullable String value) {
        if (value == null || value.trim().isEmpty()) {
            return value == null ? "" : value;
        }

        String trimmed = value.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return trimmed;
        }

        String cached = resolveCache.get(trimmed);
        if (cached != null) {
            return cached;
        }

        ResolveTask task = pendingResolves.computeIfAbsent(trimmed, ResolveTask::new);
        task.schedule();

        task.await();
        cached = resolveCache.get(trimmed);
        if (cached != null) {
            return cached;
        }

        try {
            cached = download(trimmed);
            resolveCache.put(trimmed, cached);
            return cached;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to download setting: " + trimmed, ex);
            return trimmed;
        }
    }

    static String cleanTitle(@Nullable String title) {
        if (title == null) {
            return "";
        }
        return collapseWhitespace(applyRegex(title, resolveSetting(Settings.LYRICS_CUSTOM_REGEX.get())));
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
        return collapseWhitespace(applyRegex(clean, resolveSetting(Settings.LYRICS_CUSTOM_REGEX.get())));
    }

    static String cleanAlbum(@Nullable String album) {
        if (album == null) {
            return "";
        }
        return collapseWhitespace(applyRegex(album, resolveSetting(Settings.LYRICS_CUSTOM_REGEX.get())));
    }

    @Nullable
    private static volatile String cachedRegexSource;
    @Nullable
    private static volatile Pattern cachedRegex;

    static String applyRegex(String input, String regex) {
        if (regex == null || regex.trim().isEmpty()) {
            return input;
        }
        try {
            Pattern pattern = cachedRegex;
            if (pattern == null || !regex.equals(cachedRegexSource)) {
                pattern = Pattern.compile(regex);
                cachedRegex = pattern;
                cachedRegexSource = regex;
            }
            return pattern.matcher(CharactersConverter.normalizePreserveCase(input)).replaceAll("");
        } catch (Exception ex) {
            Logger.printDebug(() -> "Failed to apply regex", ex);
            return input;
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

    static String[] parseCleanTitleAndArtist(@Nullable String rawTitle, @Nullable String rawArtist) {
        return new String[]{ cleanArtist(rawArtist), cleanTitle(rawTitle) };
    }

    @Nullable
    static TrackInfo trustedDashSplit(@Nullable String rawTitle, @Nullable String rawArtist,
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
    static TrackInfo anyDashSplit(@Nullable String rawTitle, @Nullable String album,
                                  int durationSeconds) {
        String[] sides = splitDashRaw(rawTitle);
        if (sides == null) {
            return null;
        }
        return buildSplit(cleanTitle(sides[1]), cleanArtist(sides[0]), album, durationSeconds);
    }

    @Nullable
    static TrackInfo anyDashSplitReversed(@Nullable String rawTitle, @Nullable String album,
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
            {" & ", ", ", " x ", " X ", " feat. ", " feat ", " ft. ", " ft ", " с ", " 和 ", " 和 ", "/", "×"};

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

    static String[] splitArtists(String artist) {
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

    private static String collapseWhitespace(String value) {
        return value.replaceAll("\\s+", " ").trim();
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
                }
            }
            return sb.toString().trim();
        } finally {
            conn.disconnect();
        }
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
                latch.await(READ_TIMEOUT_MS + 1000, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ex) {
                Logger.printDebug(() -> "Interrupted waiting for resolve task", ex);
                Thread.currentThread().interrupt();
            }
        }
    }
}