/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3877
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/** Lyrics from lrc.red, whose song document embeds the TTML the song page renders from. */
public final class LrcRedProvider implements LyricsProvider {

    public static final String NAME = "LrcRed";

    private static final String API_BASE = "https://lrc.red";
    /** Written only when the model produced the timing, so an absent key means no. */
    private static final String BY_MODEL_KEY = "byModel";
    /** Credit line shown under the lyrics once the model has re-timed them. */
    private static final String MODEL_CREDIT = "Synced by lrc.red";
    private static final String ALBUM_PREFIX = "Album: ";
    private static final String ARTIST_PREFIX = "Artist: ";
    private static final int SYNC_CONNECT_TIMEOUT_MS = 10_000;
    /** The model answers in 0.5s to 15s, past the shared five second read timeout. */
    private static final int SYNC_READ_TIMEOUT_MS = 60_000;
    /** The service rate limits clients that fire requests back to back. */
    private static final long SYNC_MIN_INTERVAL_MS = 1_000;
    private static final long SYNC_RETRY_DELAY_MS = 2_000;
    /** Part of the error body the service sends when pulling the recording failed for now. */
    private static final String SYNC_RETRYABLE_ERROR = "give it a moment";
    /** A sweep fails for several songs at once, so the failure is throttled. */
    private static final long SYNC_ERROR_MIN_INTERVAL_MS = 15_000;

    private static final String[] MODIFIER_KEYWORDS = {
            "slowed", "reverb", "remix", "mixed", "dj mix", "mashup",
            "cover", "tribute", "karaoke", "instrumental", "acoustic",
            "sped up", "speed up", "live", "edit", "versión", "version",
            "bpm", "guitar", "piano", "lofi", "lo-fi", "nightcore",
            "club mix", "extended mix", "radio edit"
    };

