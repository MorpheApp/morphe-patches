/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.Lyrics;
import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.TrackInfo;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.potoken.PoTokenManager;

public final class CaptionsFetcher {

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 8_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String TIMEDTEXT_URL =
            "https://www.youtube.com/api/timedtext";

    private static final String CAPTION_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36";

    private static final String SW_COOKIE_URL = "https://www.youtube.com/sw.js";
    private static final List<String> COOKIE_KEYS = Arrays.asList(
            "YSC", "VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "__Secure-ROLLOUT_TOKEN"
    );
    private static volatile String cachedCookies = null;
    private static volatile long cachedCookiesTime = 0;
    private static final long COOKIE_CACHE_DURATION_MS = 30L * 24 * 60 * 60 * 1000;

    private static final Pattern BRACKETS_PATTERN = Pattern.compile("\\[[^]]*]");
    private static final Pattern PARENTHESES_PATTERN = Pattern.compile("\\([^)]*\\)");

    private static volatile String lastFetchedVideoId = null;

    private CaptionsFetcher() {
    }

    private record CaptionTrack(String url, String langCode, String vssId, boolean isAsr,
                                boolean isTranslatable, boolean isTranslation, boolean hasTlang,
                                String displayName) {

        @Override
        @NonNull
        public String toString() {
            return langCode + (isAsr ? " (ASR)" : " (manual)")
                    + " vssId=" + vssId
                    + (isTranslation ? " [trans]" : " [orig]")
                    + (hasTlang ? " [tlang]" : "")
                    + " t=" + isTranslatable
                    + " '" + displayName + "'";
        }
    }

    public static final class CaptionsOutcome {
        static final CaptionsOutcome ALLOW_PROVIDERS = new CaptionsOutcome(null, null, false, null, null);

        public final @Nullable Lyrics lyrics;
        public final @Nullable Lyrics translationLyrics;
        public final boolean suppressProviders;
        public final @Nullable TrackInfo innertubeTrack;
        public final @Nullable String errorReason;

        private CaptionsOutcome(@Nullable Lyrics lyrics, @Nullable Lyrics translationLyrics,
                                 boolean suppressProviders, @Nullable TrackInfo innertubeTrack,
                                 @Nullable String errorReason) {
            this.lyrics = lyrics;
            this.translationLyrics = translationLyrics;
            this.suppressProviders = suppressProviders;
            this.innertubeTrack = innertubeTrack;
            this.errorReason = errorReason;
        }

        static CaptionsOutcome captions(Lyrics lyrics, @Nullable TrackInfo innertubeTrack) {
            return new CaptionsOutcome(lyrics, null, false, innertubeTrack, null);
        }

        static CaptionsOutcome captions(Lyrics lyrics, @Nullable Lyrics translationLyrics,
                                         @Nullable TrackInfo innertubeTrack) {
            return new CaptionsOutcome(lyrics, translationLyrics, false, innertubeTrack, null);
        }

        static CaptionsOutcome withError(@Nullable String reason, @Nullable TrackInfo innertubeTrack) {
            return new CaptionsOutcome(null, null, false, innertubeTrack, reason);
        }
    }

    public static final class CaptionsProvider implements LyricsProvider {
        @Override
        public String name() {
            return "Captions";
        }

        @Override
        @Nullable
        public Lyrics fetch(app.morphe.extension.music.patches.lyrics.TrackInfo track) throws Exception {
            CaptionsOutcome outcome = CaptionsFetcher.fetch();

            if (outcome.errorReason != null) {
                return null;
            }

            if (outcome.lyrics == null || outcome.lyrics.isEmpty()) {
                return null;
            }

            Lyrics result = outcome.lyrics;
            if (outcome.translationLyrics != null && !outcome.translationLyrics.isEmpty()) {
                String langTag = Locale.getDefault().toLanguageTag();
                Map<String, List<LyricsLine>> translations = new java.util.HashMap<>();
                translations.put(langTag, outcome.translationLyrics.lines());
                result = new Lyrics(result.lines(), result.providerName(), result.synced(),
                        result.romanization(), translations,
                        result.romanizations(), result.songwriters(),
                        result.rawFormat(), result.formatType(),
                        result.sourceUrl());
            }

            return result;
        }
    }

