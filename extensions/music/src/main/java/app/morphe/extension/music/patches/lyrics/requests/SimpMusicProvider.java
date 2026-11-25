/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class SimpMusicProvider implements LyricsProvider {

    private static final String BASE_URL = "https://api-lyrics.simpmusic.org/v1/";

    private static String baseUrl() {
        return LyricsEndpoints.directory(BASE_URL, Settings.SIMPMUSIC_ENDPOINT.get());
    }

    private static final String CLIENT_USER_AGENT = "SimpMusicLyrics/1.0";

    private static final String NAME = "SimpMusic";

    private static final int SEARCH_LIMIT = 10;

    private static final Pattern HTML_ENTITY =
            Pattern.compile("&#x([0-9a-fA-F]+);|&#(\\d+);");

    /** Several space kinds arrive in a body; only one is drawn, so they all fold to it. */
    private static final Pattern WHITESPACE =
            Pattern.compile("[\\s\\u00A0\\u2000-\\u200A\\u202F\\u3000]+");

    static String sourceUrl(String videoId) {
        return "https://lyrics.simpmusic.org/#/video/" + videoId;
    }

    static String sanitizeLrc(String lrc, long[] offsetHolder) {
        String[] rawLines = lrc.split("\\r?\\n");
        StringBuilder sb = new StringBuilder(lrc.length());

        for (String rawLine : rawLines) {
            String trimmed = rawLine.trim();
            if (trimmed.startsWith("[offset:")) {
                int close = trimmed.indexOf(']');
                if (close > 0) {
                    String val = trimmed.substring(8, close).trim();
                    try {
                        offsetHolder[0] = (long) (Double.parseDouble(val) * 1000);
                    } catch (NumberFormatException ex) {
                        Logger.printDebug(() -> "Could not parse offset in SimpMusic LRC", ex);
                    }
                }
                continue;
            }
            sb.append(rawLine).append('\n');
        }

        String result = decodeHtmlEntities(sb.toString());
        // The whitespace next to a tag marks a word boundary, so it is left for the parser
        // to read; only runs of spaces inside the text are folded here.
        result = result.replaceAll("[ \t]{2,}", " ");
        return result.trim();
    }

    static String decodeHtmlEntities(String text) {
        Matcher matcher = HTML_ENTITY.matcher(text);
        StringBuilder builder = new StringBuilder();
        int last = 0;
        while (matcher.find()) {
            builder.append(text, last, matcher.start());
            try {
                if (matcher.group(1) != null) {
                    builder.append((char) Integer.parseInt(Objects.requireNonNull(matcher.group(1)), 16));
                } else {
                    builder.append((char) Integer.parseInt(Objects.requireNonNull(matcher.group(2))));
                }
            } catch (NumberFormatException ex) {
                Logger.printDebug(() -> "Could not parse HTML entity in SimpMusic", ex);
                builder.append(matcher.group(0));
            }
            last = matcher.end();
        }
        builder.append(text, last, text.length());
        return builder.toString()
                .replace("&nbsp;", "\u00A0")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'");
    }

    /** A CJK word boundary is carried by the timings, so the spaces its richSync writes between words are noise in the drawn text. */
    static List<LyricsLine> normalizeLines(List<LyricsLine> lines) {
        List<LyricsLine> result = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            String text = removeCjkSpaces(WHITESPACE.matcher(line.text()).replaceAll(" ").trim());
            if (!text.equals(line.text())) {
                result.add(new LyricsLine(line.startTimeMs(), line.endTimeMs(), text, line.words(),
                        line.agentId(), line.isDuet(), line.isBG(), line.songPart()));
            } else {
                result.add(line);
            }
        }
        return result;
    }

    static String removeCjkSpaces(String text) {
        final int length = text.length();
        final StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            final char c = text.charAt(i);
            if (c != ' ') {
                sb.append(c);
                continue;
            }
            final boolean left = i > 0 && ScriptDetector.isHanOrKana(text.codePointBefore(i));
            final boolean right = i + 1 < length && ScriptDetector.isHanOrKana(text.codePointAt(i + 1));
            if (!(left && right)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    static Lyrics withContributor(@Nullable Lyrics lyrics, @Nullable String contributor) {
        return LyricsMerge.addCredit(lyrics,
                contributor == null ? null : "Submitted by " + contributor);
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
    public FetchResult fetch(TrackInfo track) {
        final String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty()) {
            return null;
        }
        return FetchResult.of(fetchByVideoId(videoId));
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) {
        final String videoId = VideoInformation.getVideoId();
        final JSONArray data = videoId.isEmpty() ? null : fetchEntries(videoId);
        if (data == null) {
            return Collections.emptyList();
        }
        List<LyricsRequests.VotedLyrics> found = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject entry = data.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            Lyrics lyrics = parseEntry(entry, videoId);
            if (lyrics != null && !lyrics.isEmpty()) {
                found.add(new LyricsRequests.VotedLyrics(entry.optInt("vote", 0), lyrics));
            }
        }
        return LyricsRequests.videoIdCandidates(found);
    }

    /**
     * Searches by the user's title and artist; automatic lookups stay keyed by video id.
     */
    @Nullable
    @Override
    public FetchResult fetchBySearch(TrackInfo track) {
        for (SearchHit hit : searchHits(track)) {
            final Lyrics lyrics = fetchByVideoId(hit.videoId());
            if (lyrics != null && !lyrics.isEmpty()) {
                return FetchResult.of(lyrics, hit.title(), hit.artist(),
                        hit.durationSec(), track);
            }
        }
        return null;
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidatesBySearch(TrackInfo track) {
        final List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        for (SearchHit hit : searchHits(track)) {
            final Lyrics lyrics = fetchByVideoId(hit.videoId());
            if (lyrics == null || lyrics.isEmpty()) {
                continue;
            }
            int score = LyricsRequests.scoreLyricsCandidate(
                    hit.title(), hit.artist(), hit.durationSec(), lyrics, track);
            if (score < 0) {
                // A search hit kept only as a fallback has no passing metadata score.
                score = hit.score() + LyricsRequests.syncRank(lyrics);
            }
            scored.add(new Lyrics.ScoredLyrics(score, lyrics));
        }
        return Lyrics.sortScoredByScore(scored);
    }

    /** The search hits worth asking for lyrics, the best first. */
    private List<SearchHit> searchHits(TrackInfo track) {
        final String title = track.title();
        final String artist = track.artist();
        if (title == null || title.isEmpty() || artist == null || artist.isEmpty()) {
            return Collections.emptyList();
        }
        return rankSearchHits(fetchSearchResults(title + " " + artist), track);
    }

    /**
     * Ranks hits by the shared match score, keeping one fallback when none passes.
     */
    static List<SearchHit> rankSearchHits(@Nullable JSONArray data, TrackInfo track) {
        if (data == null || data.length() == 0) {
            return Collections.emptyList();
        }
        final List<SearchHit> gated = new ArrayList<>();
        SearchHit fallback = null;
        int fallbackScore = Integer.MIN_VALUE;
        for (int i = 0; i < data.length(); i++) {
            final JSONObject entry = data.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            final String videoId = LyricsRequests.optString(entry, "videoId");
            if (videoId == null) {
                continue;
            }
            final String songTitle =
                    Objects.requireNonNullElse(LyricsRequests.optString(entry, "songTitle"), "");
            final String artistName =
                    Objects.requireNonNullElse(LyricsRequests.optString(entry, "artistName"), "");
            final long durationSec = entry.optLong("durationSeconds", 0);
            final int score = LyricsRequests.scoreTrackCandidate(
                    songTitle, artistName, durationSec, track);
            if (score >= 0) {
                gated.add(new SearchHit(videoId, songTitle, artistName, durationSec, score));
            } else {
                final int raw = LyricsRequests.evaluate(
                        songTitle, artistName, durationSec, track).score();
                if (raw > fallbackScore) {
                    fallbackScore = raw;
                    fallback = new SearchHit(videoId, songTitle, artistName, durationSec, score);
                }
            }
        }
        gated.sort((a, b) -> {
            if (a.score != b.score) {
                return b.score - a.score;
            }
            if (track.durationSeconds() <= 0) {
                return 0;
            }
            return Long.compare(Math.abs(a.durationSec - track.durationSeconds()),
                    Math.abs(b.durationSec - track.durationSeconds()));
        });
        if (!gated.isEmpty()) {
            return gated.size() > LyricsRequests.MAX_CANDIDATES
                    ? new ArrayList<>(gated.subList(0, LyricsRequests.MAX_CANDIDATES))
                    : gated;
        }
        return fallback == null ? Collections.emptyList() : List.of(fallback);
    }

    /** Metadata-only search; each hit's lyric is fetched by its video id. */
    @Nullable
    private JSONArray fetchSearchResults(String query) {
        HttpURLConnection connection = null;
        try {
            final String url = baseUrl() + "search?q=" + LyricsRequests.encode(query)
                    + "&limit=" + SEARCH_LIMIT;
            connection = LyricsRequests.openConnection(url);
            connection.setRequestProperty("User-Agent", CLIENT_USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            final int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }

            final JSONObject root = Requester.parseJSONObject(connection);
            if (!root.optBoolean("success", false)) {
                return null;
            }
            return root.optJSONArray("data");
        } catch (IOException | JSONException ex) {
            Logger.printDebug(() -> "Could not search SimpMusic API", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** One metadata-only search hit, before its lyric is fetched. */
    record SearchHit(String videoId, String title, String artist, long durationSec, int score) {
    }

    @Nullable
    private Lyrics fetchByVideoId(String videoId) {
        final JSONArray data = fetchEntries(videoId);
        return data == null ? null : pickBestEntry(data, videoId);
    }

    /** Every lyric the API has for the video, or null when it has none. */
    @Nullable
    private JSONArray fetchEntries(String videoId) {
        HttpURLConnection connection = null;
        try {
            final String url = baseUrl() + LyricsRequests.encode(videoId);
            connection = LyricsRequests.openConnection(url);
            connection.setRequestProperty("User-Agent", CLIENT_USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");

            final int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }

            JSONObject root = Requester.parseJSONObject(connection);
            if (!root.optBoolean("success", false)) {
                return null;
            }
            final JSONArray data = root.optJSONArray("data");
            return data == null || data.length() == 0 ? null : data;
        } catch (IOException | JSONException ex) {
            Logger.printDebug(() -> "Could not query SimpMusic API", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    @Nullable
    private Lyrics pickBestEntry(JSONArray data, String videoId) {
        Lyrics best = null;
        int bestVote = Integer.MIN_VALUE;
        for (int i = 0; i < data.length(); i++) {
            JSONObject entry = data.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            final int vote = entry.optInt("vote", 0);
            if (vote < bestVote) {
                continue;
            }
            final Lyrics lyrics = parseEntry(entry, videoId);
            if (lyrics != null && !lyrics.isEmpty()) {
                best = lyrics;
                bestVote = vote;
            }
        }
        return best;
    }

    @Nullable
    private Lyrics parseEntry(JSONObject entry, String videoId) {
        final String contributor = LyricsRequests.optString(entry, "contributor");
        final String richSync = LyricsRequests.optString(entry, "richSyncLyrics");
        if (richSync != null) {
            return withContributor(parseLrc(richSync, videoId), contributor);
        }
        final String synced = LyricsRequests.optString(entry, "syncedLyrics");
        if (synced != null) {
            return withContributor(parseLrc(synced, videoId), contributor);
        }
        final String plain = LyricsRequests.optString(entry, "plainLyric");
        if (plain != null) {
            final List<LyricsLine> lines = normalizeLines(LRCParser.parsePlain(plain));
            if (!lines.isEmpty()) {
                return withContributor(new Lyrics(lines, name(), false,
                        null, null, null, null, plain, "plain", sourceUrl(videoId)), contributor);
            }
        }
        return null;
    }

    @Nullable
    static Lyrics parseLrc(String lrc, String videoId) {
        final long[] offsetHolder = {0};
        final String sanitized = sanitizeLrc(lrc, offsetHolder);
        final LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(sanitized);
        if (result.lines.isEmpty()) {
            return null;
        }
        List<LyricsLine> lines = normalizeLines(result.lines);
        if (offsetHolder[0] != 0) {
            lines = LyricsRequests.applyOffset(lines, offsetHolder[0]);
        }
        return new Lyrics(lines, NAME, true,
                null, null, null,
                null, result.creditLines.isEmpty() ? null : result.creditLines,
                sanitized, "lrc", sourceUrl(videoId), null, null);
    }
}
