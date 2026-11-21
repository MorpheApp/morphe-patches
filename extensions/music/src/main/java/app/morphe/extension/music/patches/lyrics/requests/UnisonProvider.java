/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;
import app.morphe.extension.music.patches.lyrics.parsers.TTMLParser;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

public final class UnisonProvider implements LyricsProvider {

    private static final String BASE_URL = "https://unison.boidu.dev/lyrics";

    /** How many song+artist matches to ask for; the search response carries their lyrics. */
    private static final int SEARCH_LIMIT = 10;

    private static final String FALLBACK_KEY_ID =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static volatile String cachedKeyId;

    private static String getKeyId() {
        String cached = cachedKeyId;
        if (cached != null) {
            return cached;
        }
        synchronized (UnisonProvider.class) {
            if (cachedKeyId != null) {
                return cachedKeyId;
            }
            cachedKeyId = computeKeyId();
            return cachedKeyId;
        }
    }

    private static String computeKeyId() {
        try {
            final KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec("secp256r1"));
            final ECPublicKey publicKey = (ECPublicKey) generator.generateKeyPair().getPublic();
            final String x = base64Url(publicKey.getW().getAffineX());
            final String y = base64Url(publicKey.getW().getAffineY());
            final String canonical = "{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"" + x + "\",\"y\":\"" + y + "\"}";
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            final byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return toHex(hash);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not compute Unison key ID", ex);
            return FALLBACK_KEY_ID;
        }
    }

    static String base64Url(BigInteger value) {
        final byte[] bytes = value.toByteArray();
        final byte[] coordinate = new byte[32];
        int srcPos = 0;
        if (bytes.length > 32 && bytes[0] == 0) {
            srcPos = 1;
        }
        final int copyLen = Math.min(32, bytes.length - srcPos);
        System.arraycopy(bytes, srcPos, coordinate, 32 - copyLen, copyLen);
        return Base64.encodeToString(coordinate,
                Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    static String toHex(byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    static String sourceUrl(String videoId) {
        return "https://unison.boidu.dev/song/" + videoId;
    }

    private static Lyrics withSubmitter(@Nullable Lyrics lyrics, @Nullable String submitter) {
        return LyricsMerge.addCredit(lyrics, submitter == null ? null : "Submitted by " + submitter);
    }

    @Override
    public String name() {
        return "Unison";
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
        final String title = track.title() != null ? track.title() : "";
        final String artist = track.artist() != null ? track.artist() : "";
        final long videoLengthMs = VideoInformation.getVideoLength();
        final int duration = videoLengthMs > 0
                ? (int) (videoLengthMs / 1000)
                : track.durationSeconds();
        final String album = track.album();

        return FetchResult.of(fetchByVideoId(videoId, title, artist, duration, album));
    }

    @Override
    public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) throws IOException {
        final String videoId = VideoInformation.getVideoId();
        final JSONObject root = videoId.isEmpty() ? null
                : request(BASE_URL + "/variants/" + LyricsRequests.encode(videoId));
        final JSONArray data = root == null ? null : root.optJSONArray("data");
        if (data == null) {
            return Collections.emptyList();
        }
        List<LyricsRequests.VotedLyrics> found = new ArrayList<>();
        for (int i = 0; i < data.length(); i++) {
            JSONObject variant = data.optJSONObject(i);
            if (variant == null || variant.optBoolean("hidden", false)) {
                continue;
            }
            Lyrics lyrics = parseVariant(variant, videoId);
            if (lyrics != null && !lyrics.isEmpty()) {
                found.add(new LyricsRequests.VotedLyrics(variant.optInt("voteCount", 0), lyrics));
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
            final Lyrics lyrics = parseVariant(hit.variant(), hit.videoId());
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
            final Lyrics lyrics = parseVariant(hit.variant(), hit.videoId());
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

    /** The song+artist matches worth parsing, the best first. */
    private List<SearchHit> searchHits(TrackInfo track) {
        final String title = track.title();
        final String artist = track.artist();
        if (title == null || title.isEmpty() || artist == null || artist.isEmpty()) {
            return Collections.emptyList();
        }
        final StringBuilder url = new StringBuilder(BASE_URL);
        try {
            url.append("/search?song=").append(LyricsRequests.encode(title))
                    .append("&artist=").append(LyricsRequests.encode(artist))
                    .append("&limit=").append(SEARCH_LIMIT);
        } catch (UnsupportedEncodingException ex) {
            Logger.printDebug(() -> name() + " encode failure", ex);
            return Collections.emptyList();
        }
        final JSONObject root = request(url.toString());
        return rankSearchHits(root == null ? null : root.optJSONArray("data"), track);
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
            if (entry == null || entry.optBoolean("hidden", false)) {
                continue;
            }
            final String videoId = LyricsRequests.optString(entry, "videoId");
            if (videoId == null) {
                continue;
            }
            final String song = Objects.requireNonNullElse(
                    LyricsRequests.optString(entry, "song"), "");
            final String artist = Objects.requireNonNullElse(
                    LyricsRequests.optString(entry, "artist"), "");
            final long durationSec = entry.optLong("duration", 0);
            final int score = LyricsRequests.scoreTrackCandidate(
                    song, artist, durationSec, track);
            if (score >= 0) {
                gated.add(new SearchHit(videoId, song, artist, durationSec, score, entry));
            } else {
                final int raw = LyricsRequests.evaluate(song, artist, durationSec, track).score();
                if (raw > fallbackScore) {
                    fallbackScore = raw;
                    fallback = new SearchHit(videoId, song, artist, durationSec, score, entry);
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

    /** One song+artist match, with the lyric object the search response already carries. */
    record SearchHit(String videoId, String title, String artist, long durationSec, int score,
                     JSONObject variant) {
    }

    @Nullable
    private Lyrics fetchByVideoId(String videoId, String title, String artist,
                                  int duration, String album) {
        final StringBuilder url = new StringBuilder();
        try {
            url.append(BASE_URL)
                    .append("?v=").append(LyricsRequests.encode(videoId));
            if (!title.isEmpty()) {
                url.append("&song=").append(LyricsRequests.encode(title));
            }
            if (!artist.isEmpty()) {
                url.append("&artist=").append(LyricsRequests.encode(artist));
            }
            if (duration > 0) {
                url.append("&duration=").append(duration);
            }
            if (album != null && !album.isEmpty()) {
                url.append("&album=").append(LyricsRequests.encode(album));
            }
        } catch (UnsupportedEncodingException ex) {
            Logger.printDebug(() -> name() + " encode failure", ex);
            return null;
        }
        final JSONObject root = request(url.toString());
        final JSONObject data = root == null ? null : root.optJSONObject("data");
        return data == null ? null : parseVariant(data, videoId);
    }

    /** The response of the API, or null when it has nothing for the video. */
    @Nullable
    private JSONObject request(String url) {
        HttpURLConnection connection = null;
        try {
            connection = LyricsRequests.openConnection(url);
            connection.setRequestProperty("X-Key-ID", getKeyId());
            final int code = connection.getResponseCode();
            if (code == 404) {
                return null;
            }
            if (code != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            return Requester.parseJSONObject(connection);
        } catch (IOException | JSONException ex) {
            Logger.printDebug(() -> "Could not fetch Unison lyrics", ex);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** One submitted lyric, in the format it was submitted in. */
    @Nullable
    private Lyrics parseVariant(JSONObject variant, String videoId) {
        final String format = LyricsRequests.optString(variant, "format");
        final String lyrics = LyricsRequests.optString(variant, "lyrics");
        if (format == null || lyrics == null) {
            return null;
        }
        final JSONObject submitter = variant.optJSONObject("submitter");
        final String submitterName = submitter != null
                ? LyricsRequests.optString(submitter, "displayName")
                : null;
        return parseLyrics(format, lyrics, videoId, submitterName);
    }

    @Nullable
    private Lyrics parseLyrics(String format, String lyrics, String videoId,
                               @Nullable String submitter) {
        switch (format.toLowerCase(Locale.ROOT)) {
            case "ttml":
                return withSubmitter(
                        TTMLParser.ttmlToLyrics(lyrics, name(), sourceUrl(videoId)), submitter);
            case "lrc":
                LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(lyrics);
                if (result.lines.isEmpty()) {
                    return null;
                }
                return withSubmitter(new Lyrics(result.lines, name(), true,
                        null, null, null,
                        null, result.creditLines.isEmpty() ? null : result.creditLines,
                        lyrics, "lrc", sourceUrl(videoId), null, null), submitter);
            case "plain":
                final List<LyricsLine> plain = LRCParser.parsePlain(lyrics);
                if (plain.isEmpty()) {
                    return null;
                }
                return withSubmitter(new Lyrics(plain, name(), false,
                        null, null, null, null, lyrics, "plain", sourceUrl(videoId)), submitter);
            default:
                return null;
        }
    }
}
