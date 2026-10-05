/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3041
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.music.settings.Settings;

public final class LunaBeatProvider implements LyricsProvider {

    private static final String BASE_URL = "https://2755337087.github.io/ttml-hub/";
    private static final String MANIFEST_URL = BASE_URL + "api/v1/manifest.json";
    private static final String SONGS_URL = BASE_URL + "api/v1/songs.json";

    private static final long REQUEST_THROTTLE_MS = 500;

    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    private static volatile List<Song> lunabeatSongs = Collections.emptyList();
    private static volatile String lunabeatCachedRevision;
    private static final CountDownLatch lunabeatIndexLatch = new CountDownLatch(1);
    private static final AtomicBoolean lunabeatLoadStarted = new AtomicBoolean(false);

    record Song(String id, String title, String[] artists, String album,
                String path, String sha256) {
    }

    public static void preloadIndex() {
        if (!Settings.LYRICS_ENABLED.get() || !LyricsManager.isProviderEnabled("LunaBeat")) {
            return;
        }
        startIndexLoad();
    }

    private static void startIndexLoad() {
        if (!lunabeatLoadStarted.compareAndSet(false, true)) {
            return;
        }
        try {
            doPreloadIndex();
        } catch (Exception ex) {
            Logger.printInfo(() -> "LunaBeat index preload failed", ex);
        } finally {
            lunabeatIndexLatch.countDown();
        }
    }

    private static void doPreloadIndex() {
        String revision = fetchRevision();
        if (revision == null) {
            return;
        }
        if (revision.equals(lunabeatCachedRevision)) {
            return;
        }

        List<Song> songs = fetchSongIndex();
        if (songs == null || songs.isEmpty()) {
            return;
        }

        lunabeatSongs = songs;
        lunabeatCachedRevision = revision;
    }

