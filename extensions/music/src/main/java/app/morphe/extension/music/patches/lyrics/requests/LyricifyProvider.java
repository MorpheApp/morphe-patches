/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import android.util.Base64;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.shared.Logger;

public final class LyricifyProvider implements LyricsProvider {

    private static final String API_BASE =
            "https://api.lyricify.app/lyrics/get/mobile/new";

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    private static final Random RNG = new Random();

    private static final ConcurrentHashMap<String, String> isrcCache =
            new ConcurrentHashMap<>();

    @Override
    public String name() {
        return "Lyricify";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String cacheKey = track.title().toLowerCase(Locale.ROOT)
                + "|" + track.artist().toLowerCase(Locale.ROOT);
        String isrc = isrcCache.get(cacheKey);
        if (isrc == null) {
            isrc = fetchIsrcFromCreditsFm(track.title(), track.artist());
            if (isrc != null && !isrc.isEmpty()) {
                isrcCache.put(cacheKey, isrc);
            }
        }
        if (isrc == null || isrc.isEmpty()) {
            return null;
        }

        final String username = generateUsername();
        final String isrcParam = base64NoWrap(isrc);
        final String isrcEncoded = LyricsRequests.encode(isrcParam);

        final String url = API_BASE
                + "?username=" + username
                + "&isrc=" + isrcEncoded;

        final HttpURLConnection conn = openApi(url);

        final int code = conn.getResponseCode();
        if (code != 200) {
            LyricsRequests.logFailure(name(), conn);
            conn.disconnect();
            return null;
        }

        final String json = LyricsRequests.parseGzipString(conn);
        conn.disconnect();

        if (json.isEmpty()) {
            return null;
        }

        JSONObject response = new JSONObject(json);

        if (response.optBoolean("isInstrumental", false)) {
            return null;
        }

        final String text = LyricsRequests.optString(response, "text");
        if (text == null || text.isEmpty()) {
            return null;
        }

        final int offset = response.optInt("offset", 0);
        final String writer = LyricsRequests.optString(response, "writer");
        final String trans = LyricsRequests.optString(response, "trans");

        final boolean isSyllable = text.contains("[from:AppleSyllable]");
        final List<LyricsLine> lines;
        if (isSyllable) {
            lines = LyricifyParser.parseSyllable(text, offset);
        } else {
            lines = LyricifyParser.parseLines(text, offset);
        }

        if (lines.isEmpty()) {
            return null;
        }

        final List<String> creditLines = new ArrayList<>();
        if (writer != null && !writer.isEmpty()) {
            creditLines.add("Written by " + writer);
        }

        final Map<String, List<LyricsLine>> translations;
        final String deviceLang = Locale.getDefault().getLanguage();
        if ("zh".equals(deviceLang) && trans != null && !trans.isEmpty()) {
            final List<String> translatedTexts = LyricifyParser.parseTranslation(trans, offset);
            if (!translatedTexts.isEmpty()) {
                while (translatedTexts.size() < lines.size()) {
                    translatedTexts.add("");
                }
                final List<LyricsLine> translationLines = new ArrayList<>(lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    final LyricsLine original = lines.get(i);
                    final String tText = translatedTexts.get(i);
                    if (tText.isEmpty()) {
                        translationLines.add(new LyricsLine(
                                original.startTimeMs(), original.endTimeMs(), "", List.of()));
                    } else {
                        translationLines.add(new LyricsLine(
                                original.startTimeMs(), original.endTimeMs(), tText, List.of()));
                    }
                }
                translations = Map.of("zh", Collections.unmodifiableList(translationLines));
            } else {
                translations = null;
            }
        } else {
            translations = null;
        }

        final String formatType = isSyllable ? "lys" : "lyl";
        return new Lyrics(
                lines,
                name(),
                true,
                null,
                translations,
                null,
                creditLines.isEmpty() ? null : creditLines,
                text,
                formatType,
                null);
    }

    private static HttpURLConnection openApi(String url) throws IOException {
        final HttpURLConnection conn =
                (HttpURLConnection) new java.net.URL(url).openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("User-Agent", USER_AGENT);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Encoding", "gzip");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(8000);
        return conn;
    }

    @Nullable
    private static String fetchIsrcFromCreditsFm(String title, String artist) {
        HttpURLConnection connection = null;
        try {
            final String url = "https://api.credits.fm/v1/resolve/track";
            connection = (HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);

            final String body = new JSONObject()
                    .put("name", title)
                    .put("artist", artist)
                    .toString();
            final byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bodyBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bodyBytes);
            }

            final int code = connection.getResponseCode();
            if (code == 200) {
                final String responseBody = LyricsRequests.parseGzipString(connection);
                if (responseBody.isEmpty()) {
                    return null;
                }
                JSONObject response = new JSONObject(responseBody);
                final String isrc = LyricsRequests.optString(response, "isrc");
                if (isrc != null && !isrc.isEmpty()) {
                    return isrc;
                }
                return null;
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "Could not read the ISRC", e);
        } finally {
            if (connection != null) connection.disconnect();
        }
        return null;
    }

    private static String generateUsername() {
        final int len = 5 + RNG.nextInt(8);
        final StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + RNG.nextInt(26)));
        }
        return sb.toString();
    }

    private static String base64NoWrap(String input) {
        return Base64.encodeToString(
                input.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
    }
}
