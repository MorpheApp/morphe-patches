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

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * Lyrics from lrc.red, as word synced TTML or as LRC.
 */
public final class LrcRedProvider implements LyricsProvider {

    private static final String BASE_URL = "https://lrc.red/api/v1";

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

    @Override
    public String name() {
        return "LrcRed";
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

        List<JSONObject> candidates = queryCandidates(track);
        if (candidates.isEmpty()) {
            return null;
        }

        for (JSONObject candidate : candidates) {
            String lyricsUrl = candidate.optString("lyricsUrl", "");
            if (lyricsUrl.isEmpty()) {
                continue;
            }

            Lyrics lyrics = downloadLyrics(lyricsUrl);
            if (lyrics != null && !lyrics.isEmpty()) {
                return FetchResult.of(
                        lyrics,
                        candidate.optString("track_name", ""),
                        candidate.optString("artist_name", ""),
                        candidate.optLong("duration", 0),
                        track
                );
            }
        }

        return null;
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws Exception {
        List<JSONObject> candidates = queryCandidates(track);
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        int count = 0;
        for (JSONObject candidate : candidates) {
            if (count >= LyricsRequests.MAX_CANDIDATES) {
                break;
            }

            String lyricsUrl = candidate.optString("lyricsUrl", "");
            if (lyricsUrl.isEmpty()) {
                continue;
            }

            Lyrics lyrics = downloadLyrics(lyricsUrl);
            if (lyrics != null && !lyrics.isEmpty()) {
                int score = LyricsRequests.scoreLyricsCandidate(
                        candidate.optString("track_name", ""),
                        candidate.optString("artist_name", ""),
                        candidate.optInt("duration", 0),
                        lyrics,
                        track
                );
                scored.add(new Lyrics.ScoredLyrics(score, lyrics));
                count++;
            }
        }

        return Lyrics.sortScoredByScore(scored);
    }

    private List<JSONObject> queryCandidates(TrackInfo track) {
        String mainQuery = (track.title() + " " + track.artist()).trim();
        List<JSONObject> results = searchApi(mainQuery, track);
        if (!results.isEmpty()) {
            return results;
        }

        String cleanTitle = cleanBaseTitle(track.title());
        if (!cleanTitle.isEmpty() && !cleanTitle.equalsIgnoreCase(normalize(track.title()))) {
            String cleanQuery = (cleanTitle + " " + track.artist()).trim();
            results = searchApi(cleanQuery, track);
            if (!results.isEmpty()) {
                return results;
            }
        }

        if (!track.title().isEmpty() && !track.artist().isEmpty()) {
            results = searchApi(track.title().trim(), track);
            if (!results.isEmpty()) {
                return results;
            }
        }

        return Collections.emptyList();
    }

    private List<JSONObject> searchApi(String query, TrackInfo track) {
        if (query.isEmpty()) {
            return Collections.emptyList();
        }

        HttpURLConnection connection = null;
        try {
            String url = BASE_URL + "?q=" + LyricsRequests.encode(query);
            connection = LyricsRequests.openConnection(url);
            if (connection.getResponseCode() != Requester.HTTP_STATUS_CODE_SUCCESS) {
                LyricsRequests.logFailure(name(), connection);
                return Collections.emptyList();
            }

            JSONObject json = Requester.parseJSONObject(connection);
            JSONArray array = json.optJSONArray("results");
            if (array == null || array.length() == 0) {
                return Collections.emptyList();
            }

            List<ScoredCandidate> scoredList = new ArrayList<>();
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                int score = scoreCandidate(item, track);
                if (score > 0) {
                    scoredList.add(new ScoredCandidate(item, score));
                }
            }

            scoredList.sort((a, b) -> Integer.compare(b.score, a.score));

            List<JSONObject> sorted = new ArrayList<>(scoredList.size());
            for (ScoredCandidate sc : scoredList) {
                sorted.add(sc.candidate);
            }
            return sorted;
        } catch (Exception ex) {
            Logger.printDebug(() -> "LrcRed search failed for query: " + query, ex);
            return Collections.emptyList();
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static int scoreCandidate(JSONObject item, TrackInfo track) {
        int targetDuration = track.durationSeconds();
        int itemDuration = item.optInt("duration", 0);

        String itemTrackName = item.optString("track_name", "");
        String itemArtistName = item.optString("artist_name", "");
        String itemAlbumName = item.optString("album_name", "");
        String itemTimingType = item.optString("timing_type", "");

        String targetFullTitle = normalize(track.title());
        String targetBaseTitle = cleanBaseTitle(track.title());
        String targetArtist = normalize(track.artist());
        String targetAlbum = normalize(track.album());

        String itemFullTitle = normalize(itemTrackName);
        String itemBaseTitle = cleanBaseTitle(itemTrackName);
        String itemArtist = normalize(itemArtistName);
        String itemAlbum = normalize(itemAlbumName);

        int score = 0;

        // Title.
        if (!targetFullTitle.isEmpty() && targetFullTitle.equals(itemFullTitle)) {
            score += 50;
        } else if (!targetBaseTitle.isEmpty() && !itemBaseTitle.isEmpty()) {
            if (targetBaseTitle.equals(itemBaseTitle)) {
                score += 35;
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
                List<String> itemArtists = extractArtists(itemArtistName);
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
                    score -= 50;
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

        // Word sync is preferred over line sync.
        if ("word".equalsIgnoreCase(itemTimingType)) {
            score += 15;
        } else if ("line".equalsIgnoreCase(itemTimingType)) {
            score += 8;
        } else {
            score -= 10;
        }

        // A remix, cover or other variant the track is not.
        String trackLower = (track.title() + " " + track.album()).toLowerCase(Locale.ROOT);
        String itemLower = (itemTrackName + " " + itemAlbumName).toLowerCase(Locale.ROOT);
        for (String kw : MODIFIER_KEYWORDS) {
            if (itemLower.contains(kw) && !trackLower.contains(kw)) {
                score -= 30;
            }
        }

        return score;
    }

    @Nullable
    private Lyrics downloadLyrics(String lyricsUrl) {
        if (lyricsUrl == null || lyricsUrl.trim().isEmpty()) {
            return null;
        }

        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(lyricsUrl);
            final int code = connection.getResponseCode();
            if (code != Requester.HTTP_STATUS_CODE_SUCCESS) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }

            String content = Requester.parseString(connection);
            if (content == null || content.trim().isEmpty()) {
                return null;
            }

            String trimmed = content.trim();
            if (trimmed.startsWith("<") || lyricsUrl.endsWith(".ttml")) {
                return TTMLParser.ttmlToLyrics(content, name(), lyricsUrl);
            } else {
                LRCParser.LrcParseResult lrc = LRCParser.parseSyncedWithCreditLines(content);
                if (!lrc.lines.isEmpty()) {
                    return new Lyrics(lrc.lines, name(), true, null, null, null,
                            lrc.creditLines.isEmpty() ? null : lrc.creditLines,
                            content, "lrc", lyricsUrl);
                }
                List<LyricsLine> plain = LRCParser.parsePlain(content);
                if (!plain.isEmpty()) {
                    return new Lyrics(plain, name(), false, null, null, null, null,
                            content, "lrc", lyricsUrl);
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "LrcRed download failed: " + lyricsUrl, ex);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }

        return null;
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

    private record ScoredCandidate(JSONObject candidate, int score) {
    }
}
