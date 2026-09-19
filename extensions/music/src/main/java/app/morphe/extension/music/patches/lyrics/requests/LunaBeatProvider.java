/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
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

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class LunaBeatProvider implements LyricsProvider {

    private static final String BASE_URL = "https://2755337087.github.io/ttml-hub/";
    private static final String MANIFEST_URL = BASE_URL + "api/v1/manifest.json";
    private static final String SONGS_URL = BASE_URL + "api/v1/songs.json";

    private static volatile List<Song> lunabeatSongs = Collections.emptyList();
    private static volatile String lunabeatCachedRevision;
    private static final CountDownLatch lunabeatIndexLatch = new CountDownLatch(1);

    record Song(String id, String title, String[] artists, String album,
                String path, String sha256) {
    }

    public static void preloadIndex() {
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
            conn = LyricsRequests.openConnection(MANIFEST_URL);
            if (conn.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            JSONObject manifest = Requester.parseJSONObject(conn);
            return manifest.optString("revision", null);
        } catch (Exception ex) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Nullable
    private static List<Song> fetchSongIndex() {
        HttpURLConnection conn = null;
        try {
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
                String id = obj.optString("id", null);
                String title = obj.optString("title", null);
                String path = obj.optString("path", null);
                if (id == null || title == null || path == null) continue;

                JSONArray artistsArr = obj.optJSONArray("artists");
                String[] artists = new String[artistsArr != null ? artistsArr.length() : 0];
                for (int a = 0; a < artists.length; a++) {
                    artists[a] = artistsArr != null ? artistsArr.optString(a, "") : "";
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
    public Lyrics fetch(TrackInfo track) throws Exception {
        List<Lyrics> candidates = fetchCandidates(track);
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    @Override
    public List<Lyrics> fetchCandidates(TrackInfo track) throws Exception {
        ensureIndexLoaded();

        List<Song> matches = searchLunabeatIndex(track);
        if (matches.isEmpty()) {
            return Collections.emptyList();
        }

        List<ScoredLyrics> scored = new ArrayList<>();
        for (Song song : matches) {
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) break;
            try {
                Lyrics lyrics = fetchLunabeatLyrics(song);
                if (lyrics != null && !lyrics.isEmpty()) {
                    int score = scoreLunabeatCandidate(
                            song.title(),
                            song.artists.length > 0 ? song.artists[0] : "",
                            0, lyrics, track);
                    scored.add(new ScoredLyrics(score, lyrics));
                }
            } catch (Exception ex) {
            }
        }

        scored.sort((a, b) -> b.score - a.score);
        List<Lyrics> results = new ArrayList<>(scored.size());
        for (ScoredLyrics s : scored) {
            results.add(s.lyrics);
        }
        return results;
    }

    private static void ensureIndexLoaded() {
        if (lunabeatSongs != null && !lunabeatSongs.isEmpty()) {
            return;
        }
        try {
            lunabeatIndexLatch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
    }

    private static String normalizeLunabeat(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT)
                .replaceAll("[\\s\u00b7\u30fb.\\-_]", "");
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

            Lyrics lyrics = TtmlParser.ttmlToLyrics(ttml, name(), null);
            if (lyrics == null) return null;

            Map<String, List<LyricsLine>> translations = lyrics.translations();
            if (translations == null || translations.isEmpty()) return lyrics;

            String deviceLang = Locale.getDefault().getLanguage();
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
                    lyrics.romanization(), null, lyrics.romanizations(),
                    lyrics.songwriters(), lyrics.rawFormat(), lyrics.formatType(),
                    lyrics.sourceUrl());
        } catch (Exception ex) {
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static int scoreLunabeatCandidate(String title, String artist,
            long durationSec, Lyrics lyrics, TrackInfo track) {
        int trackScore = LyricsRequests.scoreTrackCandidate(title, artist, durationSec, track);
        if (durationSec <= 0 || track.durationSeconds() <= 0) {
            trackScore += 2;
        }
        return trackScore + LyricsRequests.syncRank(lyrics);
    }

    private record ScoredLyrics(int score, Lyrics lyrics) {
    }
}
