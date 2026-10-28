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
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.parsers.LyricifyParser;
import app.morphe.extension.shared.Logger;

public final class LyricifyProvider implements LyricsProvider {

    private static final String API_BASE =
            "https://api.lyricify.app/lyrics/get/mobile/new";

    private static final String USER_AGENT = LyricsRequests.CHROME_DESKTOP_USER_AGENT;

    private static final long REQUEST_THROTTLE_MS = 500;

    private static final AtomicLong lastRequestTime = new AtomicLong(0);

    private static final Random RNG = new Random();

    private static final ConcurrentHashMap<String, Resolved> resolvedCache =
            new ConcurrentHashMap<>();

    private record Resolved(String isrc, List<String> titles, List<String> artists) {}

    @Override
    public String name() {
        return "Lyricify";
    }

    @Nullable
    @Override
    public FetchResult fetch(TrackInfo track) throws Exception {
        String cacheKey = track.title().toLowerCase(Locale.ROOT)
                + "|" + track.artist().toLowerCase(Locale.ROOT);
        Resolved resolved = resolvedCache.get(cacheKey);
        if (resolved == null) {
            resolved = resolveFromCreditsFm(track.title(), track.artist());
            if (resolved != null) {
                resolvedCache.put(cacheKey, resolved);
            }
        }
        if (resolved == null) {
            return null;
        }
        String isrc = resolved.isrc();

        String username = generateUsername();
        String isrcParam = Base64.encodeToString(
                isrc.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        String isrcEncoded = LyricsRequests.encode(isrcParam);

        String url = API_BASE
                + "?username=" + username
                + "&isrc=" + isrcEncoded;

        LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
        HttpURLConnection conn = openApi(url);

        final int code = conn.getResponseCode();
        if (code != 200) {
            LyricsRequests.logFailure(name(), conn);
            conn.disconnect();
            return null;
        }

        String json = LyricsRequests.parseGzipString(conn);
        conn.disconnect();

        if (json.isEmpty()) {
            return null;
        }

        JSONObject response = new JSONObject(json);

        if (response.optBoolean("isInstrumental", false)) {
            return null;
        }

        String text = LyricsRequests.optString(response, "text");
        if (text == null || text.isEmpty()) {
            return null;
        }

        final int offset = response.optInt("offset", 0);
        String writer = LyricsRequests.optString(response, "writer");
        String trans = LyricsRequests.optString(response, "trans");

        final boolean isSyllable = text.contains("[from:AppleSyllable]");
        List<LyricsLine> lines;
        if (isSyllable) {
            lines = LyricifyParser.parseSyllable(text, offset);
        } else {
            lines = LyricifyParser.parseLines(text, offset);
        }

        if (lines.isEmpty()) {
            return null;
        }

        List<String> creditLines = new ArrayList<>();
        if (writer != null && !writer.isEmpty()) {
            creditLines.add("Written by " + writer);
        }

        Map<String, List<LyricsLine>> translations;
        if ("zh".equals(LyricsRequests.primarySubtag(LyricsRequests.translationLanguage()))
                && trans != null && !trans.isEmpty()) {
            List<String> translatedTexts = LyricifyParser.parseTranslation(trans, offset);
            if (!translatedTexts.isEmpty()) {
                while (translatedTexts.size() < lines.size()) {
                    translatedTexts.add("");
                }
                List<LyricsLine> translationLines = new ArrayList<>(lines.size());
                for (int i = 0; i < lines.size(); i++) {
                    LyricsLine original = lines.get(i);
                    String tText = translatedTexts.get(i);
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

        String formatType = isSyllable ? "lys" : "lyl";
        return FetchResult.searched(new Lyrics(
                lines,
                name(),
                true,
                null,
                translations,
                null,
                creditLines.isEmpty() ? null : creditLines,
                text,
                formatType,
                null),
                resolved.titles(), resolved.artists(), 0L, track);
    }

    private static HttpURLConnection openApi(String url) throws IOException {
        HttpURLConnection conn =
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
    private static Resolved resolveFromCreditsFm(String title, String artist) {
        HttpURLConnection connection = null;
        try {
            String url = "https://api.credits.fm/v1/resolve/track";
            LyricsRequests.throttle(lastRequestTime, REQUEST_THROTTLE_MS);
            connection = (HttpURLConnection) new java.net.URL(url).openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(15000);
            connection.setDoOutput(true);

            String body = new JSONObject()
                    .put("name", title)
                    .put("artist", artist)
                    .toString();
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(bodyBytes.length);
            try (OutputStream output = connection.getOutputStream()) {
                output.write(bodyBytes);
            }

            final int code = connection.getResponseCode();
            if (code == 200) {
                String responseBody = LyricsRequests.parseGzipString(connection);
                if (responseBody.isEmpty()) {
                    return null;
                }
                JSONObject response = new JSONObject(responseBody);
                String isrc = LyricsRequests.optString(response, "isrc");
                if (isrc == null || isrc.isEmpty()) {
                    return null;
                }
                List<String> titles = new ArrayList<>(2);
                String recordingTitle =
                        LyricsRequests.optString(response, "recording_title");
                if (recordingTitle != null) {
                    titles.add(recordingTitle);
                }
                String songTitle = LyricsRequests.optString(response, "song_title");
                if (songTitle != null && !titles.contains(songTitle)) {
                    titles.add(songTitle);
                }
                return new Resolved(isrc, titles,
                        LyricsRequests.stringList(response, "artist_names"));
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
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) ('a' + RNG.nextInt(26)));
        }
        return sb.toString();
    }
}
