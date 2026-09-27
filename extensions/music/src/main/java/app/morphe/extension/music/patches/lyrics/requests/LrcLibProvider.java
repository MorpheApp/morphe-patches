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

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * LRCLIB, the open lyrics database used by Metrolist, InnerTune and ViMusic.
 *
 * @see <a href="https://lrclib.net/docs">API documentation</a>
 */
public final class LrcLibProvider implements LyricsProvider {

    private static final String BASE_URL = "https://lrclib.net/api/";

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
        Lyrics exact = fetchExact(track);
        if (exact != null && exact != Lyrics.NOT_FOUND) {
            return FetchResult.of(exact, track);
        }
        return FetchResult.of(fetchSearch(track), track);
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();

        // Exact match first
        Lyrics exact = fetchExact(track);
        if (exact != null) {
            scored.add(new Lyrics.ScoredLyrics(LyricsRequests.scoreSingleResult(exact), exact));
        }

        // Then search results, sorted by combined score
        String url = BASE_URL + "search?track_name=" + LyricsRequests.encode(track.title())
                + "&artist_name=" + LyricsRequests.encode(track.artist());
        HttpURLConnection connection = LyricsRequests.openConnection(url);
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
            if (scored.size() >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }
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

        return Lyrics.sortScoredByScore(scored);
    }

    private static int scoreCandidate(JSONObject item, TrackInfo track) {
        String title = item.optString("trackName", "");
        String artist = item.optString("artistName", "");
        return LyricsRequests.scoreTrackCandidate(title, artist,
                item.optInt("duration", 0), track);
    }

    @Nullable
    private Lyrics fetchExact(TrackInfo track) throws Exception {
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
        return toLyrics(response);
    }

    @Nullable
    private Lyrics fetchSearch(TrackInfo track) throws Exception {
        String url = BASE_URL + "search?track_name=" + LyricsRequests.encode(track.title())
                + "&artist_name=" + LyricsRequests.encode(track.artist());

        HttpURLConnection connection = LyricsRequests.openConnection(url);
        if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }

        JSONArray results = Requester.parseJSONArray(connection);
        final int resultsLength = results.length();
        if (resultsLength == 0) {
            return null;
        }

        Lyrics bestLyrics = null;
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
                    int trackScore = combined - LyricsRequests.syncRank(candidateLyrics);
                    if (trackScore >= LyricsRequests.SOFT_MIN && combined > bestCombined) {
                        bestCombined = combined;
                        bestLyrics = candidateLyrics;
                    }
                }
            } catch (Exception ex) {
                Logger.printDebug(() -> "Failed to process LrcLib candidate", ex);
            }
        }
        return bestLyrics;
    }

    @Nullable
    private Lyrics toLyrics(JSONObject response) {
        if (response.optBoolean("instrumental", false)) {
            return Lyrics.NOT_FOUND;
        }

        final int id = response.optInt("id", -1);
        final String sourceUrl = id > 0 ? "https://lrclib.net/tracks/" + id : null;

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

        String enhanced = LyricsRequests.optString(response, "enhancedLyrics");
        if (enhanced != null) {
            LrcParser.LrcParseResult result = LrcParser.parseSyncedWithCreditLines(enhanced);
            if (!result.lines.isEmpty()) {
                return new Lyrics(result.lines, name(), true, null, null, null,
                        result.creditLines.isEmpty() ? null : result.creditLines,
                        enhanced, "lrc", sourceUrl);
            }
        }

        String synced = LyricsRequests.optString(response, "syncedLyrics");
        if (synced != null) {
            LrcParser.LrcParseResult result = LrcParser.parseSyncedWithCreditLines(synced);
            if (!result.lines.isEmpty()) {
                return new Lyrics(result.lines, name(), true, null, null, null,
                        result.creditLines.isEmpty() ? null : result.creditLines,
                        synced, "lrc", sourceUrl);
            }
        }

        String plain = LyricsRequests.optString(response, "plainLyrics");
        if (plain != null) {
            List<LyricsLine> lines = LrcParser.parsePlain(plain);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, name(), false, null, null, null, null, plain, "lrc",
                        sourceUrl);
            }
        }

        return null;
    }

    @Nullable
    private JSONObject getJsonObject(String url) throws IOException, JSONException {
        HttpURLConnection connection = LyricsRequests.openConnection(url);
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
