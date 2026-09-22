/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;

public final class YTMusicProvider implements LyricsProvider {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;

    private static final String INNERTUBE_KEY =
            "AIzaSyC9XL3ZjWddXya6X74dJoCTL-WEYFDNX30";
    private static final String NEXT_URL =
            "https://music.youtube.com/youtubei/v1/next?key=" + INNERTUBE_KEY + "&alt=json";
    private static final String BROWSE_URL =
            "https://music.youtube.com/youtubei/v1/browse?key=" + INNERTUBE_KEY + "&alt=json";

    private static final String WEB_REMIX_VERSION = "1.20260914.01.00";
    private static final String ANDROID_MUSIC_VERSION = "7.21.50";
    private static final String INNERTUBE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    @Override
    public String name() {
        return "YTMusic";
    }

    @Nullable
    @Override
    public Lyrics fetch(TrackInfo track) throws Exception {
        final String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty()) {
            return null;
        }

        final String browseId = fetchLyricsBrowseId(videoId);
        if (browseId == null) {
            return null;
        }

        final Lyrics plainResult = fetchLyrics(browseId, false);
        if (plainResult != null && !plainResult.isEmpty()) {
            if (plainResult.synced()) {
                return plainResult;
            }
            final Lyrics timedResult = fetchLyrics(browseId, true);
            if (timedResult != null && timedResult.synced()) {
                return timedResult;
            }
            return plainResult;
        }

        final Lyrics androidResult = fetchLyrics(browseId, true);
        if (androidResult != null && !androidResult.isEmpty()) {
            return androidResult;
        }

