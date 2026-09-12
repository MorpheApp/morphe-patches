/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.LrcParser;
import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.requests.Requester;

public final class UnisonProvider implements LyricsProvider {

    private static final String BASE_URL = "https://unison.boidu.dev/lyrics";

    private static final String FALLBACK_KEY_ID =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static volatile String cachedKeyId;

    @Override
    public String name() {
        return "Unison";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String videoId = VideoInformation.getVideoId();
        if (videoId == null || videoId.isEmpty()) {
            return null;
        }
        final String title = track.title() != null ? track.title() : "";
        final String artist = track.artist() != null ? track.artist() : "";
        final int duration = track.durationSeconds();
        final String album = track.album();

        // Unison is keyed on the YouTube video id; the server chooses the matching lyrics,
        // mirroring the Better Lyrics client. There is no fuzzy metadata-search fallback.
        return fetchByVideoId(videoId, title, artist, duration, album);
    }

    @Nullable
    private Lyrics fetchByVideoId(String videoId, String title, String artist,
                                  int duration, String album) {
        HttpURLConnection connection = null;
        try {
            final StringBuilder url = new StringBuilder(BASE_URL)
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

            connection = openUnisonConnection(url.toString());
            final int code = connection.getResponseCode();
            if (code == 404) {
                connection.disconnect();
                return null;
            }
            if (code != 200) {
                LyricsRequests.logFailure(name(), connection);
                return null;
            }
            final JSONObject root = Requester.parseJSONObject(connection);
            final JSONObject data = root.optJSONObject("data");
            if (data == null) {
                return null;
            }
            final String format = LyricsRequests.optString(data, "format");
            final String lyrics = LyricsRequests.optString(data, "lyrics");
            if (format == null || lyrics == null) {
                return null;
            }
            return parseLyrics(format, lyrics);
        } catch (IOException | JSONException ex) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static HttpURLConnection openUnisonConnection(String url) throws IOException {
        final HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("X-Key-ID", getKeyId());
        return connection;
    }

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
            return FALLBACK_KEY_ID;
        }
    }

    private static String base64Url(BigInteger value) {
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

    private static String toHex(byte[] bytes) {
        final StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    @Nullable
    private Lyrics parseLyrics(String format, String lyrics) {
        switch (format.toLowerCase(Locale.ROOT)) {
            case "ttml":
                return TtmlParser.ttmlToLyrics(lyrics, name(), null);
            case "lrc":
                LrcParser.LrcParseResult result = LrcParser.parseSyncedWithCreditLines(lyrics);
                if (result.lines.isEmpty()) {
                    return null;
                }
                return new Lyrics(result.lines, name(), true, null, null, null,
                        result.creditLines.isEmpty() ? null : result.creditLines,
                        lyrics, "lrc", null);
            case "plain":
                final List<LyricsLine> plain = LrcParser.parsePlain(lyrics);
                if (plain.isEmpty()) {
                    return null;
                }
                return new Lyrics(plain, name(), false, null, null, null, null, lyrics, "plain", null);
            default:
                return null;
        }
    }
}
