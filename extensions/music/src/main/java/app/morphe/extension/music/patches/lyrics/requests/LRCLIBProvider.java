/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.parsers.LyricsFileParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * LRCLIB, the open lyrics database used by Metrolist, InnerTune and ViMusic.
 *
 * @see <a href="https://lrclib.net/docs">API documentation</a>
 */
public final class LRCLIBProvider implements LyricsProvider {

    private static final String BASE_URL = "https://lrclib.net/api/";

    /**
     * Public ID of the registered client application, which gets higher rate limits.
     * It is not a secret.
     *
     * @see <a href="https://lrclib.net/developers/authorizations/HLyf7QYkIJfl4wcObeD-caHjBZPnGhv0Vuvx2UiIFKk">API authorization</a>
     */
    private static final String APP_ID = "HLyf7QYkIJfl4wcObeD-caHjBZPnGhv0Vuvx2UiIFKk";

    private static final Map<String, String> HEADERS = Map.of("Lrclib-App-Id", APP_ID);

    @Override
    public String name() {
        return "LRCLIB";
    }

    @Override
    public boolean hasCandidates() {
        return true;
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        // The exact endpoint matches on duration as well, which gives the best timings,
        // but it fails for any track whose duration differs from the database entry.
        // Synced always wins over plain, so a plain exact match only stands when no search
        // result has timings either.
        FetchResult exact = fetchExact(track);
        final boolean exactFound = exact != null && exact.lyrics() != Lyrics.NOT_FOUND;
        if (exactFound && LyricsRequests.syncRank(exact.lyrics()) > 0) {
            return exact;
        }
        FetchResult searched = fetchSearch(track);
        if (!exactFound) {
            return searched;
        }
        if (searched != null && searched.lyrics() != Lyrics.NOT_FOUND
                && LyricsRequests.syncRank(searched.lyrics()) > LyricsRequests.syncRank(exact.lyrics())) {
            return searched;
        }
        return exact;
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();

        FetchResult exact = fetchExact(track);
        if (exact != null) {
            Lyrics exactLyrics = exact.lyrics();
            scored.add(new Lyrics.ScoredLyrics(
                    LyricsRequests.scoreSingleResult(exactLyrics), exactLyrics));
        }

        String url = BASE_URL + "search?track_name=" + LyricsRequests.encode(track.title())
                + "&artist_name=" + LyricsRequests.encode(track.artist());
        HttpURLConnection connection = LyricsRequests.openConnection(url, HEADERS);
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            return Lyrics.sortScoredByScore(scored);
        }

        JSONArray searchResults = Requester.parseJSONArray(connection);
        if (searchResults.length() == 0) {
            return Lyrics.sortScoredByScore(scored);
        }

        List<JSONObject> candidates = new ArrayList<>();
        for (int i = 0; i < searchResults.length(); i++) {
            JSONObject candidate = searchResults.optJSONObject(i);
            if (candidate != null) {
                candidates.add(candidate);
            }
        }

        candidates.sort((a, b) -> {
            final int scoreA = scoreCandidate(a, track);
            final int scoreB = scoreCandidate(b, track);
            if (scoreA != scoreB) {
                return scoreB - scoreA;
            }
            if (track.durationSeconds() <= 0) {
                return 0;
            }
            final int deltaA = Math.abs(a.optInt("duration", 0) - track.durationSeconds());
            final int deltaB = Math.abs(b.optInt("duration", 0) - track.durationSeconds());
            return deltaA - deltaB;
        });

        List<JSONObject> gated = new ArrayList<>();
        for (JSONObject candidate : candidates) {
            if (scoreCandidate(candidate, track) >= LyricsRequests.SOFT_MIN) {
                gated.add(candidate);
            }
        }
        if (gated.isEmpty() && !candidates.isEmpty()) {
            gated.add(candidates.get(0));
        }

        for (JSONObject candidate : gated) {
            Lyrics lyrics = toLyrics(candidate);
            if (lyrics != null && !lyrics.isEmpty()) {
                int score = LyricsRequests.scoreLyricsCandidate(
                        candidate.optString("trackName", ""),
                        candidate.optString("artistName", ""),
                        candidate.optInt("duration", 0),
                        lyrics, track);
                scored.add(new Lyrics.ScoredLyrics(score, lyrics));
            }
        }

