/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5226
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.copytranscript;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.potoken.PoTokenManager;
import app.morphe.extension.youtube.patches.CaptionCookiesPatch;
import app.morphe.extension.youtube.patches.CaptionTracks;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Fetches caption tracks via Innertube player and parses timedtext JSON3 into line-level text.
 * Language choice follows the same idea as {@code PreferredCaptionLanguagePatch}:
 * active captions language, then preferred caption setting, then the app locale —
 * never "whatever track appears first in the JSON".
 */
public final class TranscriptCopyFetcher {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 15_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final Pattern BRACKETS_PATTERN = Pattern.compile("\\[[^]]*]");
    private static final Pattern PARENTHESES_PATTERN = Pattern.compile("\\([^)]*\\)");

    private TranscriptCopyFetcher() {
    }

    public record TranscriptLine(long startMs, String text) {
    }

    private record CaptionTrack(
            String url,
            String langCode,
            boolean isAsr,
            boolean isGemini,
            boolean isTranslatable
    ) {
    }

    /**
     * Fetches the transcript for {@code videoId} in the user's current language.
     * Must be called off the main thread.
     */
    public static List<TranscriptLine> fetch(String videoId) throws Exception {
        Utils.verifyOffMainThread();

        String poToken = null;
        try {
            String playerJson = fetchInnertubePlayer(videoId);
            if (playerJson != null && !playerJson.isEmpty()) {
                poToken = extractPoToken(playerJson);
                if (poToken == null || poToken.isEmpty()) {
                    try {
                        poToken = PoTokenManager.getPlayerPoToken(ClientType.ANDROID, videoId);
                    } catch (Exception ex) {
                        Logger.printDebug(() -> "Could not resolve a player PoToken", ex);
                    }
                }

                List<CaptionTrack> tracks = extractCaptionTracks(playerJson);
                CaptionTrack selected = selectTrack(tracks, playerJson, resolveDesiredLanguage());
                if (selected != null) {
                    final String lang = selected.langCode;
                    Logger.printDebug(() -> "Copy transcript using language: " + lang);
                    try {
                        List<TranscriptLine> lines = parseJson3(
                                fetchUrl(buildCaptionUrl(selected.url, poToken)));
                        if (!lines.isEmpty()) {
                            return lines;
                        }
                    } catch (Exception ex) {
                        Logger.printDebug(() -> "Caption track fetch failed", ex);
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Innertube player failed while copying transcript", ex);
        }

        return List.of();
    }

    public static String format(List<TranscriptLine> lines, boolean withTimestamps) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0, size = lines.size(); i < size; i++) {
            TranscriptLine line = lines.get(i);
            if (withTimestamps) {
                builder.append(formatTimestamp(line.startMs)).append(' ');
            }
            builder.append(line.text);
            if (i < size - 1) {
                builder.append('\n');
            }
        }
        return builder.toString();
    }

    private static String formatTimestamp(long startMs) {
        final long totalSec = Math.max(0, startMs / 1000);
        final long hours = totalSec / 3600;
        final long minutes = (totalSec % 3600) / 60;
        final long seconds = totalSec % 60;
        if (hours > 0) {
            return String.format(Locale.US, "[%d:%02d:%02d]", hours, minutes, seconds);
        }
        return String.format(Locale.US, "[%02d:%02d]", minutes, seconds);
    }

    /**
     * Same priority as Morphe caption preferences:
     * <ol>
     *   <li>Language currently selected in the player (when captions are on)</li>
     *   <li>Preferred caption language setting ({@code default} → app locale)</li>
     *   <li>App locale from {@link Requester#getAppLocale()}</li>
     * </ol>
     */
    private static String resolveDesiredLanguage() {
        String active = CaptionTracks.getSelectedInputLanguage();
        if (active != null && !active.isEmpty()) {
            return active;
        }

        String preferred = Settings.PREFERRED_CAPTION_LANGUAGE.get();
        if (preferred != null
                && !preferred.isEmpty()
                && !"off".equalsIgnoreCase(preferred)) {
            if ("default".equalsIgnoreCase(preferred)) {
                return appLanguage();
            }
            return preferred;
        }

        return appLanguage();
    }

    private static String appLanguage() {
        Locale locale = Requester.getAppLocale();
        if (locale == null) {
            locale = Locale.getDefault();
        }
        String tag = locale.toLanguageTag();
        if (!tag.isEmpty() && !"und".equalsIgnoreCase(tag)) {
            return tag;
        }
        String language = locale.getLanguage();
        return language == null || language.isEmpty() ? "en" : language;
    }

    /**
     * Pick a track for {@code desiredLang} without falling through to an unrelated first track.
     */
    @Nullable
    private static CaptionTrack selectTrack(
            List<CaptionTrack> tracks,
            String playerJson,
            String desiredLang
    ) {
        if (tracks.isEmpty()) {
            return null;
        }

        String desiredKey = languageKey(desiredLang);

        CaptionTrack manualMatch = null;
        CaptionTrack asrMatch = null;
        for (CaptionTrack track : tracks) {
            if (!languageMatches(track.langCode, desiredKey)) {
                continue;
            }
            if (!track.isAsr && manualMatch == null) {
                manualMatch = track;
            } else if (track.isAsr && asrMatch == null) {
                asrMatch = preferNonGemini(asrMatch, track);
            }
        }
        if (manualMatch != null) {
            return manualMatch;
        }
        if (asrMatch != null) {
            return asrMatch;
        }

        // Auto-translate from a source track when the desired language is only a target.
        CaptionTrack source = translationSource(tracks, playerJson);
        if (source != null && source.isTranslatable && desiredKey != null && !desiredKey.isEmpty()) {
            if (!languageMatches(source.langCode, desiredKey)) {
                String translatedUrl = source.url.replaceAll("&tlang=[^&]*", "")
                        + "&tlang=" + desiredKey;
                return new CaptionTrack(translatedUrl, desiredLang, false, false, false);
            }
        }

        // Fallbacks when the desired language does not exist: YouTube's default index,
        // then ASR (spoken language), then first manual, then first track.
        CaptionTrack byIndex = trackAtDefaultIndex(tracks, playerJson);
        if (byIndex != null) {
            return byIndex;
        }

        CaptionTrack asr = null;
        CaptionTrack manual = null;
        for (CaptionTrack track : tracks) {
            if (track.isAsr) {
                asr = preferNonGemini(asr, track);
            } else if (manual == null) {
                manual = track;
            }
        }
        if (asr != null) {
            return asr;
        }
        if (manual != null) {
            return manual;
        }
        return tracks.get(0);
    }

    @Nullable
    private static CaptionTrack preferNonGemini(@Nullable CaptionTrack current, CaptionTrack candidate) {
        if (current == null) {
            return candidate;
        }
        if (current.isGemini && !candidate.isGemini) {
            return candidate;
        }
        return current;
    }

    @Nullable
    private static CaptionTrack translationSource(List<CaptionTrack> tracks, String playerJson) {
        CaptionTrack byIndex = trackAtDefaultIndex(tracks, playerJson);
        if (byIndex != null && byIndex.isTranslatable) {
            return byIndex;
        }
        CaptionTrack asr = null;
        for (CaptionTrack track : tracks) {
            if (track.isTranslatable) {
                if (track.isAsr) {
                    asr = preferNonGemini(asr, track);
                } else {
                    return track;
                }
            }
        }
        return asr;
    }

    @Nullable
    private static CaptionTrack trackAtDefaultIndex(List<CaptionTrack> tracks, String playerJson) {
        int index = defaultCaptionsTrackIndex(playerJson);
        if (index >= 0 && index < tracks.size()) {
            return tracks.get(index);
        }
        return null;
    }

    private static int defaultCaptionsTrackIndex(String json) {
        for (String key : new String[]{
                "\"defaultCaptionsTrackIndex\":",
                "\"defaultCaptionTrackIndex\":",
                "\"defaultTranslationSourceTrackIndices\":["
        }) {
            int idx = json.indexOf(key);
            if (idx < 0) {
                continue;
            }
            idx += key.length();
            while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) {
                idx++;
            }
            int end = idx;
            while (end < json.length() && Character.isDigit(json.charAt(end))) {
                end++;
            }
            if (end > idx) {
                try {
                    return Integer.parseInt(json.substring(idx, end));
                } catch (NumberFormatException ignored) {
                    // try next key
                }
            }
        }
        return -1;
    }

