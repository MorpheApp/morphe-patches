/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.patches.lyrics.Word;
import app.morphe.extension.music.settings.Settings;

public final class SpotifyProvider implements LyricsProvider {

    private static final String SECRETS_URL =
            "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/refs/heads/main/secrets/secretDict.json";
    private static final String TOKEN_URL = "https://open.spotify.com/api/token";
    private static final String SERVER_TIME_URL = "https://open.spotify.com/api/server-time";
    private static final String CLIENT_TOKEN_URL = "https://clienttoken.spotify.com/v1/clienttoken";
    private static final String PARTNER_GRAPHQL_URL =
            "https://api-partner.spotify.com/pathfinder/v2/query";
    private static final String[] SEARCH_HASHES = {
            "0dff51c99e552b992377a2a6f40d213dc42b62db86ca0bcf16cf3934aec1aae6",
            "75bbf6bfcfdf85b8fc828417bfad92b7cd66bf7f556d85670f4da8292373ebec",
    };
    private static final String LYRICS_URL = "https://spclient.wg.spotify.com/color-lyrics/v2/track/";
    private static final String CLIENT_VERSION = "1.2.57.183.g8c1b7eb0";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    @Nullable
    private static volatile String cachedAccessToken;
    @Nullable
    private static volatile String cachedClientToken;
    private static long clientTokenExpiry = 0;
    @Nullable
    private static String cachedClientId;
    @Nullable
    private static String[] cachedTotpSecrets;  // XOR-decoded decimal string (HMAC key = UTF-8 bytes of this)
    @Nullable
    private static String cachedTotpVersion;
    private static long lastSecretFetchTime = 0;
    private static final long SECRET_REFRESH_INTERVAL_MS = 60 * 60 * 1000;

    private static final int[] FALLBACK_SECRET_DATA = {
            99, 111, 47, 88, 49, 56, 118, 65, 52, 67, 50, 104,
            117, 101, 55, 94, 95, 75, 94, 49, 69, 36, 85, 64, 74, 60
    };
    private static final String FALLBACK_TOTP_VERSION = "19";

    @Override
    public String name() {
        return "Spotify";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String spDc = Settings.SPOTIFY_TOKEN.get();
        if (spDc == null || spDc.isBlank()) {
            return null;
        }

        final String trackId = searchTrack(spDc, track.title(), track.artist());
        if (trackId == null) {
            return null;
        }

        final String sourceUrl = "https://open.spotify.com/track/" + trackId;
        final JSONObject lyricsResponse = fetchLyrics(spDc, trackId);
        if (lyricsResponse == null) {
            return null;
        }

        final String rawJson = lyricsResponse.toString();
        return parseLyrics(lyricsResponse, rawJson, sourceUrl);
    }

    @Nullable
    private String searchTrack(String spDc, String title, String artist) throws Exception {
        final String accessToken = getAccessToken(spDc);
        if (accessToken == null) {
            return null;
        }

        final String clientToken = getClientToken(spDc);

        final String query = title + " " + artist;

        for (String hash : SEARCH_HASHES) {
            final String body = new JSONObject()
                    .put("extensions", new JSONObject()
                            .put("persistedQuery", new JSONObject()
                                    .put("sha256Hash", hash)
                                    .put("version", 1)))
                    .put("operationName", "searchDesktop")
                    .put("variables", new JSONObject()
                            .put("searchTerm", query)
                            .put("limit", 5)
                            .put("numberOfTopResults", 5)
                            .put("offset", 0))
                    .toString();

            final String trackId = executeSearch(accessToken, clientToken, body, hash);
            if (trackId != null) {
                return trackId;
            }
        }

        return null;
    }

