package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;

public final class SubtitlesFetcher {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String TIMEDTEXT_URL =
            "https://www.youtube.com/api/timedtext";
    private static final String USER_AGENT =
            "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip";

    /** Languages tried by the auth-free timedtext fallback, most common first. */
    private static final List<String> TIMEDTEXT_LANGUAGES = Arrays.asList(
            "en", "en-US", "en-GB", "zh-CN", "zh-TW", "ja", "ko",
            "es", "fr", "de", "pt", "ru", "ar", "hi", "it");

    private static final Pattern BRACKETS_PATTERN = Pattern.compile("\\[[^]]*]");
    private static final Pattern PARENTHESES_PATTERN = Pattern.compile("\\([^)]*\\)");

    private SubtitlesFetcher() {
    }

    public static final class SubtitlesOutcome {
        static final SubtitlesOutcome ALLOW_PROVIDERS = new SubtitlesOutcome(null, false);
        static final SubtitlesOutcome SUPPRESS_PROVIDERS = new SubtitlesOutcome(null, true);

        public final @Nullable Lyrics lyrics;
        public final boolean suppressProviders;

        private SubtitlesOutcome(@Nullable Lyrics lyrics, boolean suppressProviders) {
            this.lyrics = lyrics;
            this.suppressProviders = suppressProviders;
        }

        static SubtitlesOutcome subtitles(Lyrics lyrics) {
            return new SubtitlesOutcome(lyrics, false);
        }
    }

    public static SubtitlesOutcome fetch() {
        final String videoId = readVideoIdWithRetry();
        if (videoId == null || videoId.isEmpty()) {
            Logger.printDebug(() -> "Subtitles: no video id available, skipping");
            return SubtitlesOutcome.ALLOW_PROVIDERS;
        }
        Logger.printDebug(() -> "Subtitles: video id=" + videoId);

        try {
            final CaptionListResult captions = findCaptionList(videoId);
            if (captions == null || !captions.structurePresent) {
                // Innertube unavailable or no captions structure at all (typical for songs):
                // try the auth-free timedtext fallback, then let providers handle it.
                return tryTimedtext(videoId);
            }
            if (!captions.urls.isEmpty()) {
                for (String url : captions.urls) {
                    try {
                        final String json = fetchCaptionUrl(
                                url.replaceAll("&fmt=[^&]*", "") + "&fmt=json3");
                        final List<LyricsLine> lines = parseJson3(json);
                        if (!lines.isEmpty()) {
                            Logger.printDebug(() -> "Subtitles: parsed " + lines.size()
                                    + " lines from innertube");
                            return SubtitlesOutcome.subtitles(new Lyrics(lines, Lyrics.CAPTIONS_PROVIDER, true));
                        }
                    } catch (Exception ex) {
                        Logger.printDebug(() -> "Subtitles: caption url fetch failed", ex);
                    }
                }
                Logger.printDebug(() -> "Subtitles: caption tracks present but none parsed");
            } else {
                Logger.printDebug(() -> "Subtitles: captions structure present but no tracks");
            }
            final Lyrics timed = fetchViaTimedtext(videoId);
            if (timed != null && !timed.isEmpty()) {
                return SubtitlesOutcome.subtitles(timed);
            }
            Logger.printDebug(() -> "Subtitles: captioned context with no usable captions, falling back to providers");
            return SubtitlesOutcome.ALLOW_PROVIDERS;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Subtitles fetch failed", ex);
            return SubtitlesOutcome.ALLOW_PROVIDERS;
        }
    }

    private static SubtitlesOutcome tryTimedtext(String videoId) {
        final Lyrics timed = fetchViaTimedtext(videoId);
        if (timed != null && !timed.isEmpty()) {
            return SubtitlesOutcome.subtitles(timed);
        }
        Logger.printDebug(() -> "Subtitles: timedtext fallback found nothing");
        return SubtitlesOutcome.ALLOW_PROVIDERS;
    }

