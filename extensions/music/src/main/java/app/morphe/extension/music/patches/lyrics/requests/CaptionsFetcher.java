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

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.model.Word;
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
    private static final int VALIDATE_CONNECT_TIMEOUT_MS = 10_000;
    private static final int VALIDATE_READ_TIMEOUT_MS = 12_000;
    private static final String INNERTUBE_PLAYER_URL =
            "https://www.youtube.com/youtubei/v1/player?prettyPrint=false";
    private static final String TIMEDTEXT_URL =
            "https://www.youtube.com/api/timedtext";

    /*
 * Naming the ASR track apart is what lets the footer say which caption track is on
 * screen. It has to remain a prefix-extension of CAPTIONS_PROVIDER, because that is how
 * the rest of the lyrics code tells a video-timeline result from a title-matched one.
 */
    private static final String ASR_PROVIDER_NAME = Lyrics.CAPTIONS_PROVIDER + " (ASR)";

    private static final String CAPTION_USER_AGENT = LyricsRequests.CHROME_DESKTOP_USER_AGENT;

    private static final String SW_COOKIE_URL = "https://www.youtube.com/sw.js";
    private static final List<String> COOKIE_KEYS = Arrays.asList(
            "YSC", "VISITOR_INFO1_LIVE", "VISITOR_PRIVACY_METADATA", "__Secure-ROLLOUT_TOKEN"
    );
    private static final List<String> REQUIRED_USER_COOKIE_KEYS = Arrays.asList(
            "SID", "HSID", "SSID", "SAPISID", "__Secure-3PAPISID", "__Secure-1PAPISID", "LOGIN_INFO"
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
        static final CaptionsOutcome ALLOW_PROVIDERS = new CaptionsOutcome(null, null, null, false, null, null);

        public final @Nullable Lyrics lyrics;
        public final @Nullable Lyrics translationLyrics;
        public final @Nullable Lyrics asrLyrics;
        public final boolean suppressProviders;
        public final @Nullable TrackInfo innertubeTrack;
        public final @Nullable String errorReason;

        private CaptionsOutcome(@Nullable Lyrics lyrics, @Nullable Lyrics translationLyrics,
                                @Nullable Lyrics asrLyrics, boolean suppressProviders,
                                @Nullable TrackInfo innertubeTrack,
                                @Nullable String errorReason) {
            this.lyrics = lyrics;
            this.translationLyrics = translationLyrics;
            this.asrLyrics = asrLyrics;
            this.suppressProviders = suppressProviders;
            this.innertubeTrack = innertubeTrack;
            this.errorReason = errorReason;
        }

        static CaptionsOutcome captions(Lyrics lyrics, @Nullable TrackInfo innertubeTrack) {
            return new CaptionsOutcome(lyrics, null, null, false, innertubeTrack, null);
        }

        static CaptionsOutcome captions(Lyrics lyrics, @Nullable Lyrics translationLyrics,
                                         @Nullable Lyrics asrLyrics,
                                         @Nullable TrackInfo innertubeTrack) {
            return new CaptionsOutcome(lyrics, translationLyrics, asrLyrics, false, innertubeTrack, null);
        }

        static CaptionsOutcome withError(@Nullable String reason, @Nullable TrackInfo innertubeTrack) {
            return new CaptionsOutcome(null, null, null, false, innertubeTrack, reason);
        }
    }

    public static final class CaptionsProvider implements LyricsProvider {
        @Override
        public String name() {
            return "Captions";
        }

        @Override
        @Nullable
        public FetchResult fetch(TrackInfo track) throws Exception {
            CaptionsOutcome outcome = CaptionsFetcher.fetch();

            if (outcome.errorReason != null) {
                return null;
            }

            if (outcome.lyrics == null || outcome.lyrics.isEmpty()) {
                return null;
            }

            Lyrics result = outcome.lyrics;
            if (outcome.translationLyrics != null && !outcome.translationLyrics.isEmpty()) {
                Map<String, List<LyricsLine>> translations = new HashMap<>();
                translations.put(LyricsRequests.translationLanguage(),
                        outcome.translationLyrics.lines());
                result = new Lyrics(result.lines(), result.providerName(), result.synced(),
                        result.romanization(), translations,
                        result.romanizations(), result.songwriters(),
                        result.rawFormat(), result.formatType(),
                        result.sourceUrl());
            }

            return FetchResult.of(result);
        }

        /*
 * The ASR track is worth a candidate slot because it is the only caption track that
 * carries a per-word timeline. It does not take over the screen on its own: the lookups
 * that reach this provider have already put the hand written text there.
 */
        @Override
        public boolean hasCandidates() {
            return true;
        }

        @Override
        public List<Lyrics.ScoredLyrics> fetchCandidates(TrackInfo track) {
            CaptionsOutcome outcome = CaptionsFetcher.fetch(true);
            List<Lyrics.ScoredLyrics> candidates = new ArrayList<>(2);
            if (outcome.errorReason == null) {
                if (outcome.lyrics != null && !outcome.lyrics.isEmpty()) {
                    candidates.add(scored(outcome.lyrics, track));
                }
                if (outcome.asrLyrics != null && !outcome.asrLyrics.isEmpty()) {
                    candidates.add(scored(outcome.asrLyrics, track));
                }
            }
            return candidates;
        }

        /* The manager takes the reported score back apart, so both tracks have to report on the
 * same match and sync scale or their ranking would compare two different quantities. */
        private static Lyrics.ScoredLyrics scored(Lyrics lyrics, TrackInfo track) {
            FetchResult result = FetchResult.of(lyrics);
            int score = result.matchScore(track) + LyricsRequests.syncRank(lyrics);
            return new Lyrics.ScoredLyrics(score, lyrics);
        }
    }

    public static CaptionsOutcome fetch() {
        return fetch(false);
    }

    /*
 * @param includeAsr also pull the ASR track of the same song. Only the candidate pass
 *                   asks for it: the ordinary one already spent its request on the hand
 *                   written track, and paying for a second one on every lookup would buy a
 *                   candidate the user has to refresh his way to.
 */
    private static CaptionsOutcome fetch(boolean includeAsr) {
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
                List<CaptionTrack> primaryTracks = selectPrimaryTracks(captions.tracks(),
                        captions.sourceLangCode());
                if (!primaryTracks.isEmpty()) {
                    CaptionTrack primaryTrack = primaryTracks.get(0);
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
                        Lyrics asrLyrics = null;
                        if (includeAsr) {
                            for (CaptionTrack track : primaryTracks) {
                                if (track.isAsr()) {
                                    asrLyrics = fetchTrackLyrics(track, captions.poToken(),
                                            ASR_PROVIDER_NAME);
                                    break;
                                }
                            }
                        }
                        lastFetchedVideoId = videoId;
                        return CaptionsOutcome.captions(primaryLyrics, translationLyrics, asrLyrics,
                                captions.innertubeTrack());
                    }
                }
            }

            Lyrics timed = fetchViaTimedtext(videoId, captions.poToken());
            if (timed != null && !timed.isEmpty()) {
                lastFetchedVideoId = videoId;
                return CaptionsOutcome.captions(timed, captions.innertubeTrack());
            }
            return allowProviders(captions.innertubeTrack());
        } catch (Exception ex) {
            Logger.printDebug(() -> "fetchCaptions failure", ex);
            return CaptionsOutcome.ALLOW_PROVIDERS;
        }
    }

    @Nullable
    private static Lyrics fetchTrackLyrics(CaptionTrack track, @Nullable String poToken) {
        return fetchTrackLyrics(track, poToken, Lyrics.CAPTIONS_PROVIDER);
    }

    @Nullable
    private static Lyrics fetchTrackLyrics(CaptionTrack track, @Nullable String poToken,
                                            String providerName) {
        try {
            String url = buildCaptionUrl(track.url(), poToken);
            String json = fetchCaptionUrl(url);
            List<LyricsLine> lines = parseJson3(json);
            if (!lines.isEmpty()) {
                return new Lyrics(lines, providerName, true, null, null, null, null, json, "json3", null);
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
        Lyrics timed = fetchViaTimedtext(videoId, poToken);
        if (timed != null && !timed.isEmpty()) {
            lastFetchedVideoId = videoId;
            return CaptionsOutcome.captions(timed, innertubeTrack);
        }
        return allowProviders(innertubeTrack);
    }

    private static CaptionsOutcome allowProviders(@Nullable TrackInfo innertubeTrack) {
        return new CaptionsOutcome(null, null, null, false, innertubeTrack, null);
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
                    Logger.printDebug(() -> "Interrupted waiting for video ID", ex);
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
                Logger.printDebug(() -> "Interrupted retrying video ID", ex);
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

    /**
     * Where the JSON array opening at {@code arrStart} closes, or -1 when it never does. Nesting
     * is counted rather than trusted, since these arrays carry nested ones.
     */
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

    private static Set<String> extractTranslationLanguages(String json) {
        Set<String> langs = new HashSet<>();
        int idx = json.indexOf("\"translationLanguages\":[");
        if (idx < 0) {
            return langs;
        }
        int arrStart = json.indexOf('[', idx);
        int arrEnd = arrStart < 0 ? -1 : endOfJsonArray(json, arrStart);
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
        final int arrEnd = arrStart < 0 ? -1 : endOfJsonArray(json, arrStart);
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
                } catch (NumberFormatException ex) {
                    Logger.printDebug(() -> "Could not parse default track index", ex);
                }
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
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not extract name from track", ex);
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
            final int arrEnd = arrStart < 0 ? -1 : endOfJsonArray(json, arrStart);
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

    /**
     * The ASR track goes last and never takes the primary slot: its text is the one a hand
     * written track of the same song is a correction of, so it is worth offering only where
     * the hand written one is not.
     */
    private static List<CaptionTrack> selectPrimaryTracks(List<CaptionTrack> tracks,
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
                    if (sourceManual == null) {
                        sourceManual = track;
                    }
                } else if (fallbackManual == null) {
                    fallbackManual = track;
                }
            } else if (fallbackAsr == null) {
                fallbackAsr = track;
            }
        }
        List<CaptionTrack> ordered = new ArrayList<>(3);
        for (CaptionTrack track : new CaptionTrack[]{sourceManual, fallbackManual, fallbackAsr}) {
            if (track != null && !ordered.contains(track)) {
                ordered.add(track);
            }
        }
        return ordered;
    }

    @Nullable
    private static CaptionTrack selectTranslationTrack(List<CaptionTrack> tracks,
                                                       String primaryLang,
                                                       @Nullable String sourceLangCode) {
        String sysLang = LyricsRequests.primarySubtag(LyricsRequests.translationLanguage());
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
        String userCookies = normalizeCookies(Settings.LYRICS_CAPTION_COOKIES.get());
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
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
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
                    //noinspection SizeReplaceableByIsEmpty
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

    private static String normalizeCookies(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        String value = raw.trim();
        if (value.regionMatches(true, 0, "Cookie:", 0, "Cookie:".length())) {
            value = value.substring("Cookie:".length()).trim();
        }
        final int valueLength = value.length();
        if (valueLength >= 2) {
            final char firstChar = value.charAt(0);
            final char lastChar = value.charAt(valueLength - 1);
            if (((firstChar == '"' && lastChar == '"') || (firstChar == '\'' && lastChar == '\''))) {
                value = value.substring(1, valueLength - 1).trim();
            }
        }

        StringBuilder sb = new StringBuilder(valueLength);
        for (String part : value.split("[;\\r\\n]+")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty() || trimmed.indexOf('=') <= 0) continue;
            //noinspection SizeReplaceableByIsEmpty
            if (sb.length() > 0) sb.append("; ");
            sb.append(trimmed);
        }
        return sb.toString();
    }

    private static boolean hasRequiredCookieKeys(String cookies) {
        if (cookies.isEmpty()) return false;
        for (String key : REQUIRED_USER_COOKIE_KEYS) {
            String value = extractCookieValue(cookies, key);
            if (value == null || value.isEmpty()) return false;
        }
        return true;
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
            Logger.printDebug(() -> "Could not compute SAPISIDHASH", ex);
            return null;
        }
    }

    @Nullable
    private static String postInnertubePlayer(String bodyJson, @Nullable String cookies,
                                              int connectTimeoutMs, int readTimeoutMs) throws Exception {
        HttpURLConnection conn = Requester.openConnection(INNERTUBE_PLAYER_URL);
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("User-Agent", CAPTION_USER_AGENT);
            conn.setRequestProperty("Origin", "https://www.youtube.com");
            conn.setRequestProperty("Referer", "https://www.youtube.com/");
            conn.setRequestProperty("X-Origin", "https://www.youtube.com");
            conn.setRequestProperty("X-YouTube-Client-Name", "1");
            conn.setRequestProperty("X-YouTube-Client-Version", "2.20250101.00.00");
            conn.setRequestProperty("X-Goog-AuthUser", "0");

            if (cookies != null && !cookies.isEmpty()) {
                conn.setRequestProperty("Cookie", cookies);
                String sapisidHash = computeSapisidHash(cookies);
                if (sapisidHash != null) {
                    conn.setRequestProperty("Authorization", sapisidHash);
                }
            }
            conn.setDoOutput(true);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyJson.getBytes(StandardCharsets.UTF_8));
            }

            final int responseCode = conn.getResponseCode();
            if (responseCode != 200) {
                return null;
            }

            return Requester.parseString(conn);
        } finally {
            conn.disconnect();
        }
    }

    public static boolean validateYouTubeCookies(String cookies) {
        if (cookies == null || cookies.trim().isEmpty()) return false;
        String normalized = normalizeCookies(cookies);
        if (!hasRequiredCookieKeys(normalized)) {
            Logger.printDebug(() -> "YouTube cookies are missing required keys");
            return false;
        }
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

            String json = null;
            for (int attempt = 1; attempt <= 2 && json == null; attempt++) {
                final int currentAttempt = attempt;
                try {
                    json = postInnertubePlayer(body.toString(), normalized,
                            VALIDATE_CONNECT_TIMEOUT_MS, VALIDATE_READ_TIMEOUT_MS);
                } catch (IOException ex) {
                    Logger.printDebug(() -> "YouTube cookie validation attempt "
                            + currentAttempt + " failed", ex);
                }
            }
            if (json == null) {
                return false;
            }
            JSONObject resp = new JSONObject(json);
            JSONObject ps = resp.optJSONObject("playabilityStatus");
            String status = ps != null ? ps.optString("status") : null;
            return "OK".equals(status);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not validate YouTube cookies", ex);
            return false;
        }
    }

    @Nullable
    private static String fetchInnertubePlayer(String videoId) {
        try {
            String bodyStr = buildPlayerRequestBody(videoId);
            return postInnertubePlayer(bodyStr, getCookies(), CONNECT_TIMEOUT_MS, READ_TIMEOUT_MS);
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not fetch InnerTube player", ex);
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

    @Nullable
    private static Lyrics fetchViaTimedtext(String videoId, @Nullable String poToken) {
        List<String> langs = timedtextLanguages();

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
            } catch (Exception ex) {
                Logger.printDebug(() -> "Could not fetch via timedtext for lang: " + lang, ex);
            }
        }
        return null;
    }

    private static List<String> timedtextLanguages() {
        List<String> langs = new ArrayList<>();
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

            List<Word> words = wordTimings(event, segments, startTimeMs);
            lines.add(words.isEmpty()
                    ? new LyricsLine(startTimeMs, trimmed)
                    : new LyricsLine(startTimeMs, LyricsLine.NO_TIME, trimmed, words));
        }

        return lines;
    }

    private static List<Word> wordTimings(JSONObject event, JSONArray segments,
                                           long startTimeMs) throws JSONException {
        final int count = segments.length();
        if (count < 2) {
            return List.of();
        }
        List<Word> words = new ArrayList<>(count);
        boolean offsetByWord = false;
        for (int j = 0; j < count; j++) {
            JSONObject seg = segments.getJSONObject(j);
            String raw = seg.optString("utf8", "");
            String word = raw.trim();
            if (word.isEmpty()) {
                continue;
            }
            long startMs = startTimeMs + seg.optLong("tOffsetMs", 0);
            offsetByWord |= startMs > startTimeMs;
            words.add(new Word(startMs, startMs, word, null,
                    Character.isWhitespace(raw.charAt(raw.length() - 1))));
        }
        // Nothing places a word past the line itself, so these words would all land on one
        // instant and the timing cleanup upstream would spread them into a timeline the
        // payload never carried.
        if (!offsetByWord || words.size() < 2) {
            return List.of();
        }
        final int total = words.size();
        long eventEndMs = startTimeMs + event.optLong("dDurationMs", 0);
        for (int k = 0; k < total - 1; k++) {
            Word word = words.get(k);
            words.set(k, new Word(word.startMs(), words.get(k + 1).startMs(), word.text(),
                    null, word.endsWithSpace()));
        }
        Word last = words.get(total - 1);
        words.set(total - 1, new Word(last.startMs(),
                Math.max(eventEndMs, last.startMs()), last.text(),
                null, last.endsWithSpace()));
        return words;
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