    @Nullable
    private String executeSearch(String accessToken, @Nullable String clientToken,
                                  String body, String hash) throws Exception {
        for (int attempt = 0; attempt <= 2; attempt++) {
            final HttpURLConnection connection = (HttpURLConnection)
                    new java.net.URL(PARTNER_GRAPHQL_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("app-platform", "WebPlayer");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Referer", "https://open.spotify.com/");
            connection.setRequestProperty("Origin", "https://open.spotify.com");
            connection.setRequestProperty("spotify-app-version", CLIENT_VERSION);
            if (clientToken != null) {
                connection.setRequestProperty("client-token", clientToken);
            }

            final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bodyBytes.length);
            try (java.io.OutputStream os = connection.getOutputStream()) {
                os.write(bodyBytes);
            }

            final int code = connection.getResponseCode();

            if (code == 200) {
                final String json = parseBody(connection);
                connection.disconnect();

                final String trackId = parseSearchResult(json);
                if (trackId != null) {
                    return trackId;
                }
                break; // got 200 but no tracks — don't retry, try next hash
            }

            if (code == 429 && attempt < 2) {
                final long retryAfterMs = parseRetryAfter(connection);
                connection.disconnect();
                try {
                    Thread.sleep(retryAfterMs);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                continue;
            }

            final String errorBody = parseErrorBody(connection);
            connection.disconnect();
            return null;
        }

        return null;
    }

    @Nullable
    private String parseSearchResult(String json) {
        try {
            final JSONObject root = new JSONObject(json);
            final JSONObject data = root.optJSONObject("data");
            if (data == null) return null;

            final JSONObject searchV2 = data.optJSONObject("searchV2");
            if (searchV2 == null) return null;

            JSONArray items = null;
            final JSONObject tracks = searchV2.optJSONObject("tracks");
            if (tracks != null) {
                items = tracks.optJSONArray("items");
            }
            if (items == null || items.length() == 0) {
                final JSONObject search = data.optJSONObject("search");
                if (search != null) {
                    final JSONObject searchTracks = search.optJSONObject("tracks");
                    if (searchTracks != null) {
                        items = searchTracks.optJSONArray("items");
                    }
                }
            }
            if (items == null || items.length() == 0) {
                return null;
            }

            return extractTrackId(items);
        } catch (Exception ex) {
            return null;
        }
    }

    @Nullable
    private String extractTrackId(JSONArray items) {
        for (int i = 0; i < items.length(); i++) {
            final JSONObject itemWrapper = items.optJSONObject(i);
            if (itemWrapper == null) continue;

            final JSONObject dataNode = itemWrapper.optJSONObject("data");
            final JSONObject node = dataNode != null ? dataNode : itemWrapper;

            String id = node.optString("id", null);
            if (id == null || id.isEmpty()) {
                final String uri = node.optString("uri", null);
                if (uri != null && uri.startsWith("spotify:track:")) {
                    id = uri.substring("spotify:track:".length());
                }
            }
            if (id != null && !id.isEmpty()) {
                return id;
            }
        }

        return null;
    }

    @Nullable
    private JSONObject fetchLyrics(String spDc, String trackId) throws Exception {
        final String accessToken = getAccessToken(spDc);
        if (accessToken == null) {
            return null;
        }

        final String url = LYRICS_URL + trackId + "?format=json&market=from_token";
        HttpURLConnection connection = LyricsRequests.openConnection(url);
        connection.setRequestProperty("Authorization", "Bearer " + accessToken);
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("app-platform", "WebPlayer");

        final int code = connection.getResponseCode();
        if (code != 200) {
            LyricsRequests.logFailure(name(), connection);
            return null;
        }

        final String json = LyricsRequests.parseGzipString(connection);
        return new JSONObject(json);
    }

    @Nullable
    private Lyrics parseLyrics(JSONObject response, String rawJson, @Nullable String sourceUrl) {
        final JSONObject lyricsObj = response.optJSONObject("lyrics");
        if (lyricsObj == null) {
            return null;
        }

        final String syncType = lyricsObj.optString("syncType", "UNSYNCED");
        final JSONArray linesArr = lyricsObj.optJSONArray("lines");
        if (linesArr == null || linesArr.length() == 0) {
            return null;
        }

        if ("SYLLABLE_SYNCED".equals(syncType)) {
            return parseSyllableLines(linesArr, rawJson, sourceUrl);
        } else if (!"UNSYNCED".equals(syncType)) {
            return parseSyncedLines(linesArr, rawJson, sourceUrl);
        } else {
            return parsePlainLines(linesArr, rawJson, sourceUrl);
        }
    }

    @Nullable
    private Lyrics parseSyncedLines(JSONArray linesArr, String rawJson, @Nullable String sourceUrl) {
        final List<LyricsLine> lines = new ArrayList<>(linesArr.length());

        for (int i = 0; i < linesArr.length(); i++) {
            final JSONObject lineObj = linesArr.optJSONObject(i);
            if (lineObj == null) {
                continue;
            }

            final long startTimeMs = parseStartTimeMs(lineObj);
            final String text = lineObj.optString("words", "").trim();
            if (text.isEmpty()) {
                continue;
            }

            lines.add(new LyricsLine(startTimeMs, text));
        }

        if (lines.isEmpty()) {
            return null;
        }
        return new Lyrics(lines, name(), true, null, null, null, null, rawJson, "sp.json", sourceUrl);
    }

    private static List<Word> distributeWords(String[] tokens, long startMs, long durationMs) {
        final List<Word> words = new ArrayList<>(tokens.length);
        if (tokens.length == 0) {
            return words;
        }

        final long perWord = durationMs / tokens.length;
        long cursor = startMs;

        for (int j = 0; j < tokens.length; j++) {
            final long wordEnd = cursor + perWord;
            final boolean spaceAfter = j < tokens.length - 1;
            words.add(new Word(cursor, wordEnd, tokens[j], null, spaceAfter));
            cursor = wordEnd;
        }
        return words;
    }

    @Nullable
    private Lyrics parseSyllableLines(JSONArray linesArr, String rawJson,
                                       @Nullable String sourceUrl) {
        final List<LyricsLine> lines = new ArrayList<>(linesArr.length());

        for (int i = 0; i < linesArr.length(); i++) {
            final JSONObject lineObj = linesArr.optJSONObject(i);
            if (lineObj == null) {
                continue;
            }

            final long startTimeMs = parseStartTimeMs(lineObj);
            final String text = lineObj.optString("words", "").trim();
            if (text.isEmpty()) {
                continue;
            }

            final JSONArray syllablesArr = lineObj.optJSONArray("syllables");
            final List<Word> words;
            if (syllablesArr != null && syllablesArr.length() > 0) {
                words = parseSyllables(syllablesArr, text);
            } else {
                final long nextStartMs;
                if (i + 1 < linesArr.length()) {
                    nextStartMs = peekNextStartTime(linesArr, i + 1, startTimeMs + 2000);
                } else {
                    nextStartMs = startTimeMs + 2000;
                }
                final long lineDuration = Math.max(nextStartMs - startTimeMs, 100);
                words = distributeWords(text.split("\\s+"), startTimeMs, lineDuration);
            }

            lines.add(new LyricsLine(startTimeMs, text, words));
        }

        if (lines.isEmpty()) {
            return null;
        }
        return new Lyrics(lines, name(), true, null, null, null, null, rawJson, "sp.json", sourceUrl);
    }

    private static List<Word> parseSyllables(JSONArray syllablesArr, String lineText) {
        final List<Word> words = new ArrayList<>(syllablesArr.length());
        int charOffset = 0;

        for (int j = 0; j < syllablesArr.length(); j++) {
            final JSONObject syllable = syllablesArr.optJSONObject(j);
            if (syllable == null) {
                continue;
            }

            final long startMs = parseStartTimeMs(syllable);
            final long endMs = syllable.optLong("endTimeMs", startMs);
            final int numChars = syllable.optInt("numChars", 0);
            if (numChars <= 0 || charOffset >= lineText.length()) {
                continue;
            }

            final int end = Math.min(charOffset + numChars, lineText.length());
            final String wordText = lineText.substring(charOffset, end);
            final boolean spaceAfter = end < lineText.length();
            words.add(new Word(startMs, endMs, wordText, null, spaceAfter));
            charOffset = end;
        }

        return words;
    }

    private static long parseStartTimeMs(JSONObject obj) {
        final String raw = obj.optString("startTimeMs", null);
        if (raw == null || raw.isEmpty()) {
            return LyricsLine.NO_TIME;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return LyricsLine.NO_TIME;
        }
    }

    private static long peekNextStartTime(JSONArray linesArr, int index, long fallback) {
        final JSONObject next = linesArr.optJSONObject(index);
        if (next != null) {
            final long nextStart = parseStartTimeMs(next);
            if (nextStart > 0) {
                return nextStart;
            }
        }
        return fallback;
    }

    @Nullable
    private Lyrics parsePlainLines(JSONArray linesArr, String rawJson, @Nullable String sourceUrl) {
        final List<LyricsLine> lines = new ArrayList<>(linesArr.length());

        for (int i = 0; i < linesArr.length(); i++) {
            final JSONObject lineObj = linesArr.optJSONObject(i);
            if (lineObj == null) {
                continue;
            }

            final String text = lineObj.optString("words", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            lines.add(new LyricsLine(LyricsLine.NO_TIME, text));
        }

        if (lines.isEmpty()) {
            return null;
        }
        return new Lyrics(lines, name(), false, null, null, null, null, rawJson, "sp.json", sourceUrl);
    }

    @Nullable
    private synchronized String getAccessToken(String spDc) {
        if (cachedAccessToken != null) {
            return cachedAccessToken;
        }

        try {
            ensureTotpSecrets();

            final long serverTimeMs = getServerTime(spDc);

            final String totpValue = generateTotp(serverTimeMs);

            final String url = TOKEN_URL
                    + "?reason=init&productType=web-player"
                    + "&totp=" + totpValue
                    + "&totpVer=" + cachedTotpVersion
                    + "&totpServer=" + totpValue;

            final HttpURLConnection connection = (HttpURLConnection)
                    new java.net.URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("App-Platform", "WebPlayer");
            connection.setRequestProperty("Referer", "https://open.spotify.com/");
            connection.setRequestProperty("Cookie", "sp_dc=" + spDc);

            final int code = connection.getResponseCode();
            if (code != 200) {
                final String errorBody = parseErrorBody(connection);
                return null;
            }

            final String body = parseBody(connection);
            final JSONObject json = new JSONObject(body);
            final String token = json.optString("accessToken", "");
            if (token.isBlank()) {
                return null;
            }
            cachedAccessToken = token;

            final String clientId = json.optString("clientId", null);
            if (clientId != null && !clientId.isEmpty()) {
                cachedClientId = clientId;
            }

            return cachedAccessToken;
        } catch (Exception ex) {
            return null;
        }
    }

    @Nullable
    private synchronized String getClientToken(String spDc) {
        final long now = System.currentTimeMillis();
        if (cachedClientToken != null && now < clientTokenExpiry) {
            return cachedClientToken;
        }

        try {
            final String accessToken = getAccessToken(spDc);
            if (accessToken == null) {
                return null;
            }

            final String clientId = cachedClientId;
            if (clientId == null || clientId.isEmpty()) {
                return null;
            }

            final String deviceId = UUID.randomUUID().toString();
            final JSONObject jsSdkData = new JSONObject()
                    .put("device_brand", "unknown")
                    .put("device_model", "unknown")
                    .put("os", "linux")
                    .put("os_version", "unknown")
                    .put("device_id", deviceId)
                    .put("device_type", "computer");

            final JSONObject clientData = new JSONObject()
                    .put("client_version", CLIENT_VERSION)
                    .put("client_id", clientId)
                    .put("js_sdk_data", jsSdkData);

            final JSONObject requestBody = new JSONObject()
                    .put("client_data", clientData);

            final HttpURLConnection connection = (HttpURLConnection)
                    new java.net.URL(CLIENT_TOKEN_URL).openConnection();
            connection.setRequestMethod("POST");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Origin", "https://open.spotify.com");
            connection.setRequestProperty("Referer", "https://open.spotify.com");

            final byte[] bodyBytes = requestBody.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bodyBytes.length);
            try (java.io.OutputStream os = connection.getOutputStream()) {
                os.write(bodyBytes);
            }

            final int code = connection.getResponseCode();
            if (code != 200) {
                final String errorBody = parseErrorBody(connection);
                connection.disconnect();
                return null;
            }

            final String body = parseBody(connection);
            final JSONObject json = new JSONObject(body);
            final JSONObject grantedToken = json.optJSONObject("granted_token");
            if (grantedToken == null) {
                connection.disconnect();
                return null;
            }

            final String token = grantedToken.optString("token", "");
            if (token.isEmpty()) {
                connection.disconnect();
                return null;
            }

            final long expiresIn = grantedToken.optLong("expires_after", 3600);
            clientTokenExpiry = now + (expiresIn * 1000) - 60_000;
            cachedClientToken = token;

            connection.disconnect();
            return cachedClientToken;
        } catch (Exception ex) {
            return null;
        }
    }

    private long getServerTime(String spDc) {
        try {
            final HttpURLConnection connection = (HttpURLConnection)
                    new java.net.URL(SERVER_TIME_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Origin", "https://open.spotify.com/");
            connection.setRequestProperty("Referer", "https://open.spotify.com/");
            connection.setRequestProperty("Cookie", "sp_dc=" + spDc);

            final int code = connection.getResponseCode();
            if (code == 200) {
                final String body = parseBody(connection);
                final JSONObject json = new JSONObject(body);
                final long serverTime = json.optLong("serverTime", 0);
                if (serverTime > 0) {
                    return serverTime * 1000;
                }
            }
        } catch (Exception ex) {
        }
        return System.currentTimeMillis();
    }

    private void ensureTotpSecrets() throws Exception {
        final long now = System.currentTimeMillis();
        if (cachedTotpSecrets != null && cachedTotpVersion != null
                && (now - lastSecretFetchTime) < SECRET_REFRESH_INTERVAL_MS) {
            return;
        }

        try {
            final String body = fetchUrlWithRetry(SECRETS_URL, 2);
            if (body != null) {
                final JSONObject secrets = new JSONObject(body);

                int newestVersion = -1;
                Iterator<String> keys = secrets.keys();
                while (keys.hasNext()) {
                    String key = keys.next();
                    try {
                        int v = Integer.parseInt(key);
                        if (v > newestVersion) {
                            newestVersion = v;
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }

                if (newestVersion >= 0) {
                    final String previousVersion = cachedTotpVersion;
                    cachedTotpVersion = String.valueOf(newestVersion);
                    final JSONArray arr = secrets.getJSONArray(cachedTotpVersion);
                    final int[] secretData = new int[arr.length()];
                    for (int i = 0; i < arr.length(); i++) {
                        secretData[i] = arr.getInt(i);
                    }

                    final int[] xored = new int[secretData.length];
                    for (int i = 0; i < secretData.length; i++) {
                        xored[i] = secretData[i] ^ ((i % 33) + 9);
                    }

                    cachedTotpSecrets = new String[]{ decimalStringFromInts(xored) };

                    if (previousVersion != null && !cachedTotpVersion.equals(previousVersion)) {
                        cachedAccessToken = null;
                    }

                    lastSecretFetchTime = now;
                    return;
                }
            }
        } catch (Exception ex) {
        }

        if (cachedTotpSecrets == null || cachedTotpVersion == null) {
            cachedTotpSecrets = new String[]{ decimalStringFromInts(FALLBACK_SECRET_DATA) };
            cachedTotpVersion = FALLBACK_TOTP_VERSION;
            lastSecretFetchTime = now;
        }
    }

    private String generateTotp(long timestampMs) throws NoSuchAlgorithmException, InvalidKeyException {
        final long epochSeconds = timestampMs / 1000;
        final long counter = epochSeconds / 30;

        final byte[] counterBytes = new byte[8];
        long tmp = counter;
        for (int i = 7; i >= 0; i--) {
            counterBytes[i] = (byte) (tmp & 0xFF);
            tmp >>= 8;
        }

        final Mac mac = Mac.getInstance("HmacSHA1");
        final byte[] secretBytes = cachedTotpSecrets[0].getBytes(StandardCharsets.UTF_8);
        mac.init(new SecretKeySpec(secretBytes, "HmacSHA1"));
        final byte[] hash = mac.doFinal(counterBytes);

        final int offset = hash[hash.length - 1] & 0x0F;
        final int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);

        final int otp = binary % 1_000_000;
        return String.format(Locale.US, "%06d", otp);
    }

    @Nullable
    private static String fetchUrlWithRetry(String url, int maxRetries) {
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                final HttpURLConnection connection = (HttpURLConnection)
                        new java.net.URL(url).openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(8000);
                connection.setRequestProperty("User-Agent", USER_AGENT);

                final int code = connection.getResponseCode();
                if (code == 200) {
                    return parseBody(connection);
                }
            } catch (IOException ex) {
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(1000L * (attempt + 1));
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return null;
                }
            }
        }
        return null;
    }