    @Nullable
    private static String readVideoIdWithRetry() {
        String videoId = VideoInformation.getVideoId();
        if (videoId != null && !videoId.isEmpty()) {
            return videoId;
        }
        for (int i = 0; i < 5; i++) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            videoId = VideoInformation.getVideoId();
            if (videoId != null && !videoId.isEmpty()) {
                final int attempt = i;
                Logger.printDebug(() -> "Subtitles: video id available after retry " + attempt);
                return videoId;
            }
        }
        return null;
    }

    private static final class CaptionListResult {
        final boolean structurePresent;
        final List<String> urls;

        CaptionListResult(boolean structurePresent, List<String> urls) {
            this.structurePresent = structurePresent;
            this.urls = urls;
        }
    }

    @Nullable
    private static CaptionListResult findCaptionList(String videoId) {
        final String json = fetchInnertubePlayer(videoId);
        if (json == null) {
            return null;
        }
        final int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) {
            Logger.printDebug(() -> "Subtitles: innertube response has no captionTracks field");
            return new CaptionListResult(false, new ArrayList<>());
        }
        final List<String> urls = extractCaptionUrls(json, tracksIdx);
        Logger.printDebug(() -> "Subtitles: innertube captionTracks count=" + urls.size());
        return new CaptionListResult(true, urls);
    }

    @Nullable
    private static String fetchInnertubePlayer(String videoId) {
        String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\","
                + "\"clientVersion\":\"20.10.38\"}},"
                + "\"videoId\":\"" + videoId + "\"}";

        HttpURLConnection conn = null;
        try {
            conn = Requester.openConnection(INNERTUBE_PLAYER_URL);
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", USER_AGENT);
            conn.setRequestProperty("X-YouTube-Client-Name", "3");
            conn.setRequestProperty("X-YouTube-Client-Version", "20.10.38");
            conn.setDoOutput(true);

            for (Map.Entry<String, String> entry : AuthUtils.getRequestHeader().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            final int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                Logger.printDebug(() -> "Subtitles: innertube player HTTP " + responseCode);
                return null;
            }
            return Requester.parseString(conn);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Subtitles: innertube player request failed", ex);
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static List<String> extractCaptionUrls(String json, int tracksIdx) {
        List<String> urls = new ArrayList<>();
        int searchFrom = tracksIdx;
        String firstUrl = null;
        String firstNonGemini = null;

        while (true) {
            int baseUrlIdx = json.indexOf("\"baseUrl\":\"", searchFrom);
            if (baseUrlIdx < 0 || baseUrlIdx > tracksIdx + 50_000) {
                break;
            }
            baseUrlIdx += "\"baseUrl\":\"".length();

            final int endIdx = json.indexOf('"', baseUrlIdx);
            if (endIdx < 0) {
                break;
            }

            String url = json.substring(baseUrlIdx, endIdx)
                    .replace("\\u0026", "&")
                    .replace("\\u003d", "=")
                    .replace("\\u003e", ">")
                    .replace("\\u003c", "<");

            if (firstUrl == null) {
                firstUrl = url;
            }
            if (firstNonGemini == null && !url.contains("variant=gemini")) {
                firstNonGemini = url;
            }
            searchFrom = endIdx + 1;
        }

        // Prefer a non-gemini (real) caption track, then the first one.
        final String chosen = firstNonGemini != null ? firstNonGemini : firstUrl;
        if (chosen != null) {
            urls.add(chosen);
        }
        return urls;
    }

    @Nullable
    private static Lyrics fetchViaTimedtext(String videoId) {
        for (String lang : TIMEDTEXT_LANGUAGES) {
            try {
                final String url = TIMEDTEXT_URL + "?lang=" + lang + "&v=" + videoId + "&fmt=json3";
                final String json = fetchCaptionUrl(url);
                final List<LyricsLine> lines = parseJson3(json);
                if (!lines.isEmpty()) {
                    Logger.printDebug(() -> "Subtitles: timedtext fallback got " + lines.size()
                            + " lines (lang=" + lang + ")");
                    return new Lyrics(lines, Lyrics.CAPTIONS_PROVIDER, true);
                }
            } catch (Exception ex) {
                // Wrong language or unavailable; try the next candidate.
            }
        }
        return null;
    }

    private static List<LyricsLine> parseJson3(String json) throws Exception {
        final JSONObject root = new JSONObject(json);
        if (!root.has("events")) {
            return new ArrayList<>();
        }

        final JSONArray events = root.getJSONArray("events");
        final List<LyricsLine> lines = new ArrayList<>();

        for (int i = 0; i < events.length(); i++) {
            final JSONObject event = events.getJSONObject(i);
            if (!event.has("segs")) {
                continue;
            }

            String text = "";
            long startTimeMs = event.optLong("tStartMs", 0);

            final JSONArray segments = event.getJSONArray("segs");
            for (int j = 0; j < segments.length(); j++) {
                final JSONObject seg = segments.getJSONObject(j);
                if (seg.has("utf8")) {
                    text += seg.getString("utf8");
                }
            }

            final String trimmed = text.trim();
            if (trimmed.isEmpty()
                    || BRACKETS_PATTERN.matcher(trimmed).matches()
                    || PARENTHESES_PATTERN.matcher(trimmed).matches()) {
                continue;
            }

            lines.add(new LyricsLine(startTimeMs, trimmed));
        }

        return lines;
    }

    private static String fetchCaptionUrl(String urlStr) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = Requester.openConnection(urlStr);
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", USER_AGENT);

            for (Map.Entry<String, String> entry : AuthUtils.getRequestHeader().entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            if (conn.getResponseCode() != 200) {
                throw new Exception("HTTP response code: " + conn.getResponseCode());
            }
            return Requester.parseString(conn);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