    @Nullable
    private static String fetchRevision() {
        HttpURLConnection conn = null;
        try {
            LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
            conn = LyricsRequests.openConnection(MANIFEST_URL);
            if (conn.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            JSONObject manifest = Requester.parseJSONObject(conn);
            String revision = manifest.optString("revision");
            return revision.isEmpty() ? null : revision;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch LunaBeat manifest revision", ex);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Nullable
    private static List<Song> fetchSongIndex() {
        HttpURLConnection conn = null;
        try {
            LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
            conn = LyricsRequests.openConnection(SONGS_URL);
            if (conn.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            JSONObject root = Requester.parseJSONObject(conn);
            JSONArray songs = root.optJSONArray("songs");
            if (songs == null) return null;

            List<Song> result = new ArrayList<>(songs.length());
            for (int i = 0; i < songs.length(); i++) {
                JSONObject obj = songs.optJSONObject(i);
                if (obj == null) continue;
                String id = obj.optString("id", "");
                String title = obj.optString("title", "");
                String path = obj.optString("path", "");

                JSONArray artistsArr = obj.optJSONArray("artists");
                String[] artists = new String[artistsArr != null ? artistsArr.length() : 0];
                for (int a = 0; a < artists.length; a++) {
                    artists[a] = artistsArr.optString(a, "");
                }

                String album = obj.optString("album", "");
                String sha256 = obj.optString("sha256", "");

                result.add(new Song(id, title, artists, album, path, sha256));
            }
            return result;
        } catch (Exception ex) {
            Logger.printInfo(() -> "LunaBeat songs.json fetch failed", ex);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Override
    public String name() {
        return "LunaBeat";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        List<ScoredSong> candidates = fetchScoredSongs(track);
        if (candidates.isEmpty()) {
            return null;
        }
        ScoredSong top = candidates.get(0);
        return FetchResult.of(top.lyrics(), top.title(), top.artist(), 0, track);
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        for (ScoredSong candidate : fetchScoredSongs(track)) {
            scored.add(new Lyrics.ScoredLyrics(candidate.score(), candidate.lyrics()));
        }
        return scored;
    }

    private record ScoredSong(int score, Lyrics lyrics, String title, String artist) {}

    private List<ScoredSong> fetchScoredSongs(TrackInfo track) {
        ensureIndexLoaded();

        List<Song> matches = searchLunabeatIndex(track);
        if (matches.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredSong> scored = new ArrayList<>(matches.size());
        for (Song song : matches) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) break;
            String artist = song.artists.length > 0 ? song.artists[0] : "";
            int trackScore = LyricsRequests.scoreTrackCandidate(song.title(), artist, 0, track);
            if (trackScore < LyricsRequests.SOFT_MIN && !scored.isEmpty()) {
                continue;
            }
            Lyrics lyrics = fetchLunabeatLyrics(song);
            if (lyrics != null && !lyrics.isEmpty()) {
                int score = scoreLunabeatCandidate(
                        song.title(), artist, lyrics, track);
                scored.add(new ScoredSong(score, lyrics, song.title(), artist));
            }
        }

        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        return scored;
    }

    private static void ensureIndexLoaded() {
        if (lunabeatSongs != null && !lunabeatSongs.isEmpty()) {
            return;
        }
        startIndexLoad();
        try {
            boolean completed = lunabeatIndexLatch.await(5, TimeUnit.SECONDS);
            if (!completed) {
                Logger.printDebug(() -> "Timeout waiting for LunaBeat index latch");
            }
        } catch (InterruptedException ex) {
            Logger.printDebug(() -> "Interrupted waiting for LunaBeat index latch", ex);
            Thread.currentThread().interrupt();
        }
    }

    private static String normalizeLunabeat(@Nullable String s) {
        if (s == null) return "";
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s·・.\\-_]", "");
    }

    private List<Song> searchLunabeatIndex(TrackInfo track) {
        String nt = normalizeLunabeat(track.title());
        String na = normalizeLunabeat(track.artist());

        List<Song> result = new ArrayList<>();
        for (Song song : lunabeatSongs) {
            if (!normalizeLunabeat(song.title()).equals(nt)) continue;

            boolean artistMatch = false;
            for (String a : song.artists) {
                if (normalizeLunabeat(a).equals(na)) {
                    artistMatch = true;
                    break;
                }
            }
            if (artistMatch) {
                result.add(song);
            }
        }
        return result;
    }

    @Nullable
    private Lyrics fetchLunabeatLyrics(Song song) {
        String url = BASE_URL + song.path;
        HttpURLConnection conn = null;
        try {
            LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
            conn = LyricsRequests.openConnection(url);
            if (conn.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            java.io.InputStream in = conn.getInputStream();
            int n;
            while ((n = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            String ttml = sb.toString();

            Lyrics lyrics = TTMLParser.ttmlToLyrics(ttml, name(), "");
            if (lyrics == null) return null;

            Map<String, List<LyricsLine>> translations = lyrics.translations();
            if (translations == null || translations.isEmpty()) return lyrics;

            String deviceLang = LyricsRequests.deviceLanguage();
            if ("zh".equals(deviceLang)) {
                List<LyricsLine> zhLines = translations.get("zh");
                if (zhLines == null) return lyrics;
                Map<String, List<LyricsLine>> filtered = new HashMap<>(1);
                filtered.put("zh", zhLines);
                return new Lyrics(lyrics.lines(), lyrics.providerName(), lyrics.synced(),
                        lyrics.romanization(), filtered, lyrics.romanizations(),
                        lyrics.songwriters(), lyrics.rawFormat(), lyrics.formatType(),
                        lyrics.sourceUrl());
            }

            return new Lyrics(lyrics.lines(), lyrics.providerName(), lyrics.synced(),
                    lyrics.romanization(), Collections.emptyMap(), lyrics.romanizations(),
                    lyrics.songwriters(), lyrics.rawFormat(), lyrics.formatType(),
                    lyrics.sourceUrl());
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch LunaBeat lyrics", ex);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int scoreLunabeatCandidate(String title, String artist,
                                              Lyrics lyrics, TrackInfo track) {
        int trackScore = LyricsRequests.scoreTrackCandidate(title, artist, 0, track);
        return trackScore + LyricsRequests.syncRank(lyrics);
    }
}