    public static CaptionsOutcome fetch() {
        String videoId = readVideoIdWithRetry();
        if (videoId == null || videoId.isEmpty()) {
            return CaptionsOutcome.ALLOW_PROVIDERS;
        }

        try {
            CaptionListResult captions = findCaptionList(videoId);
            if (captions == null) {
                return CaptionsOutcome.ALLOW_PROVIDERS;
            }

            if (captions.errorReason() != null) {
                return CaptionsOutcome.withError(captions.errorReason(), captions.innertubeTrack());
            }

            if (!captions.structurePresent()) {
                return tryTimedtext(videoId, captions.poToken(), captions.innertubeTrack());
            }

            if (!captions.tracks().isEmpty()) {
                CaptionTrack primaryTrack = selectPrimaryTrack(captions.tracks(),
                        captions.sourceLangCode());
                if (primaryTrack != null) {
                    Lyrics primaryLyrics = fetchTrackLyrics(primaryTrack, captions.poToken());
                    if (primaryLyrics != null && !primaryLyrics.isEmpty()) {
                        CaptionTrack translationTrack =
                                selectTranslationTrack(captions.tracks(), primaryTrack.langCode(),
                                        captions.sourceLangCode());
                        Lyrics translationLyrics = null;
                        if (translationTrack != null) {
                            Lyrics tl = fetchTrackLyrics(translationTrack, captions.poToken());
                            if (tl != null && !tl.isEmpty()) {
                                translationLyrics = tl;
                            }
                        }
                        lastFetchedVideoId = videoId;
                        return CaptionsOutcome.captions(primaryLyrics, translationLyrics,
                                captions.innertubeTrack());
                    }
                }
            }

            Lyrics timed = fetchViaTimedtext(videoId, captions.poToken(), null);
            if (timed != null && !timed.isEmpty()) {
                lastFetchedVideoId = videoId;
                return CaptionsOutcome.captions(timed, captions.innertubeTrack());
            }
            return allowProviders(captions.innertubeTrack());
        } catch (Exception ex) {
            return CaptionsOutcome.ALLOW_PROVIDERS;
        }
    }