        List<Lyrics.ScoredLyrics> ordered = syncedFirst(scored);
        return ordered.size() > LyricsRequests.MAX_CANDIDATES
                ? new ArrayList<>(ordered.subList(0, LyricsRequests.MAX_CANDIDATES))
                : ordered;
    }

    /**
     * Orders candidates so every synced one comes before every plain one, by score within
     * each group. Word timings rank above line timings.
     */
    static List<Lyrics.ScoredLyrics> syncedFirst(List<Lyrics.ScoredLyrics> scored) {
        List<Lyrics.ScoredLyrics> ordered = new ArrayList<>(Lyrics.sortScoredByScore(scored));
        // List.sort is stable, so the score order survives within each timing rank.
        ordered.sort((a, b) -> LyricsRequests.syncRank(b.lyrics()) - LyricsRequests.syncRank(a.lyrics()));
        return ordered;
    }

    private static int scoreCandidate(JSONObject item, TrackInfo track) {
        String title = item.optString("trackName", "");
        String artist = item.optString("artistName", "");
        return LyricsRequests.scoreTrackCandidate(title, artist,
                item.optInt("duration", 0), track);
    }

    @Nullable
    private FetchResult fetchExact(TrackInfo track) throws Exception {
        StringBuilder url = new StringBuilder(BASE_URL);
        url.append("get?track_name=").append(LyricsRequests.encode(track.title()));
        url.append("&artist_name=").append(LyricsRequests.encode(track.artist()));
        if (!track.album().isEmpty()) {
            url.append("&album_name=").append(LyricsRequests.encode(track.album()));
        }
        if (track.durationSeconds() > 0) {
            url.append("&duration=").append(track.durationSeconds());
        }

        JSONObject response = getJsonObject(url.toString());
        if (response == null) {
            return null;
        }
        Lyrics lyrics = toLyrics(response);
        if (lyrics == null) {
            return null;
        }
        return FetchResult.of(lyrics,
                response.optString("trackName", ""),
                response.optString("artistName", ""),
                response.optLong("duration", 0), track);
    }

    @Nullable
    private FetchResult fetchSearch(TrackInfo track) throws Exception {
        String url = BASE_URL + "search?track_name=" + LyricsRequests.encode(track.title())
                + "&artist_name=" + LyricsRequests.encode(track.artist());

        HttpURLConnection connection = LyricsRequests.openConnection(url, HEADERS);
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }

        JSONArray results = Requester.parseJSONArray(connection);
        final int resultsLength = results.length();
        if (resultsLength == 0) {
            return null;
        }

        FetchResult best = null;
        int bestRank = -1;
        int bestCombined = -1;
        for (int i = 0; i < resultsLength; i++) {
            JSONObject candidate = results.optJSONObject(i);
            if (candidate == null) {
                continue;
            }
            try {
                Lyrics candidateLyrics = toLyrics(candidate);
                if (candidateLyrics != null && candidateLyrics != Lyrics.NOT_FOUND) {
                    int combined = LyricsRequests.scoreLyricsCandidate(
                            candidate.optString("trackName", ""),
                            candidate.optString("artistName", ""),
                            candidate.optInt("duration", 0),
                            candidateLyrics, track);
                    final int rank = LyricsRequests.syncRank(candidateLyrics);
                    int trackScore = combined - rank;
                    // Any synced match beats any plain one; the score only breaks ties
                    // between results with the same kind of timings.
                    if (trackScore >= LyricsRequests.SOFT_MIN
                            && (rank > bestRank || (rank == bestRank && combined > bestCombined))) {
                        bestRank = rank;
                        bestCombined = combined;
                        best = FetchResult.of(candidateLyrics,
                                candidate.optString("trackName", ""),
                                candidate.optString("artistName", ""),
                                candidate.optInt("duration", 0), track);
                    }
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Failed to process LrcLib candidate", ex);
            }
        }
        return best;
    }

    @Nullable
    private Lyrics toLyrics(JSONObject response) {
        if (response.optBoolean("instrumental", false)) {
            return Lyrics.NOT_FOUND;
        }

        final int id = response.optInt("id", -1);
        String sourceUrl = id > 0 ? "https://lrclib.net/tracks/" + id : null;

        String lyricsFile = LyricsRequests.optString(response, "lyricsFile");
        if (lyricsFile == null) {
            lyricsFile = LyricsRequests.optString(response, "lyricsfile");
        }
        if (lyricsFile != null) {
            Lyrics fromFile = LyricsFileParser.parse(lyricsFile, name(), sourceUrl);
            if (fromFile != null && !fromFile.isEmpty()) {
                return fromFile;
            }
        }

        String synced = LyricsRequests.optString(response, "syncedLyrics");
        if (synced != null) {
            LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(synced);
            if (!result.lines.isEmpty()) {
                return new Lyrics(result.lines, name(), true, null, null, null,
                        result.creditLines.isEmpty() ? null : result.creditLines,
                        synced, "lrc", sourceUrl);
            }
        }

        String plain = LyricsRequests.optString(response, "plainLyrics");
        if (plain != null) {
            List<LyricsLine> lines = LRCParser.parsePlain(plain);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, name(), false, null, null, null, null, plain, "lrc",
                        sourceUrl);
            }
        }

        return null;
    }

    @Nullable
    private JSONObject getJsonObject(String url) throws IOException, JSONException {
        HttpURLConnection connection = LyricsRequests.openConnection(url, HEADERS);
        final int responseCode = connection.getResponseCode();
        if (responseCode == 404) {
            connection.disconnect();
            return null;
        }
        if (responseCode != 200) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }
        return Requester.parseJSONObject(connection);
    }
}