    private static final Pattern BRACKETED_TAGS = Pattern.compile(
            "[(x\\[](?:feat\\.?|ft\\.?|with|prod\\.?|bonus track|remaster(?:ed)?)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAILING_FEAT = Pattern.compile(
            "\\b(?:feat\\.?|ft\\.?|with)\\s+.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern VIDEO_TAGS = Pattern.compile(
            "[(x\\[](?:official(?:\\s+(?:music\\s+)?video|\\s+audio)?|music video|video|audio|lyrics?|visualizer|album version|explicit)[^)\\]]*[)\\]]",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SONGWRITER_TAG = Pattern.compile(
            "<songwriter>([^<]*)</songwriter>");

    /** One thread, so a re-time never occupies the lookup pool or races another re-time. */
    private static final ExecutorService SYNC_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "lrc-red-sync");
        thread.setDaemon(true);
        return thread;
    });
    /** Songs the model has already re-timed in this run, so a revisit does not ask again. */
    private static final Set<String> SYNCED_ISRCS = ConcurrentHashMap.newKeySet();
    /** Songs the model refused, so the wait is not repeated. */
    private static final Set<String> SYNC_FAILED_ISRCS = ConcurrentHashMap.newKeySet();
    /** Songs whose document carried line-level timing only, pending a display to trigger sync. */
    private static final Map<String, Lyrics> SYNC_PENDING_DISPLAY = new ConcurrentHashMap<>();
    /** Songs the user put back, not asked about again until the app restarts. */
    private static final Set<String> SYNC_REVERTED_ISRCS = ConcurrentHashMap.newKeySet();
    /** Songs with a re-time in flight or queued. */
    private static final Set<String> SYNC_PENDING_ISRCS = ConcurrentHashMap.newKeySet();
    private static final AtomicLong SYNC_LAST_REQUEST_MS = new AtomicLong(0);
    private static final AtomicLong SYNC_ERROR_LAST_MS = new AtomicLong(0);

    /** One the search turned up, normalised from either wording before scoring. */
    private record SearchHit(String isrc, String title, String artist, String album,
                             double durationSeconds) {
    }

    private record ScoredHit(SearchHit hit, int score) {
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        if (track.title().isEmpty()) {
            return null;
        }

        for (SearchHit hit : queryCandidates(track)) {
            Lyrics lyrics = load(hit);
            if (lyrics != null && !lyrics.isEmpty()) {
                return FetchResult.of(
                        lyrics,
                        hit.title(),
                        hit.artist(),
                        Math.round(hit.durationSeconds()),
                        track
                );
            }
        }

        return null;
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<SearchHit> hits = queryCandidates(track);
        if (hits.isEmpty()) {
            return Collections.emptyList();
        }

        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        int count = 0;
        // Ranked already, and each download costs a song document.
        for (SearchHit hit : hits) {
            if (count >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }
            if (Thread.currentThread().isInterrupted()) {
                break;
            }

            Lyrics lyrics = load(hit);
            if (lyrics != null && !lyrics.isEmpty()) {
                int score = LyricsRequests.scoreLyricsCandidate(
                        hit.title(),
                        hit.artist(),
                        (int) Math.round(hit.durationSeconds()),
                        lyrics,
                        track
                );
                scored.add(new Lyrics.ScoredLyrics(score, lyrics));
                count++;
            }
        }

        return Lyrics.sortScoredByScore(scored);
    }

    /** Downloads one hit, which queues a model re-time when its timing is line level only. */
    @Nullable
    private Lyrics load(SearchHit hit) {
        if (hit.isrc() == null || hit.isrc().isEmpty()) {
            return null;
        }
        // The document is the only place the timing level is stated; the summary calls it "sync".
        return load(hit.isrc());
    }

    @Nullable
    private static Lyrics load(String isrc) {
        return load(isrc, false);
    }

    /** @param fresh reads past the CDN cache, which holds a song for hours. */
    @Nullable
    private static Lyrics load(String isrc, boolean fresh) {
        final String url = songApi(isrc) + ".song.json"
                + (fresh ? "?t=" + System.currentTimeMillis() : "");
        final JSONObject json = readJson(url);
        if (json == null) {
            return null;
        }

        final Lyrics lyrics = readSourceTtml(json);
        if (lyrics == null) {
            return null;
        }

        final String level = json.optString("level", "").trim().toLowerCase(Locale.ROOT);
        if ("line".equals(level) && !readByModel(json)) {
            SYNC_PENDING_DISPLAY.put(isrc, lyrics);
        }

        return withInfoLines(lyrics, json);
    }

    public static void onDisplayed(@Nullable Lyrics lyrics) {
        if (lyrics == null || !NAME.equals(lyrics.providerName())) {
            return;
        }
        final String url = lyrics.sourceUrl();
        if (url == null || url.isEmpty()) {
            return;
        }
        final String isrc = url.substring(url.lastIndexOf('/') + 1);
        final Lyrics before = SYNC_PENDING_DISPLAY.remove(isrc);
        if (before != null) {
            scheduleSync(isrc, before);
        }
    }

    /** The only copy that carries the sections and the readings. */
    @Nullable
    private static Lyrics readSourceTtml(JSONObject json) {
        final String ttml = json.optString("source", "").trim();
        if (ttml.isEmpty()) {
            Logger.printDebug(() -> "lrc.red song document carries no TTML");
            return null;
        }

        final Lyrics full = TTMLParser.ttmlToLyrics(
                ttml, NAME, songPage(json.optString("isrc", "")));
        if (full == null || full.isEmpty()) {
            return null;
        }

        return new Lyrics(full.lines(), NAME, full.synced(),
                full.romanization(), full.translations(), full.romanizations(),
                full.songwriters(), ttml, "ttml", full.sourceUrl(),
                readByModel(json) ? MODEL_CREDIT : null);
    }

    /** The album and the artist are in the document alone. */
    private static Lyrics withInfoLines(Lyrics lyrics, JSONObject json) {
        final List<String> lines = new ArrayList<>();
        if (lyrics.songwriters() == null) {
            // Only a document whose TTML failed to parse reaches here without credits.
            lines.addAll(readDocumentCredits(json.optString("source", "")));
        } else {
            for (String line : lyrics.songwriters()) {
                // A previous pass may have added these already.
                if (!line.startsWith(ALBUM_PREFIX) && !line.startsWith(ARTIST_PREFIX)) {
                    lines.add(line);
                }
            }
        }

        final String album = json.optString("album", "").trim();
        final String artist = json.optString("artist", "").trim();
        if (!album.isEmpty()) {
            lines.add(ALBUM_PREFIX + album);
        }
        if (!artist.isEmpty()) {
            lines.add(ARTIST_PREFIX + artist);
        }

        if (lines.isEmpty() || lines.equals(lyrics.songwriters())) {
            return lyrics;
        }

        return new Lyrics(lyrics.lines(), lyrics.providerName(), lyrics.synced(),
                lyrics.romanization(), lyrics.translations(), lyrics.romanizations(),
                lines, lyrics.rawFormat(), lyrics.formatType(),
                lyrics.sourceUrl(), lyrics.about());
    }

    /** The songwriter names in the TTML head, in the form the TTML parser produces. */
    private static List<String> readDocumentCredits(String ttml) {
        if (ttml.isEmpty()) {
            return List.of();
        }

        final List<String> names = new ArrayList<>();
        final Matcher matcher = SONGWRITER_TAG.matcher(ttml);
        while (matcher.find()) {
            final String name = matcher.group(1).trim();
            if (!name.isEmpty()) {
                names.add(name);
            }
        }
        return names.isEmpty() ? List.of() : List.of("Written by: " + String.join(", ", names));
    }

    private record SongState(boolean byModel, String level) {
    }

    @Nullable
    private static SongState parseLevel(String json) {
        if (json == null || json.trim().isEmpty()) {
            return null;
        }
        try {
            final JSONObject root = new JSONObject(json);
            return new SongState(readByModel(root),
                    root.optString("level", "").trim().toLowerCase(Locale.ROOT));
        } catch (Exception ex) {
            return null;
        }
    }

    private static boolean readByModel(JSONObject root) {
        if (!root.has(BY_MODEL_KEY)) {
            return false;
        }
        final Object value = root.opt(BY_MODEL_KEY);
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        return value instanceof String && "true".equalsIgnoreCase(((String) value).trim());
    }

    /** Stops the song being re-timed again in this run. */
    public static void markReverted(String isrc) {
        if (isrc != null && !isrc.isEmpty()) {
            SYNC_REVERTED_ISRCS.add(isrc);
        }
    }

    /**
     * Queues a model re-time of a song showing line level timing. Failures stay silent: the lyric
     * already on screen is good enough.
     */
    private static void scheduleSync(String isrc, Lyrics before) {
        if (SYNC_REVERTED_ISRCS.contains(isrc)
                || SYNCED_ISRCS.contains(isrc)
                || SYNC_FAILED_ISRCS.contains(isrc)) {
            return;
        }
        if (!SYNC_PENDING_ISRCS.add(isrc)) {
            return;
        }

        SYNC_EXECUTOR.execute(() -> {
            try {
                runSync(isrc, before);
            } catch (Exception ex) {
                Logger.printDebug(() -> "lrc.red sync failed for " + isrc, ex);
            } finally {
                SYNC_PENDING_ISRCS.remove(isrc);
            }
        });
    }

    private static void runSync(String isrc, Lyrics before) {
        final String[] error = new String[1];
        final String body = postSync(isrc, /* allowRetry */ true, error);
        if (body == null) {
            SYNC_FAILED_ISRCS.add(isrc);
            reportSyncFailure(readErrorMessage(error[0]));
            return;
        }

        // The sync response never carries byModel, so the document is read again.
        if (!confirmsByModel(isrc)) {
            SYNC_FAILED_ISRCS.add(isrc);
            reportSyncFailure("the sync returned but the model credit did not appear");
            return;
        }

        // The CDN holds the songs for hours, so the re-timed copy has to be read past it.
        final Lyrics after = load(isrc, /* fresh */ true);
        if (after == null || after.isEmpty()) {
            SYNC_FAILED_ISRCS.add(isrc);
            reportSyncFailure("the re-timed lyric could not be downloaded");
            return;
        }

        SYNCED_ISRCS.add(isrc);
        Utils.runOnMainThread(() -> LyricsManager.getInstance().patchInPlace(isrc, before, after));
    }

    @Nullable
    private static String postSync(String isrc, boolean allowRetry, String[] error) {
        final String[] attemptError = new String[1];
        final String body = syncRequest("POST", songApi(isrc) + "/sync", attemptError);
        if (body != null) {
            return body;
        }
        if (error != null) {
            error[0] = attemptError[0];
        }
        if (allowRetry && attemptError[0] != null && attemptError[0].contains(SYNC_RETRYABLE_ERROR)) {
            sleep(SYNC_RETRY_DELAY_MS);
            return postSync(isrc, /* allowRetry */ false, error);
        }
        return null;
    }

    private static void reportSyncFailure(@Nullable String detail) {
        try {
            final long now = System.currentTimeMillis();
            final long previous = SYNC_ERROR_LAST_MS.get();
            if (previous > 0 && now - previous < SYNC_ERROR_MIN_INTERVAL_MS) {
                return;
            }
            if (!SYNC_ERROR_LAST_MS.compareAndSet(previous, now)) {
                return;
            }
            final String reason = detail == null || detail.isEmpty() ? "no response" : detail;
            Logger.printException(() -> reason);
        } catch (Throwable ignored) {
        }
    }

    /** Read from {@code {"error": "..."}} when it parses, as it arrived otherwise. */
    @Nullable
    private static String readErrorMessage(@Nullable String body) {
        if (body == null) {
            return null;
        }
        try {
            final String error = new JSONObject(body).optString("error", "").trim();
            return error.isEmpty() ? body.trim() : error;
        } catch (Exception ignored) {
            return body.trim();
        }
    }

    private static boolean confirmsByModel(String isrc) {
        final SongState parsed = parseLevel(
                readText(songApi(isrc) + ".song.json?t=" + System.currentTimeMillis()));
        return parsed != null && parsed.byModel();
    }

    @Nullable
    private static String syncRequest(String method, String url, @Nullable String[] error) {
        throttleSync();

        HttpURLConnection connection = null;
        try {
            connection = Requester.openConnection(url);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(SYNC_CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(SYNC_READ_TIMEOUT_MS);
            connection.setRequestProperty("User-Agent", LyricsRequests.userAgent());

            if ("POST".equals(method)) {
                // An empty body is all this endpoint takes.
                connection.setFixedLengthStreamingMode(0);
                connection.setDoOutput(true);
                connection.getOutputStream().close();
            }

            final int code = connection.getResponseCode();
            if (code == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return Requester.parseString(connection);
            }

            if (error != null) {
                error[0] = Requester.parseErrorString(connection);
            }
            final int status = code;
            Logger.printDebug(() -> "lrc.red " + method + " " + status + " " + url);
            return null;
        } catch (IOException ex) {
            if (error != null) {
                error[0] = ex.getMessage();
            }
            Logger.printDebug(() -> "lrc.red " + method + " failed: " + url, ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void throttleSync() {
        final long now = System.currentTimeMillis();
        final long previous = SYNC_LAST_REQUEST_MS.getAndSet(now);
        final long waitMs = SYNC_MIN_INTERVAL_MS - (now - previous);
        if (previous > 0 && waitMs > 0) {
            sleep(waitMs);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    private List<SearchHit> queryCandidates(TrackInfo track) throws UnsupportedEncodingException {
        // match.json ranks by the whole track, which keeps covers out; the name goes under its own
        // parameter, not q.
        List<SearchHit> hits = matchApi(track);
        if (!hits.isEmpty()) {
            return hits;
        }

        final String cleanTitle = cleanBaseTitle(track.title());
        if (!cleanTitle.isEmpty() && !cleanTitle.equalsIgnoreCase(normalize(track.title()))) {
            hits = matchApi(new TrackInfo(cleanTitle, track.artist(),
                    track.album(), track.durationSeconds()));
            if (!hits.isEmpty()) {
                return hits;
            }
        }

        if (!track.title().isEmpty() && !track.artist().isEmpty()) {
            hits = matchApi(new TrackInfo(track.title(), "",
                    track.album(), track.durationSeconds()));
            if (!hits.isEmpty()) {
                return hits;
            }
        }

        // search.json searches the whole catalogue, so its order says nothing and hits are scored here.
        hits = searchApi(track);
        if (!hits.isEmpty()) {
            return hits;
        }

        return cleanTitle.isEmpty() || cleanTitle.equalsIgnoreCase(normalize(track.title()))
                ? Collections.emptyList()
                : searchApi(new TrackInfo(cleanTitle, track.artist(),
                track.album(), track.durationSeconds()));
    }

    private List<SearchHit> matchApi(TrackInfo track) throws UnsupportedEncodingException {
        if (track.title().isEmpty()) {
            return Collections.emptyList();
        }
        final JSONObject json = readJson(buildUrl("/match.json", "track", track));
        return json == null ? Collections.emptyList() : scoreHits(json.optJSONArray("hits"), track);
    }

    private List<SearchHit> searchApi(TrackInfo track) throws UnsupportedEncodingException {
        if (track.title().isEmpty()) {
            return Collections.emptyList();
        }
        final JSONObject json = readJson(buildUrl("/search.json", "q", track));
        return json == null ? Collections.emptyList() : scoreHits(json.optJSONArray("hits"), track);
    }

    private static String buildUrl(String path, String titleKey, TrackInfo track)
            throws UnsupportedEncodingException {
        final StringBuilder url = new StringBuilder(API_BASE)
                .append(path).append('?').append(titleKey).append('=')
                .append(LyricsRequests.encode(track.title()));
        if (!track.artist().isEmpty()) {
            url.append("&artist=").append(LyricsRequests.encode(track.artist()));
        }
        if (!track.album().isEmpty()) {
            url.append("&album=").append(LyricsRequests.encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }
        return url.toString();
    }

    private List<SearchHit> scoreHits(@Nullable JSONArray array, TrackInfo track) {
        if (array == null || array.length() == 0) {
            return Collections.emptyList();
        }

        List<ScoredHit> scored = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.optJSONObject(i);
            if (item == null) {
                continue;
            }
            SearchHit hit = new SearchHit(
                    item.optString("isrc", ""),
                    item.optString("title", ""),
                    item.optString("artist", ""),
                    item.optString("album", ""),
                    item.optDouble("duration", 0));
            if (hit.isrc().isEmpty()) {
                continue;
            }
            int score = scoreCandidate(hit, track);
            if (score > 0) {
                scored.add(new ScoredHit(hit, score));
            }
        }

        scored.sort((a, b) -> Integer.compare(b.score, a.score));

        List<SearchHit> sorted = new ArrayList<>(scored.size());
        for (ScoredHit sc : scored) {
            sorted.add(sc.hit());
        }
        return sorted;
    }

    private static int scoreCandidate(SearchHit item, TrackInfo track) {
        int targetDuration = track.durationSeconds();
        int itemDuration = (int) Math.round(item.durationSeconds());

        String targetFullTitle = normalize(track.title());
        String targetBaseTitle = cleanBaseTitle(track.title());
        String targetArtist = normalize(track.artist());
        String targetAlbum = normalize(track.album());

        String itemFullTitle = normalize(item.title());
        String itemBaseTitle = cleanBaseTitle(item.title());
        String itemArtist = normalize(item.artist());
        String itemAlbum = normalize(item.album());

        int score = 0;
        // A title that matches exactly loses almost nothing to a differently written artist, which
        // is how a native title pairs with a romanized artist, or the other way round.
        boolean titleExact = false;

        // Title.
        if (!targetFullTitle.isEmpty() && targetFullTitle.equals(itemFullTitle)) {
            score += 50;
            titleExact = true;
        } else if (!targetBaseTitle.isEmpty() && !itemBaseTitle.isEmpty()) {
            if (targetBaseTitle.equals(itemBaseTitle)) {
                score += 35;
                titleExact = true;
            } else if (itemBaseTitle.contains(targetBaseTitle) || targetBaseTitle.contains(itemBaseTitle)) {
                score += 15;
            } else {
                score -= 40;
            }
        }

        // Artist.
        if (!targetArtist.isEmpty() && !itemArtist.isEmpty()) {
            if (targetArtist.equals(itemArtist)) {
                score += 30;
            } else if (itemArtist.contains(targetArtist) || targetArtist.contains(itemArtist)) {
                score += 15;
            } else {
                List<String> targetArtists = extractArtists(track.artist());
                List<String> itemArtists = extractArtists(item.artist());
                boolean hasOverlap = false;
                for (String ta : targetArtists) {
                    for (String ia : itemArtists) {
                        if (ia.equals(ta) || ia.contains(ta) || ta.contains(ia)) {
                            hasOverlap = true;
                            break;
                        }
                    }
                    if (hasOverlap) {
                        break;
                    }
                }
                if (hasOverlap) {
                    score += 10;
                } else {
                    score -= titleExact ? 25 : 50;
                }
            }
        }

        // Album.
        if (!targetAlbum.isEmpty() && !itemAlbum.isEmpty()) {
            if (targetAlbum.equals(itemAlbum)) {
                score += 35;
            } else if (itemAlbum.contains(targetAlbum) || targetAlbum.contains(itemAlbum)) {
                score += 20;
            }

            boolean targetIsSingle = targetAlbum.contains("single");
            boolean itemIsSingle = itemAlbum.contains("single");
            if (!targetIsSingle && itemIsSingle) {
                score -= 15;
            } else if (targetIsSingle && itemIsSingle) {
                score += 10;
            }
        }

        // Duration.
        if (targetDuration > 0 && itemDuration > 0) {
            int diff = Math.abs(itemDuration - targetDuration);
            if (diff == 0) {
                score += 25;
            } else if (diff <= 1) {
                score += 20;
            } else if (diff <= 2) {
                score += 15;
            } else if (diff <= 5) {
                score += 5;
            } else if (diff > 15) {
                score -= 30;
            }
        }

        // A remix, cover or other variant the track is not.
        String trackLower = (track.title() + " " + track.album()).toLowerCase(Locale.ROOT);
        String itemLower = (item.title() + " " + item.album()).toLowerCase(Locale.ROOT);
        for (String kw : MODIFIER_KEYWORDS) {
            if (itemLower.contains(kw) && !trackLower.contains(kw)) {
                score -= 30;
            }
        }

        return score;
    }

    private static String normalize(String str) {
        if (str == null) {
            return "";
        }
        return str.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}\\s]", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static String cleanBaseTitle(String str) {
        if (str == null) {
            return "";
        }
        String s = BRACKETED_TAGS.matcher(str).replaceAll("");
        s = TRAILING_FEAT.matcher(s).replaceAll("");
        s = VIDEO_TAGS.matcher(s).replaceAll("");
        return normalize(s);
    }

    private static List<String> extractArtists(String str) {
        if (str == null || str.isEmpty()) {
            return Collections.emptyList();
        }
        String[] parts = str.split("(?i)[,&/]|\\b(?:feat\\.?|ft\\.?|with|x)\\b");
        List<String> result = new ArrayList<>(parts.length);
        for (String p : parts) {
            String norm = normalize(p);
            if (!norm.isEmpty()) {
                result.add(norm);
            }
        }
        return result;
    }

    /** The body of a successful response, or null with a debug log. */
    @Nullable
    private static String readText(String url) {
        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url);
            final int code = connection.getResponseCode();
            if (code != Requester.HTTP_STATUS_CODE_SUCCESS) {
                LyricsRequests.logFailure(NAME, connection);
                return null;
            }
            return Requester.parseString(connection);
        } catch (Exception ex) {
            Logger.printDebug(() -> "lrc.red request failed: " + url, ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private static JSONObject readJson(String url) {
        final String body = readText(url);
        if (body == null) {
            return null;
        }
        try {
            return new JSONObject(body);
        } catch (Exception ex) {
            Logger.printDebug(() -> "lrc.red returned invalid JSON: " + url, ex);
            return null;
        }
    }

    private static String songApi(String isrc) {
        return API_BASE + "/s/" + isrc;
    }

    private static String songPage(String isrc) {
        return songApi(isrc);
    }
}