    private static boolean languageMatches(String trackLang, String desiredKey) {
        if (trackLang == null || trackLang.isEmpty() || desiredKey == null || desiredKey.isEmpty()) {
            return false;
        }
        return languageKey(trackLang).equals(languageKey(desiredKey));
    }

    private static String languageKey(String languageCode) {
        if (languageCode == null || languageCode.isEmpty()) {
            return "";
        }
        String code = languageCode.toLowerCase(Locale.US).replace('_', '-');
        String primary = code.split("-", 2)[0];
        // Keep the same coarse keys PreferredCaptionLanguagePatch uses for matching.
        if ("zh".equals(primary)) {
            if (code.contains("-hant") || code.endsWith("-tw") || code.endsWith("-hk")) {
                return "zh-hant";
            }
            if (code.contains("-hans") || code.endsWith("-cn") || code.endsWith("-sg")) {
                return "zh-hans";
            }
        }
        if ("pt".equals(primary)) {
            return code.endsWith("-pt") ? "pt-pt" : "pt";
        }
        return primary;
    }

    @Nullable
    private static String fetchInnertubePlayer(String videoId) throws Exception {
        String body = "{\"context\":{\"client\":{\"clientName\":\"ANDROID\","
                + "\"clientVersion\":\"20.10.38\"}},"
                + "\"videoId\":\"" + videoId + "\"}";

        HttpURLConnection conn = Requester.openConnection(INNERTUBE_PLAYER_URL);
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent",
                    "com.google.android.youtube/20.10.38 (Linux; U; Android 11) gzip");
            conn.setRequestProperty("X-YouTube-Client-Name", "3");
            conn.setRequestProperty("X-YouTube-Client-Version", "20.10.38");
            applyCaptionCookies(conn);
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            final int code = conn.getResponseCode();
            if (code != 200) {
                throw new Exception("Innertube player HTTP " + code);
            }
            return Requester.parseString(conn);
        } finally {
            conn.disconnect();
        }
    }

    @Nullable
    private static String extractPoToken(String json) {
        int idx = json.indexOf("\"poToken\":\"");
        if (idx < 0) {
            return null;
        }
        idx += "\"poToken\":\"".length();
        final int end = json.indexOf('"', idx);
        return end < 0 ? null : json.substring(idx, end);
    }

    private static List<CaptionTrack> extractCaptionTracks(String json) {
        List<CaptionTrack> tracks = new ArrayList<>();
        final int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) {
            return tracks;
        }

        try {
            final int arrStart = json.indexOf('[', tracksIdx);
            final int arrEnd = arrStart < 0 ? -1 : endOfJsonArray(json, arrStart);
            if (arrEnd < 0) {
                return tracks;
            }

            JSONArray arr = new JSONArray(json.substring(arrStart, arrEnd + 1));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String baseUrl = obj.optString("baseUrl", "");
                if (baseUrl.isEmpty()) {
                    continue;
                }
                baseUrl = unescapeUrl(baseUrl);

                String langCode = obj.optString("languageCode", "");
                if (langCode.isEmpty()) {
                    langCode = extractLangFromUrl(baseUrl);
                }
                if (langCode.isEmpty()) {
                    continue;
                }

                final boolean isGemini = baseUrl.contains("variant=gemini");
                final boolean isAsr = "asr".equals(obj.optString("kind", ""))
                        || baseUrl.contains("kind=asr")
                        || isGemini;
                final boolean isTranslatable = obj.optBoolean("isTranslatable", false)
                        || baseUrl.contains("kind=asr");

                tracks.add(new CaptionTrack(baseUrl, langCode, isAsr, isGemini, isTranslatable));
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read caption tracks", ex);
        }
        return tracks;
    }

    private static String unescapeUrl(String baseUrl) {
        return baseUrl.replace("\\u0026", "&")
                .replace("\\u003d", "=")
                .replace("\\u003e", ">")
                .replace("\\u003c", "<");
    }

    private static int endOfJsonArray(String json, int arrStart) {
        int depth = 0;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String extractLangFromUrl(String url) {
        for (String prefix : new String[]{"&lang=", "?lang="}) {
            int idx = url.indexOf(prefix);
            if (idx >= 0) {
                idx += prefix.length();
                final int end = url.indexOf('&', idx);
                return end < 0 ? url.substring(idx) : url.substring(idx, end);
            }
        }
        return "";
    }

    private static String buildCaptionUrl(String baseUrl, @Nullable String poToken) {
        String url = baseUrl
                .replaceAll("&variant=gemini", "")
                .replaceAll("&fmt=[^&]*", "")
                + "&fmt=json3";
        if (poToken != null && !poToken.isEmpty() && !url.contains("pot=")) {
            url += "&pot=" + poToken;
        }
        return url;
    }

    private static List<TranscriptLine> parseJson3(String json) throws Exception {
        List<TranscriptLine> lines = new ArrayList<>();
        JSONObject root = new JSONObject(json);
        JSONArray events = root.optJSONArray("events");
        if (events == null) {
            return lines;
        }

        for (int i = 0, length = events.length(); i < length; i++) {
            JSONObject event = events.getJSONObject(i);
            if (event.optInt("aAppend", 0) == 1) {
                continue;
            }
            JSONArray segs = event.optJSONArray("segs");
            if (segs == null) {
                continue;
            }

            StringBuilder textBuilder = new StringBuilder();
            for (int j = 0, segsLength = segs.length(); j < segsLength; j++) {
                textBuilder.append(segs.getJSONObject(j).optString("utf8", ""));
            }

            String text = textBuilder.toString()
                    .replace('\n', ' ')
                    .trim();
            if (text.isEmpty()
                    || BRACKETS_PATTERN.matcher(text).matches()
                    || PARENTHESES_PATTERN.matcher(text).matches()) {
                continue;
            }

            lines.add(new TranscriptLine(event.optLong("tStartMs", 0), text));
        }
        return lines;
    }

    private static String fetchUrl(String urlStr) throws Exception {
        HttpURLConnection conn = Requester.openConnection(urlStr);
        try {
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", CaptionCookiesPatch.getUserAgent());
            applyCaptionCookies(conn);

            Map<String, String> authHeaders = AuthUtils.getRequestHeader();
            for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
                if (!entry.getValue().isEmpty()) {
                    conn.setRequestProperty(entry.getKey(), entry.getValue());
                }
            }

            final int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                throw new Exception("HTTP response code: " + responseCode);
            }
            return Requester.parseString(conn);
        } finally {
            conn.disconnect();
        }
    }

    private static void applyCaptionCookies(HttpURLConnection conn) {
        if (!Settings.SET_CAPTION_COOKIES.get()) {
            return;
        }
        String cookies = CaptionCookiesPatch.getCookies();
        if (!cookies.isEmpty()) {
            conn.setRequestProperty("Cookie", cookies);
        }
        conn.setRequestProperty("User-Agent", CaptionCookiesPatch.getUserAgent());
    }
}