    @Nullable
    private static Lyrics fetchTrackLyrics(CaptionTrack track, @Nullable String poToken) {
        try {
            String url = buildCaptionUrl(track.url(), poToken);
            String json = fetchCaptionUrl(url);
            List<LyricsLine> lines = parseJson3(json);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, Lyrics.CAPTIONS_PROVIDER, true, null, null, null, null, json, "json3", null);
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch captions", ex);
        }
        return null;
    }

    private static String buildCaptionUrl(String baseUrl, @Nullable String poToken) {
        String url = baseUrl.replaceAll("&fmt=[^&]*", "") + "&fmt=json3";
        if (poToken != null && !poToken.isEmpty()) {
            url += "&pot=" + poToken;
        }
        return url;
    }

    private static CaptionsOutcome tryTimedtext(String videoId, @Nullable String poToken,
                                                 @Nullable TrackInfo innertubeTrack) {
        Lyrics timed = fetchViaTimedtext(videoId, poToken, null);
        if (timed != null && !timed.isEmpty()) {
            lastFetchedVideoId = videoId;
            return CaptionsOutcome.captions(timed, innertubeTrack);
        }
        return allowProviders(innertubeTrack);
    }

    private static CaptionsOutcome allowProviders(@Nullable TrackInfo innertubeTrack) {
        return new CaptionsOutcome(null, null, false, innertubeTrack, null);
    }

    @Nullable
    private static String readVideoIdWithRetry() {
        String videoId = VideoInformation.getVideoId();
        String previousId = lastFetchedVideoId;

        if (!videoId.isEmpty()) {
            if (!videoId.equals(previousId)) {
                return videoId;
            }
            for (int i = 0; i < 5; i++) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    break;
                }
                videoId = VideoInformation.getVideoId();
                if (!videoId.isEmpty() && !videoId.equals(previousId)) {
                    return videoId;
                }
            }
            if (!videoId.isEmpty()) {
                return videoId;
            }
        }

        for (int i = 0; i < 3; i++) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            videoId = VideoInformation.getVideoId();
            if (!videoId.isEmpty()) {
                return videoId;
            }
        }
        return null;
    }

    private record CaptionListResult(boolean structurePresent, List<CaptionTrack> tracks,
                                     @Nullable String poToken, @Nullable TrackInfo innertubeTrack,
                                     @Nullable String sourceLangCode, @Nullable String videoLangCode,
                                     @Nullable String errorReason) {
    }

    @Nullable
    private static CaptionListResult findCaptionList(String videoId) {
        String json = fetchInnertubePlayer(videoId);
        if (json == null) {
            return null;
        }
        TrackInfo innertubeTrack = extractVideoDetails(json);
        String videoLangCode = extractVideoLanguageCode(json);

        String errorReason = extractPlayabilityError(json);
        if (errorReason != null) {
            return new CaptionListResult(false, new ArrayList<>(), null, innertubeTrack,
                    null, videoLangCode, errorReason);
        }

        String resolvedPoToken = extractPoToken(json);
        if (resolvedPoToken == null || resolvedPoToken.isEmpty()) {
            try {
                resolvedPoToken = PoTokenManager.getPlayerPoToken(ClientType.ANDROID, videoId);
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not resolve a player PoToken", ex);
            }
        }
        String poToken = resolvedPoToken;
        final int tracksIdx = json.indexOf("\"captionTracks\":[");
        if (tracksIdx < 0) {
            return new CaptionListResult(false, new ArrayList<>(), poToken, innertubeTrack,
                    null, videoLangCode, null);
        }
        List<CaptionTrack> tracks = extractCaptionTracks(json, tracksIdx);
        Set<String> translationLangs = extractTranslationLanguages(json);
        String microLang = extractMicroformatLanguage(json);
        String audioTracksLang = extractAudioTracksLanguage(json, tracks);
        String sourceLangCode = determineSourceLanguage(tracks, videoLangCode,
                microLang, audioTracksLang, translationLangs);

        return new CaptionListResult(true, tracks, poToken, innertubeTrack,
                sourceLangCode, videoLangCode, null);
    }

    private static Set<String> extractTranslationLanguages(String json) {
        Set<String> langs = new java.util.HashSet<>();
        int idx = json.indexOf("\"translationLanguages\":[");
        if (idx < 0) {
            return langs;
        }
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) return langs;
        int depth = 0;
        int arrEnd = -1;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { arrEnd = i; break; }
            }
        }
        if (arrEnd < 0) return langs;
        try {
            JSONArray arr = new JSONArray(json.substring(arrStart, arrEnd + 1));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                String langCode = obj.optString("languageCode", "");
                if (!langCode.isEmpty()) {
                    langs.add(langCode);
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the caption languages", ex);
        }
        return langs;
    }

    @Nullable
    private static String determineSourceLanguage(List<CaptionTrack> tracks,
                                                   @Nullable String videoLangCode,
                                                   @Nullable String microLang,
                                                   @Nullable String audioTracksLang,
                                                   Set<String> translationLangs) {
        for (CaptionTrack track : tracks) {
            if (track.isAsr()) {
                return track.langCode();
            }
        }
        if (videoLangCode != null && !videoLangCode.isEmpty()) {
            return videoLangCode;
        }
        if (microLang != null && !microLang.isEmpty()) {
            for (CaptionTrack track : tracks) {
                if (!track.isAsr() && track.langCode().equals(microLang)) {
                    return microLang;
                }
            }
        }
        for (CaptionTrack track : tracks) {
            if (!track.isAsr() && !track.isTranslation()) {
                return track.langCode();
            }
        }
        for (CaptionTrack track : tracks) {
            if (!track.isAsr() && !track.hasTlang()) {
                return track.langCode();
            }
        }
        if (audioTracksLang != null && !audioTracksLang.isEmpty()) {
            return audioTracksLang;
        }
        if (translationLangs != null && !translationLangs.isEmpty()) {
            for (CaptionTrack track : tracks) {
                if (!track.isAsr() && !translationLangs.contains(track.langCode())) {
                    return track.langCode();
                }
            }
        }
        List<CaptionTrack> translatable = new ArrayList<>();
        for (CaptionTrack track : tracks) {
            if (!track.isAsr() && track.isTranslatable()) {
                translatable.add(track);
            }
        }
        if (translatable.size() == 1) {
            return translatable.get(0).langCode;
        }
        return null;
    }

    @Nullable
    private static String extractVideoLanguageCode(String json) {
        final int vdIdx = json.indexOf("\"videoDetails\":{");
        if (vdIdx < 0) {
            return null;
        }
        final int vdStart = vdIdx + "\"videoDetails\":".length();

        String lang = extractJsonString(json, vdStart, "languageCode");
        if (lang != null && !lang.isEmpty()) {
            return lang;
        }

        lang = extractJsonString(json, vdStart, "primaryLanguage");
        if (lang != null && !lang.isEmpty()) {
            return lang;
        }

        lang = extractJsonString(json, vdStart, "language");
        if (lang != null && !lang.isEmpty()) {
            return lang;
        }

        return null;
    }

    @Nullable
    private static String extractMicroformatLanguage(String json) {
        final int mfIdx = json.indexOf("\"playerMicroformatRenderer\":{");
        if (mfIdx < 0) {
            return null;
        }
        final int mfStart = mfIdx + "\"playerMicroformatRenderer\":".length();
        String lang = extractJsonString(json, mfStart, "language");
        if (lang != null && !lang.isEmpty()) {
            return lang;
        }
        lang = extractJsonString(json, mfStart, "lang");
        if (lang != null && !lang.isEmpty()) {
            return lang;
        }
        return null;
    }

    @Nullable
    private static String extractAudioTracksLanguage(String json,
                                                     List<CaptionTrack> tracks) {
        final int atIdx = json.indexOf("\"audioTracks\":[");
        if (atIdx < 0) {
            return null;
        }
        final int arrStart = json.indexOf('[', atIdx);
        if (arrStart < 0) return null;
        int depth = 0;
        int arrEnd = -1;
        for (int i = arrStart; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) { arrEnd = i; break; }
            }
        }
        if (arrEnd < 0) return null;

        int defaultIdx = -1;
        String daiKey = "\"defaultAudioTrackIndex\":";
        int daiIdx = json.indexOf(daiKey);
        if (daiIdx >= 0 && daiIdx < arrEnd + 200) {
            int numStart = daiIdx + daiKey.length();
            int numEnd = numStart;
            while (numEnd < json.length() && Character.isDigit(json.charAt(numEnd))) {
                numEnd++;
            }
            if (numEnd > numStart) {
                try {
                    defaultIdx = Integer.parseInt(json.substring(numStart, numEnd));
                } catch (NumberFormatException ignored) {}
            }
        }
        if (defaultIdx < 0) {
            defaultIdx = 0;
        }

        try {
            JSONArray arr = new JSONArray(json.substring(arrStart, arrEnd + 1));
            if (defaultIdx >= arr.length()) {
                defaultIdx = 0;
            }
            JSONObject defaultTrack = arr.getJSONObject(defaultIdx);
            JSONArray indices = defaultTrack.optJSONArray("captionTrackIndices");
            if (indices == null || indices.length() == 0) {
                return null;
            }
            final int captionIdx = indices.getInt(0);
            if (captionIdx >= 0 && captionIdx < tracks.size()) {
                return tracks.get(captionIdx).langCode;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not pick the caption language", ex);
        }
        return null;
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

    @Nullable
    private static String extractPlayabilityError(String json) {
        final int psIdx = json.indexOf("\"playabilityStatus\":{");
        if (psIdx < 0) return null;
        final int psStart = psIdx + "\"playabilityStatus\":".length();
        String status = extractJsonString(json, psStart, "status");
        if (status == null || "OK".equals(status)) return null;

        String reason = extractJsonString(json, psStart, "reason");
        if (reason != null && !reason.isEmpty()) {
            return reason;
        }
        return "playabilityStatus=" + status;
    }

    @Nullable
    private static TrackInfo extractVideoDetails(String json) {
        final int vdIdx = json.indexOf("\"videoDetails\":{");
        if (vdIdx < 0) {
            return null;
        }
        final int vdStart = vdIdx + "\"videoDetails\":".length();
        String title = extractJsonString(json, vdStart, "title");
        String author = extractJsonString(json, vdStart, "author");
        if (title == null || title.isEmpty() || author == null || author.isEmpty()) {
            return null;
        }
        return new TrackInfo(title, author, "", 0);
    }

    @Nullable
    private static String extractJsonString(String json, int from, String key) {
        String needle = "\"" + key + "\":\"";
        int idx = json.indexOf(needle, from);
        if (idx < 0) {
            return null;
        }
        idx += needle.length();
        final int end = json.indexOf('"', idx);
        if (end < 0) {
            return null;
        }
        return json.substring(idx, end);
    }

    private static String extractNameFromTrack(JSONObject obj) {
        try {
            Object nameObj = obj.opt("name");
            if (nameObj instanceof String str && !str.isEmpty()) {
                return str;
            }
            if (nameObj instanceof JSONObject nameJson) {
                String simpleText = nameJson.optString("simpleText", "");
                if (!simpleText.isEmpty()) {
                    return simpleText;
                }
                JSONArray runs = nameJson.optJSONArray("runs");
                if (runs != null && runs.length() > 0) {
                    return runs.getJSONObject(0).optString("text", "");
                }
            }
        } catch (Exception ignored) {
        }
        return "";
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

    private static List<CaptionTrack> extractCaptionTracks(String json, int tracksIdx) {
        List<CaptionTrack> tracks = new ArrayList<>();
        try {
            final int arrStart = json.indexOf('[', tracksIdx);
            if (arrStart < 0) return tracks;
            int depth = 0;
            int arrEnd = -1;
            for (int i = arrStart; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '[') depth++;
                else if (c == ']') {
                    depth--;
                    if (depth == 0) { arrEnd = i; break; }
                }
            }
            if (arrEnd < 0) return tracks;

            JSONArray arr = new JSONArray(json.substring(arrStart, arrEnd + 1));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);

                String baseUrl = obj.optString("baseUrl", "");
                if (baseUrl.isEmpty()) continue;
                baseUrl = baseUrl.replace("\\u0026", "&")
                        .replace("\\u003d", "=")
                        .replace("\\u003e", ">")
                        .replace("\\u003c", "<");

                String langCode = obj.optString("languageCode", "");
                if (langCode.isEmpty()) {
                    langCode = extractLangFromUrl(baseUrl);
                }
                if (langCode.isEmpty()) continue;

                String kind = obj.optString("kind", "");
                boolean isAsr = "asr".equals(kind);

                String name = extractNameFromTrack(obj);
                if (name.isEmpty()) {
                    name = langCode + (isAsr ? " (auto-generated)" : "");
                }

                String vssId = obj.optString("vssId", "");

                boolean isTranslatable = obj.optBoolean("isTranslatable", true);

                boolean isTranslation = vssId.startsWith(".") || vssId.startsWith(".a.");

                boolean hasTlang = baseUrl.contains("&tlang=") || baseUrl.contains("?tlang=");

                tracks.add(new CaptionTrack(baseUrl, langCode, vssId, isAsr,
                        isTranslatable, isTranslation, hasTlang, name));
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read the caption tracks", ex);
        }
        return tracks;
    }

    @Nullable
    private static CaptionTrack selectPrimaryTrack(List<CaptionTrack> tracks,
                                                   @Nullable String sourceLangCode) {
        CaptionTrack sourceManual = null;
        CaptionTrack fallbackManual = null;
        CaptionTrack fallbackAsr = null;
        for (CaptionTrack track : tracks) {
            if (track.url().contains("variant=gemini")) {
                continue;
            }
            if (!track.isAsr()) {
                if (track.langCode().equals(sourceLangCode)) {
                    sourceManual = track;
                } else if (fallbackManual == null) {
                    fallbackManual = track;
                }
            } else {
                if (fallbackAsr == null) {
                    fallbackAsr = track;
                }
            }
            if (sourceManual != null) break;
        }
        if (sourceManual != null) return sourceManual;
        if (fallbackManual != null) return fallbackManual;
        return fallbackAsr;
    }

    @Nullable
    private static CaptionTrack selectTranslationTrack(List<CaptionTrack> tracks,
                                                       String primaryLang,
                                                       @Nullable String sourceLangCode) {
        String sysLang = Locale.getDefault().getLanguage();
        if (sysLang.isEmpty()) {
            return null;
        }

        CaptionTrack manualMatch = null;
        CaptionTrack asrMatch = null;
        for (CaptionTrack track : tracks) {
            if (track.url().contains("variant=gemini")) continue;
            if (track.langCode().equals(primaryLang)) {
                continue;
            }
            if (track.langCode().equals(sourceLangCode)) {
                continue;
            }
            boolean langMatch = track.langCode().startsWith(sysLang) || sysLang.startsWith(track.langCode());
            if (!langMatch) continue;
            if (!track.isAsr()) {
                manualMatch = track;
            } else if (asrMatch == null) {
                asrMatch = track;
            }
            if (manualMatch != null) break;
        }
        return manualMatch != null ? manualMatch : asrMatch;
    }

    private static String getCookies() {
        String userCookies = Settings.LYRICS_CAPTION_COOKIES.get();
        if (!userCookies.isEmpty()) {
            return userCookies;
        }

        if (cachedCookies != null && !cachedCookies.isEmpty()
                && (System.currentTimeMillis() - cachedCookiesTime) < COOKIE_CACHE_DURATION_MS) {
            return cachedCookies;
        }

        HttpURLConnection conn = null;
        try {
            conn = Requester.openConnection(SW_COOKIE_URL);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", CAPTION_USER_AGENT);
            conn.setRequestProperty("Referer", "https://www.youtube.com/");
            conn.setRequestProperty("Accept", "*/*");
            conn.setInstanceFollowRedirects(true);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);

            final int responseCode = conn.getResponseCode();
            if (responseCode == 200) {
                List<String> setCookies = conn.getHeaderFields().get("Set-Cookie");
                cachedCookies = parseCookies(setCookies);
                cachedCookiesTime = System.currentTimeMillis();
                return cachedCookies;
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch cookies", ex);
        } finally {
            if (conn != null) conn.disconnect();
        }
        return "";
    }

    private static String parseCookies(java.util.List<String> setCookies) {
        if (setCookies == null || setCookies.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String setCookie : setCookies) {
            String entry = setCookie.split(";")[0].trim();
            final int eq = entry.indexOf('=');
            if (eq > 0) {
                String key = entry.substring(0, eq).trim();
                if (COOKIE_KEYS.contains(key)) {
                    if (sb.length() > 0) sb.append("; ");
                    sb.append(entry);
                }
            }
        }
        return sb.toString();
    }

    @Nullable
    private static String extractCookieValue(String cookieHeader, String key) {
        if (cookieHeader == null || cookieHeader.isEmpty()) return null;
        for (String part : cookieHeader.split(";")) {
            String trimmed = part.trim();
            if (trimmed.startsWith(key + "=")) {
                return trimmed.substring(key.length() + 1);
            }
        }
        return null;
    }

    @Nullable
    private static String computeSapisidHash(String cookies) {
        String sapisid = extractCookieValue(cookies, "SAPISID");
        if (sapisid == null || sapisid.isEmpty()) {
            sapisid = extractCookieValue(cookies, "__Secure-3PAPISID");
        }
        if (sapisid == null || sapisid.isEmpty()) return null;

        final long timestamp = System.currentTimeMillis() / 1000;
        String input = timestamp + " " + sapisid + " https://www.youtube.com";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return "SAPISIDHASH " + timestamp + "_" + hex;
        } catch (Exception ex) {
            return null;
        }
    }

    public static boolean validateYouTubeCookies(String cookies) {
        if (cookies == null || cookies.trim().isEmpty()) return false;
        try {
            JSONObject body = new JSONObject();
            JSONObject client = new JSONObject();
            client.put("clientName", "WEB");
            client.put("clientVersion", "2.20250101.00.00");
            client.put("hl", "en");
            client.put("gl", "US");
            JSONObject context = new JSONObject();
            context.put("client", client);
            body.put("context", context);
            body.put("videoId", "dQw4w9WgXcQ");

            HttpURLConnection conn = Requester.openConnection(INNERTUBE_PLAYER_URL);
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(8000);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", CAPTION_USER_AGENT);
                conn.setRequestProperty("Origin", "https://www.youtube.com");
                conn.setRequestProperty("Referer", "https://www.youtube.com/");
                conn.setRequestProperty("X-Origin", "https://www.youtube.com");
                conn.setRequestProperty("X-YouTube-Client-Name", "1");
                conn.setRequestProperty("X-YouTube-Client-Version", "2.20250101.00.00");
                conn.setRequestProperty("X-Goog-AuthUser", "0");
                conn.setRequestProperty("Cookie", cookies);
                String sapisidHash = computeSapisidHash(cookies);
                if (sapisidHash != null) {
                    conn.setRequestProperty("Authorization", sapisidHash);
                }
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body.toString().getBytes(StandardCharsets.UTF_8));
                }

                final int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    return false;
                }

                String json = Requester.parseString(conn);
                JSONObject resp = new JSONObject(json);
                JSONObject ps = resp.optJSONObject("playabilityStatus");
                String status = ps != null ? ps.optString("status") : null;
                return "OK".equals(status);
            } finally {
                conn.disconnect();
            }
        } catch (Exception ex) {
            return false;
        }
    }

    @Nullable
    private static String fetchInnertubePlayer(String videoId) {
        String cookies = getCookies();
        final boolean hasCookies = cookies != null && !cookies.isEmpty();

        try {
            String bodyStr = buildPlayerRequestBody(videoId);

            HttpURLConnection conn = null;
            try {
                conn = Requester.openConnection(INNERTUBE_PLAYER_URL);
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setRequestProperty("User-Agent", CAPTION_USER_AGENT);
                conn.setRequestProperty("Origin", "https://www.youtube.com");
                conn.setRequestProperty("Referer", "https://www.youtube.com/");
                conn.setRequestProperty("X-Origin", "https://www.youtube.com");
                conn.setRequestProperty("X-YouTube-Client-Name", "1");
                conn.setRequestProperty("X-YouTube-Client-Version", "2.20250101.00.00");
                conn.setRequestProperty("X-Goog-AuthUser", "0");
                conn.setDoOutput(true);

                if (hasCookies) {
                    conn.setRequestProperty("Cookie", cookies);
                    String sapisidHash = computeSapisidHash(cookies);
                    if (sapisidHash != null) {
                        conn.setRequestProperty("Authorization", sapisidHash);
                    }
                }

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(bodyStr.getBytes(StandardCharsets.UTF_8));
                }

                final int responseCode = conn.getResponseCode();
                if (responseCode != 200) {
                    return null;
                }
                return Requester.parseString(conn);
            } catch (Exception ex) {
                return null;
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        } catch (Exception ex) {
            return null;
        }
    }

    private static String buildPlayerRequestBody(String videoId) throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", "WEB");
        client.put("clientVersion", "2.20250101.00.00");
        client.put("hl", "en");
        client.put("gl", "US");

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject captionParams = new JSONObject();
        captionParams.put("captionsEnabled", true);

        JSONObject body = new JSONObject();
        body.put("context", context);
        body.put("videoId", videoId);
        body.put("captionParams", captionParams);
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);
        return body.toString();
    }

    /**
     * @param preferredLangs Languages to try before the defaults. Nothing asks for a
     *                       preference yet, so the branch it drives is dormant.
     */
    @Nullable
    @SuppressWarnings("SameParameterValue")
    private static Lyrics fetchViaTimedtext(String videoId, @Nullable String poToken,
                                           @Nullable List<String> preferredLangs) {
        List<String> langs = timedtextLanguages(preferredLangs);

        String pot = (poToken != null && !poToken.isEmpty()) ? "&pot=" + poToken : "";
        for (String lang : langs) {
            try {
                String url = TIMEDTEXT_URL + "?lang=" + lang + "&v=" + videoId
                        + "&kind=asr&fmt=json3" + pot;
                String json = fetchCaptionUrl(url);
                List<LyricsLine> lines = parseJson3(json);
                if (!lines.isEmpty()) {
                    return new Lyrics(lines, Lyrics.CAPTIONS_PROVIDER, true, null, null, null, null, json, "json3", null);
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static List<String> timedtextLanguages(@Nullable List<String> preferredLangs) {
        List<String> langs = new ArrayList<>();
        if (preferredLangs != null) {
            for (String lang : preferredLangs) {
                if (!langs.contains(lang)) {
                    langs.add(lang);
                }
            }
        }
        Locale sys = Locale.getDefault();
        String sysLang = sys.getLanguage();
        if (!sysLang.isEmpty() && !langs.contains(sysLang)) {
            langs.add(sysLang);
        }
        String sysRegion = sys.getLanguage() + "-" + sys.getCountry();
        if (!sysRegion.equals(sysLang) && !langs.contains(sysRegion)) {
            langs.add(sysRegion);
        }
        return langs;
    }

    private static List<LyricsLine> parseJson3(String json) throws Exception {
        JSONObject root = new JSONObject(json);
        if (!root.has("events")) {
            return new ArrayList<>();
        }

        JSONArray events = root.getJSONArray("events");
        List<LyricsLine> lines = new ArrayList<>();

        for (int i = 0; i < events.length(); i++) {
            JSONObject event = events.getJSONObject(i);
            if (event.optInt("aAppend", 0) == 1) {
                continue;
            }
            if (!event.has("segs")) {
                continue;
            }

            StringBuilder textBuilder = new StringBuilder();
            long startTimeMs = event.optLong("tStartMs", 0);

            JSONArray segments = event.getJSONArray("segs");
            for (int j = 0; j < segments.length(); j++) {
                JSONObject seg = segments.getJSONObject(j);
                if (seg.has("utf8")) {
                    textBuilder.append(seg.getString("utf8"));
                }
            }

            String trimmed = textBuilder.toString().trim();
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
            conn.setRequestProperty("User-Agent", CAPTION_USER_AGENT);

            String cookies = getCookies();
            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
            }

            for (Map.Entry<String, String> entry : AuthUtils.getRequestHeader().entrySet()) {
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
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