        return null;
    }

    @Nullable
    private static String fetchLyricsBrowseId(String videoId) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", buildContext("WEB_REMIX", WEB_REMIX_VERSION));
            body.put("videoId", videoId);
            body.put("playlistId", "RDAMVM" + videoId);

            final HttpURLConnection conn = postInnertube(NEXT_URL, body.toString(),
                    WEB_REMIX_VERSION, true);
            try {
                final int code = conn.getResponseCode();
                if (code != 200) {
                    LyricsRequests.logFailure("YTMusic", conn);
                    return null;
                }
                JSONObject response = Requester.parseJSONObject(conn);
                return extractLyricsBrowseId(response);
            } finally {
                conn.disconnect();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch YTMusic browse ID", ex);
            return null;
        }
    }

    @Nullable
    private static String extractLyricsBrowseId(JSONObject response) {
        try {
            final JSONArray tabs = response
                    .getJSONObject("contents")
                    .getJSONObject("singleColumnMusicWatchNextResultsRenderer")
                    .getJSONObject("tabbedRenderer")
                    .getJSONObject("watchNextTabbedResultsRenderer")
                    .getJSONArray("tabs");
            for (int i = 0; i < tabs.length(); i++) {
                JSONObject browseEndpoint = LyricsRequests.optPath(tabs.optJSONObject(i),
                        "tabRenderer", "endpoint", "browseEndpoint");
                if (browseEndpoint == null) {
                    continue;
                }
                JSONObject musicConfig = LyricsRequests.optPath(browseEndpoint,
                        "browseEndpointContextSupportedConfigs",
                        "browseEndpointContextMusicConfig");
                if (musicConfig == null) {
                    continue;
                }
                if ("MUSIC_PAGE_TYPE_TRACK_LYRICS".equals(musicConfig.optString("pageType", ""))) {
                    return LyricsRequests.optString(browseEndpoint, "browseId");
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not extract YTMusic lyrics browse ID", ex);
        }
        return null;
    }

    @Nullable
    private Lyrics fetchLyrics(String browseId, boolean useAndroidClient) {
        try {
            final String clientVersion = useAndroidClient
                    ? ANDROID_MUSIC_VERSION : WEB_REMIX_VERSION;
            JSONObject body = new JSONObject();
            body.put("context", buildContext(
                    useAndroidClient ? "ANDROID_MUSIC" : "WEB_REMIX", clientVersion));
            body.put("browseId", browseId);

            final HttpURLConnection conn = postInnertube(BROWSE_URL, body.toString(),
                    clientVersion, false);
            try {
                final int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
                    return null;
                }
                JSONObject response = Requester.parseJSONObject(conn);
                return parseLyricsResponse(response);
            } finally {
                conn.disconnect();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch YTMusic lyrics", ex);
            return null;
        }
    }

    @Nullable
    private static Lyrics parseLyricsResponse(JSONObject response) {
        JSONObject lyricsData = navigateTimedLyricsModel(response);
        final String viaProvider = extractSourceProvider(lyricsData, response);
        final String sourceTag = viaProvider != null
                ? "YouTube Music (via " + viaProvider + ")"
                : "YouTube Music";

        if (lyricsData != null) {
            final JSONArray timedArray = lyricsData.optJSONArray("timedLyricsData");
            if (timedArray != null && timedArray.length() > 0) {
                final List<LyricsLine> lines = parseTimedLyrics(timedArray);
                if (!lines.isEmpty()) {
                    boolean hasTimestamp = false;
                    for (LyricsLine line : lines) {
                        if (line.startTimeMs() != LyricsLine.NO_TIME) {
                            hasTimestamp = true;
                            break;
                        }
                    }
                    if (hasTimestamp) {
                        return new Lyrics(lines, sourceTag, true, null, null, null,
                                null, timedArray.toString(), "ytm.json", null);
                    }
                    final StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < lines.size(); i++) {
                        if (i > 0) sb.append('\n');
                        sb.append(lines.get(i).text());
                    }
                    final String plainText = sb.toString();
                    return new Lyrics(LyricsRequests.parsePlainTextLines(plainText),
                            sourceTag, false, null, null, null,
                            null, plainText, "txt", null);
                }
            }

            final String instrumental = lyricsData.optString("lyrics", "");
            if (instrumental.contains("[Instrumental]") || instrumental.contains("♪")) {
                return Lyrics.NOT_FOUND;
            }
        }

        final String plainText = extractPlainTextLyrics(response);
        if (plainText != null && !plainText.isEmpty()) {
            final List<LyricsLine> lines = LyricsRequests.parsePlainTextLines(plainText);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, sourceTag, false, null, null, null,
                        null, plainText, "txt", null);
            }
        }

        return null;
    }

    @Nullable
    private static String extractSourceProvider(@Nullable JSONObject lyricsData,
                                                JSONObject response) {
        if (lyricsData != null) {
            final String source = lyricsData.optString("sourceMessage", "");
            final String parsed = parseSourceProvider(source);
            if (parsed != null) {
                return parsed;
            }
        }
        try {
            JSONObject footer = response
                    .getJSONObject("contents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents")
                    .getJSONObject(0)
                    .getJSONObject("musicDescriptionShelfRenderer")
                    .getJSONObject("footer");
            final String source = footer.getJSONArray("runs")
                    .getJSONObject(0).optString("text", "");
            return parseSourceProvider(source);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not extract YTMusic source provider from description", ex);
        }
        return null;
    }

    @Nullable
    private static String parseSourceProvider(String sourceMessage) {
        if (sourceMessage.startsWith("Source: ")) {
            final String provider = sourceMessage.substring(8).trim();
            return provider.isEmpty() ? null : provider;
        }
        return null;
    }

    @Nullable
    private static JSONObject navigateTimedLyricsModel(JSONObject response) {
        try {
            return response
                    .getJSONObject("contents")
                    .getJSONObject("elementRenderer")
                    .getJSONObject("newElement")
                    .getJSONObject("type")
                    .getJSONObject("componentType")
                    .getJSONObject("model")
                    .getJSONObject("timedLyricsModel")
                    .getJSONObject("lyricsData");
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not navigate YTMusic timed lyrics model", ex);
        }
        return null;
    }

    @Nullable
    private static String extractPlainTextLyrics(JSONObject response) {
        try {
            final JSONArray sectionContents = response
                    .getJSONObject("contents")
                    .getJSONObject("sectionListRenderer")
                    .getJSONArray("contents");
            for (int i = 0; i < sectionContents.length(); i++) {
                JSONObject shelf = sectionContents.optJSONObject(i);
                if (shelf == null) {
                    continue;
                }
                JSONObject renderer = shelf.optJSONObject("musicDescriptionShelfRenderer");
                if (renderer == null) {
                    continue;
                }
                JSONObject description = renderer.optJSONObject("description");
                if (description == null) {
                    continue;
                }
                final JSONArray runs = description.optJSONArray("runs");
                if (runs != null && runs.length() > 0) {
                    final StringBuilder sb = new StringBuilder();
                    for (int r = 0; r < runs.length(); r++) {
                        JSONObject run = runs.optJSONObject(r);
                        if (run != null) {
                            sb.append(run.optString("text", ""));
                        }
                    }
                    return sb.toString();
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not extract YTMusic plain text lyrics", ex);
        }
        return null;
    }

    private static List<LyricsLine> parseTimedLyrics(JSONArray timedArray) {
        final List<LyricsLine> lines = new ArrayList<>();
        for (int i = 0; i < timedArray.length(); i++) {
            JSONObject entry = timedArray.optJSONObject(i);
            if (entry == null) {
                continue;
            }
            final String text = entry.optString("lyricLine", "").trim();
            if (text.isEmpty()) {
                continue;
            }
            JSONObject cueRange = entry.optJSONObject("cueRange");
            final long startMs = parseTimestamp(cueRange, "startTimeMilliseconds");
            final long endMs = parseTimestamp(cueRange, "endTimeMilliseconds");
            lines.add(new LyricsLine(startMs, endMs, text, List.of()));
        }
        return lines;
    }

    private static long parseTimestamp(JSONObject cueRange, String key) {
        if (cueRange == null) {
            return LyricsLine.NO_TIME;
        }
        final String raw = cueRange.optString(key, "");
        if (raw.isEmpty()) {
            return LyricsLine.NO_TIME;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException ex) {
            Logger.printDebug(() -> "Could not parse YTMusic timestamp: " + raw, ex);
            return LyricsLine.NO_TIME;
        }
    }

    private static JSONObject buildContext(String clientName, String clientVersion) throws Exception {
        JSONObject client = new JSONObject();
        client.put("clientName", clientName);
        client.put("clientVersion", clientVersion);
        client.put("hl", "en");

        JSONObject user = new JSONObject();

        JSONObject context = new JSONObject();
        context.put("client", client);
        context.put("user", user);
        return context;
    }

    private static HttpURLConnection postInnertube(String url, String jsonBody,
                                                    String clientVersion,
                                                    boolean sendAuth) throws Exception {
        final HttpURLConnection conn = Requester.openConnection(url);
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", INNERTUBE_USER_AGENT);
        conn.setRequestProperty("X-YouTube-Client-Version", clientVersion);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Cookie", "SOCS=CAI");
        conn.setRequestProperty("Origin", "https://music.youtube.com");
        conn.setDoOutput(true);

        if (sendAuth) {
            for (Map.Entry<String, String> entry :
                    AuthUtils.getRequestHeader().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }
        }

        final byte[] bytes = jsonBody.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        conn.setFixedLengthStreamingMode(bytes.length);
        try (java.io.OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        return conn;
    }
}
