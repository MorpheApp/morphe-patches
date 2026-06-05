/*
 * Copyright (C) 2026 anddea
 *
 * This file is part of the revanced-patches project:
 * https://github.com/anddea/revanced-patches
 *
 * Original author(s) (based on contributions):
 * - Jav1x (https://github.com/Jav1x)
 * - anddea (https://github.com/anddea)
 *
 * Licensed under the GNU General Public License v3.0.
 *
 * ------------------------------------------------------------------------
 * GPLv3 Section 7 – Attribution Notice
 * ------------------------------------------------------------------------
 *
 * This file contains substantial original work by the author(s) listed above.
 *
 * In accordance with Section 7 of the GNU General Public License v3.0,
 * the following additional terms apply to this file:
 *
 * 1. Attribution (Section 7(b)): This specific copyright notice and the
 *    list of original authors above must be preserved in any copy or
 *    derivative work. You may add your own copyright notice below it,
 *    but you may not remove the original one.
 *
 * 2. Origin (Section 7(c)): Modified versions must be clearly marked as
 *    such (e.g., by adding a "Modified by" line or a new copyright notice).
 *    They must not be misrepresented as the original work.
 *
 * ------------------------------------------------------------------------
 * Version Control Acknowledgement (Non-binding Request)
 * ------------------------------------------------------------------------
 *
 * While not a legal requirement of the GPLv3, the original author(s)
 * respectfully request that ports or substantial modifications retain
 * historical authorship credit in version control systems (e.g., Git),
 * listing original author(s) appropriately and modifiers as committers
 * or co-authors.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.NonNull;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;


import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

public class VotApiClient {

    private static final String YANDEX_API_HOST = "api.browser.yandex.ru";

    private static final String HMAC_KEY = "bt8xH3VOlb4mqf0nqAibnDOoiPlXsisf";
    private static final String COMPONENT_VERSION = "26.4.1.1026";
    private static final String VOT_MODULE = "video-translation";
    private static final double DEFAULT_DURATION = 310.0;

    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/147.0.0.0 YaBrowser/26.4.1.1026 Yowser/2.5 Safari/537.36";

    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;

    public static final int STATUS_FAILED = 0;
    public static final int STATUS_FINISHED = 1;
    public static final int STATUS_WAITING = 2;
    public static final int STATUS_LONG_WAITING = 3;
    public static final int STATUS_PART_CONTENT = 5;
    public static final int STATUS_AUDIO_REQUESTED = 6;
    public static final int STATUS_SESSION_REQUIRED = 7;

    /** Session state — created on demand, shared across requests. */
    private static String sessionUuid = null;
    private static String sessionSecretKey = null;
    private static long sessionExpiresAt = 0;

    /** Translation result cache — keyed by videoUrl + sourceLang + targetLang + liveVoices. */
    private static final long CACHE_TTL_MS = 30 * 60_000; // 30 minutes
    private static final Map<String, CachedResult> translationCache = new ConcurrentHashMap<>();

    /** Simple flag: once a token passes validation, skip re-checking it during the process lifetime. */
    private static volatile String lastValidatedToken = null;
    private static volatile boolean tokenIsValid = false;

    private record CachedResult(TranslationResult result, long createdAt) {
        boolean isExpired() {
            return System.currentTimeMillis() - createdAt > CACHE_TTL_MS;
        }
    }

    public record TranslationResult(int status, String audioUrl, int remainingTime,
                                    String translationId, String message) {
    }

    /**
     * Converts a direct audio URL (S3/Yandex) to a proxied URL.
     * Used when audio proxy is enabled — routes audio through the configured proxy host.
     *
     * @param originalUrl the original audio URL
     * @return proxied URL, or originalUrl on error
     */
    @NonNull
    public static String toProxyAudioUrl(@NonNull String originalUrl) {
        if (originalUrl.isEmpty()) {
            return originalUrl;
        }
        String proxyHost = Settings.VOT_PROXY_URL.get();
        if (proxyHost.isEmpty()) {
            return originalUrl;
        }
        proxyHost = proxyHost.replaceFirst("^https?://", "").replaceAll("/+$", "");
        try {
            URI uri = new URI(originalUrl);
            String path = uri.getRawPath();
            String query = uri.getRawQuery();
            if (path == null || path.isEmpty()) {
                return originalUrl;
            }
            String pathTrimmed = path.replaceFirst("^/+", "");
            int lastSlash = pathTrimmed.lastIndexOf('/');
            if (lastSlash >= 0) {
                pathTrimmed = pathTrimmed.substring(lastSlash + 1);
            }
            StringBuilder proxyUrl = new StringBuilder();
            proxyUrl.append("https://").append(proxyHost);
            proxyUrl.append("/video-translation/audio-proxy/");
            proxyUrl.append(pathTrimmed);
            if (query != null && !query.isEmpty()) {
                proxyUrl.append("?").append(query);
            }
            Logger.printDebug(() -> "toProxyAudioUrl: " + originalUrl + " -> " + proxyUrl);
            return proxyUrl.toString();
        } catch (URISyntaxException e) {
            Logger.printDebug(() -> "toProxyAudioUrl: invalid URL " + originalUrl);
            return originalUrl;
        }
    }

    public static TranslationResult requestTranslation(
            String videoUrl, double duration,
            String sourceLang, String targetLang,
            String videoTitle
    ) {
        return requestTranslation(videoUrl, duration, sourceLang, targetLang, videoTitle,
                Settings.VOT_USE_LIVE_VOICES.get(), true);
    }

    public static TranslationResult requestTranslation(
            String videoUrl, double duration,
            String sourceLang, String targetLang,
            String videoTitle, boolean useLiveVoices
    ) {
        return requestTranslation(videoUrl, duration, sourceLang, targetLang, videoTitle,
                useLiveVoices, true);
    }

    public static TranslationResult requestTranslation(
            String videoUrl, double duration,
            String sourceLang, String targetLang,
            String videoTitle, boolean useLiveVoices, boolean firstRequest
    ) {
        // Proactively ensure a valid session exists before any API call.
        ensureSession();

        String cacheKey = videoUrl + "|" + sourceLang + "|" + targetLang + "|" + useLiveVoices;
        CachedResult cached = translationCache.get(cacheKey);
        if (cached != null && !cached.isExpired()) {
            Logger.printDebug(() -> "VOT cache hit: " + cacheKey);
            return cached.result();
        }
        if (cached != null) {
            translationCache.remove(cacheKey);
        }

        // Resolve OAuth token once (may be null if not configured)
        String oauthToken = useLiveVoices ? Settings.VOT_OAUTH_TOKEN.get() : null;
        if (oauthToken != null && oauthToken.isEmpty()) {
            oauthToken = null;
        }
        // Validate OAuth token before using it (makes a lightweight API call, cached per process).
        if (oauthToken != null && !isValidOAuthToken(oauthToken)) {
            Logger.printDebug(() -> "VOT OAuth token is invalid, clearing and falling back");
            Settings.VOT_OAUTH_TOKEN.save("");
            // Return a special result — caller will show "auth required" toast.
            return new TranslationResult(STATUS_SESSION_REQUIRED, null, 0, null, null);
        }

        // Retry once on SESSION_REQUIRED
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (duration <= 0) {
                    duration = DEFAULT_DURATION;
                }

                String apiSourceLang = (sourceLang == null || sourceLang.isEmpty() || "auto".equalsIgnoreCase(sourceLang))
                        ? "" : sourceLang;

                byte[] body = VotProtobuf.encodeTranslationRequest(
                        videoUrl, firstRequest, duration,
                        apiSourceLang, targetLang, videoTitle,
                        useLiveVoices
                );

                String path = "/video-translation/translate";
                byte[] responseBytes = sendApiRequest(path, body, oauthToken);

                if (responseBytes == null || responseBytes.length == 0) {
                    return null;
                }

                VotProtobuf.TranslationResponse response = VotProtobuf.decodeTranslationResponse(responseBytes);

                // If server asks for a session, create one and retry
                if (response.status == STATUS_SESSION_REQUIRED && attempt == 0) {
                    Logger.printDebug(() -> "VOT: session required, creating session and retrying...");
                    invalidateSession();
                    if (createSession()) {
                        continue;
                    }
                    // Session creation failed — return the result as-is
                }

                TranslationResult result = new TranslationResult(
                        response.status,
                        response.url,
                        response.remainingTime,
                        response.translationId,
                        response.message
                );

                // Cache only finished / part-content results (not waiting or errors)
                if (result.status() == STATUS_FINISHED || result.status() == STATUS_PART_CONTENT) {
                    translationCache.put(cacheKey, new CachedResult(result, System.currentTimeMillis()));
                }

                return result;

            } catch (Exception e) {
                Logger.printException(() -> "VotApiClient.requestTranslation failed for " + videoUrl, e);
                return null;
            }
        }
        return null; // unreachable but silences warning
    }

    /**
     * Whether a finished (STATUS_FINISHED or STATUS_PART_CONTENT) translation exists in cache
     * for the given video/language pair. Used by the UI to show an indicator on voice-style buttons.
     */
    public static boolean hasCachedTranslation(String videoUrl, String sourceLang, String targetLang, boolean useLiveVoices) {
        String cacheKey = videoUrl + "|" + sourceLang + "|" + targetLang + "|" + useLiveVoices;
        CachedResult cached = translationCache.get(cacheKey);
        return cached != null && !cached.isExpired();
    }

    private static void invalidateSession() {
        sessionSecretKey = null;
        sessionUuid = null;
        sessionExpiresAt = 0;
    }

    /**
     * Sends a protobuf request directly to the Yandex VOT API.
     * Uses the same format as the official Yandex Browser extension.
     *
     * @param oauthToken optional OAuth token for live voices (may be null)
     */
    private static byte[] sendApiRequest(String path, byte[] body, String oauthToken) throws IOException {
        return sendApiRequest(path, body, "POST", oauthToken);
    }

    private static byte[] sendApiRequest(String path, byte[] body, String method, String oauthToken) throws IOException {
        String workerUrl = "https://" + YANDEX_API_HOST + path;
        Logger.printDebug(() -> "VOT sendApiRequest: " + method + " " + workerUrl);

        String vtransSignature = computeHmacHex(body);
        // Use existing session UUID or generate a new one (will be replaced when session is created)
        String uuid = sessionUuid != null ? sessionUuid : generateUuid();
        String tokenData = uuid + ":" + path + ":" + COMPONENT_VERSION;
        String tokenSign = computeHmacHex(tokenData.getBytes(StandardCharsets.UTF_8));
        String vtransToken = tokenSign + ":" + tokenData;

        HttpURLConnection connection = (HttpURLConnection) new URL(workerUrl).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setRequestProperty("Accept", "application/x-protobuf");
            connection.setRequestProperty("Accept-Language", "en");
            connection.setRequestProperty("Content-Type", "application/x-protobuf");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Pragma", "no-cache");
            connection.setRequestProperty("Cache-Control", "no-cache");
            connection.setRequestProperty("Vtrans-Signature", vtransSignature);
            connection.setRequestProperty("Sec-Vtrans-Token", vtransToken);
            if (sessionSecretKey != null && !sessionSecretKey.isEmpty()) {
                connection.setRequestProperty("Sec-Vtrans-Sk", sessionSecretKey);
            }
            if (oauthToken != null && !oauthToken.isEmpty()) {
                connection.setRequestProperty("Authorization", "OAuth " + oauthToken);
            }
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);

            connection.setFixedLengthStreamingMode(body.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(body);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode != 200) {
                Logger.printDebug(() -> "VOT sendApiRequest: " + workerUrl
                        + " returned " + responseCode);
                return null;
            }

            return readBytes(connection.getInputStream());

        } finally {
            connection.disconnect();
        }
    }

    private static String computeHmacHex(byte[] data) {
        try {
            Mac hmac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(
                    HMAC_KEY.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            hmac.init(keySpec);
            byte[] result = hmac.doFinal(data);

            StringBuilder hex = new StringBuilder();
            for (byte b : result) {
                hex.append(String.format(Locale.US, "%02x", b & 0xFF));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            return "";
        }
    }

    private static String generateUuid() {
        String hexDigits = "0123456789ABCDEF";
        Random random = new Random();
        StringBuilder uuid = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            uuid.append(hexDigits.charAt(random.nextInt(16)));
        }
        return uuid.toString();
    }

    /**
     * Creates a new Yandex session. Called when the API returns STATUS_SESSION_REQUIRED
     * or when no valid session exists.
     */
    private static boolean createSession() {
        try {
            String uuid = generateUuid();
            String path = "/session/create";
            byte[] body = VotProtobuf.encodeSessionRequest(uuid, VOT_MODULE);

            // Session creation uses Ya-Summary headers (no body signing, token-based only)
            String tokenData = uuid + ":" + path + ":" + COMPONENT_VERSION;
            String tokenSign = computeHmacHex(tokenData.getBytes(StandardCharsets.UTF_8));
            String summaryToken = tokenSign + ":" + tokenData;

            String url = "https://" + YANDEX_API_HOST + path;
            Logger.printDebug(() -> "VOT createSession: POST " + url);

            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            try {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Accept", "application/x-protobuf");
                connection.setRequestProperty("Content-Type", "application/x-protobuf");
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setRequestProperty("X-Ya-Summary-Token", summaryToken);
                connection.setRequestProperty("X-Ya-Summary-Sk", "");
                connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
                connection.setReadTimeout(READ_TIMEOUT_MS);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);

                try (OutputStream os = connection.getOutputStream()) {
                    os.write(body);
                }

                int responseCode = connection.getResponseCode();
                if (responseCode != 200) {
                    Logger.printDebug(() -> "VOT createSession: returned " + responseCode);
                    return false;
                }

                byte[] responseBytes = readBytes(connection.getInputStream());
                if (responseBytes == null || responseBytes.length == 0) {
                    Logger.printDebug(() -> "VOT createSession: empty response");
                    return false;
                }

                VotProtobuf.SessionResponse response = VotProtobuf.decodeSessionResponse(responseBytes);
                if (response.secretKey == null || response.secretKey.isEmpty()) {
                    Logger.printDebug(() -> "VOT createSession: no secretKey in response");
                    return false;
                }

                sessionUuid = uuid;
                sessionSecretKey = response.secretKey;
                // expires is in seconds from the session creation time
                sessionExpiresAt = System.currentTimeMillis() + (response.expires > 0 ? response.expires * 1000L : 3600_000L);

                Logger.printDebug(() -> "VOT createSession: success, expires in " + response.expires + "s");
                return true;

            } finally {
                connection.disconnect();
            }
        } catch (Exception e) {
            Logger.printException(() -> "VOT createSession failed", e);
            return false;
        }
    }

    /**
     * Returns true if the error message indicates that "live voices" (Lively)
     * are unavailable for the requested language pair. In that case the caller
     * should retry with standard voices instead.
     */
    public static boolean isLivelyVoiceUnavailableError(String message) {
        if (message == null || message.isEmpty()) return false;
        String lower = message.toLowerCase(Locale.US);
        return lower.contains("обычная озвучка") || lower.contains("standard voice");
    }

    /**
     * Validates a Yandex OAuth token by calling login.yandex.ru/info.
     * Caches the result so we only call it once per token per process lifetime.
     *
     * @param token the OAuth token to validate
     * @return true if the token is valid, false otherwise
     */
    public static boolean isValidOAuthToken(String token) {
        if (token == null || token.isEmpty()) return false;
        // Return cached result if we already validated this exact token.
        if (token.equals(lastValidatedToken)) return tokenIsValid;
        try {
            String url = "https://login.yandex.ru/info?format=json";
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Authorization", "OAuth " + token);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                int code = conn.getResponseCode();
                lastValidatedToken = token;
                tokenIsValid = (code == 200);
                Logger.printDebug(() -> "VOT OAuth token validation: HTTP " + code
                        + " -> " + (tokenIsValid ? "valid" : "invalid"));
                return tokenIsValid;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "VOT OAuth token validation failed: " + e.getMessage());
            // On network error, assume valid so we don't block the user.
            lastValidatedToken = token;
            tokenIsValid = true;
            return true;
        }
    }

    /**
     * Clears the translation cache. Call when the user changes language, proxy
     * or other translation-related settings to force fresh API requests.
     */
    public static void clearTranslationCache() {
        translationCache.clear();
    }

    /**
     * Sends a fail-audio request (JSON, PUT) to abort the currently queued audio
     * so that a fresh empty-audio request can trigger regeneration.
     */
    public static void sendFailedAudio(String videoUrl) {
        try {
            String path = "/video-translation/fail-audio-js";
            String jsonBody = "{\"video_url\":\"" + videoUrl + "\"}";
            sendJsonRequest(path, jsonBody, "PUT");
        } catch (Exception e) {
            Logger.printException(() -> "VotApiClient.sendFailedAudio failed for " + videoUrl, e);
        }
    }

    /**
     * Sends an empty audio protobuf request (PUT) to trigger translation generation
     * on Yandex servers. Called after sendFailedAudio when STATUS_AUDIO_REQUESTED.
     */
    public static void sendEmptyAudio(String videoUrl, String translationId, String oauthToken) {
        try {
            byte[] body = VotProtobuf.encodeEmptyAudioRequest(translationId, videoUrl);
            String path = "/video-translation/audio";
            sendApiRequest(path, body, "PUT", oauthToken);
        } catch (Exception e) {
            Logger.printException(() -> "VotApiClient.sendEmptyAudio failed for " + videoUrl, e);
        }
    }

    /**
     * Sends a JSON request to the Yandex VOT API (for fail-audio-js endpoint).
     */
    private static void sendJsonRequest(String path, String jsonBody, String method) throws IOException {
        String workerUrl = "https://" + YANDEX_API_HOST + path;

        HttpURLConnection connection = (HttpURLConnection) new URL(workerUrl).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setDoOutput(true);

            byte[] payloadBytes = jsonBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(payloadBytes.length);

            try (OutputStream os = connection.getOutputStream()) {
                os.write(payloadBytes);
            }
        } finally {
            connection.disconnect();
        }
    }

    /** Returns true if the current session is still valid. */
    private static boolean hasValidSession() {
        return sessionSecretKey != null && !sessionSecretKey.isEmpty()
                && System.currentTimeMillis() < sessionExpiresAt;
    }

    /** Ensures a valid session exists, creating one if necessary. */
    private static boolean ensureSession() {
        if (hasValidSession()) return true;
        return createSession();
    }

    private static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] chunk = new byte[4096];
        int bytesRead;
        while ((bytesRead = is.read(chunk)) != -1) {
            buffer.write(chunk, 0, bytesRead);
        }
        return buffer.toByteArray();
    }
}