    private static String decimalStringFromInts(int[] values) {
        final StringBuilder sb = new StringBuilder(values.length);
        for (int v : values) {
            sb.append(v);
        }
        return sb.toString();
    }

    private static long parseRetryAfter(HttpURLConnection connection) {
        final String retryAfter = connection.getHeaderField("Retry-After");
        if (retryAfter != null) {
            try {
                return Long.parseLong(retryAfter) * 1000;
            } catch (NumberFormatException ignored) {
            }
        }
        return 3000; // default 3 seconds
    }

    private static String parseBody(HttpURLConnection connection) throws IOException {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    @Nullable
    private static String parseErrorBody(HttpURLConnection connection) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
            final StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Exception ex) {
            return null;
        }
    }

    public static void invalidateToken() {
        cachedAccessToken = null;
        cachedClientToken = null;
        clientTokenExpiry = 0;
        cachedClientId = null;
    }

    public static boolean validateToken(String spDc) {
        if (spDc == null || spDc.isBlank()) return false;
        try {
            final HttpURLConnection connection = (HttpURLConnection)
                    new java.net.URL(SERVER_TIME_URL).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(8000);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Origin", "https://open.spotify.com/");
            connection.setRequestProperty("Referer", "https://open.spotify.com/");
            connection.setRequestProperty("Cookie", "sp_dc=" + spDc);
            final int code = connection.getResponseCode();
            connection.disconnect();
            return code == 200;
        } catch (Exception ex) {
            return false;
        }
    }
}
