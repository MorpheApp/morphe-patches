/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2533
 * https://github.com/MorpheApp/morphe-patches/pull/3663
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.potoken;

import android.os.SystemClock;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

public final class BotGuardManager {

    /**
     * @param expirationMs {@link SystemClock#elapsedRealtime()} when the token expires.
     */
    public record IntegrityToken(String token, long expirationMs) {
    }

    private record Challenge(String key, String data) {
    }

    private static final String BOT_GUARD_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT";
    private static final String BOT_GUARD_REQUEST_KEY = "O43z0dpjhgX20SCx4KAo";
    private static final String YOUTUBE_CONFIG_URL = "https://www.youtube.com/tv_config?action_get_config=true";
    private static final String YOUTUBE_URL = "https://www.youtube.com/";
    private static final String YOUTUBE_TV_URL = "https://www.youtube.com/tv";
    private static final String INTERPRETER_FILE_PREFIX = "botguard_interpreter_";
    private static final Pattern INTERPRETER_HASH_PATTERN = Pattern.compile("[A-Za-z0-9_-]{43}");
    private static final String USER_AGENT = "Mozilla/5.0 (SMART-TV; Linux; Tizen 8.0) AppleWebKit/537.36 (KHTML, like Gecko) SamsungBrowser/7.0 Chrome/108.0.5359.1 TV Safari/537.36";
    /**
     * TCP connection and HTTP read timeout.
     */
    private static final int HTTP_TIMEOUT_MILLISECONDS = 5 * 1000;

    /**
     * Any arbitrarily large value, but must be at least twice {@link #HTTP_TIMEOUT_MILLISECONDS}
     */
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 10 * 1000;

    /**
     * 5 hours 55 mins.
     * Leave 5 minutes of margin just to be sure.
     */
    private static final long CHALLENGE_DATA_EXPIRATION_MS = 6 * 60 * 60 * 1000L - 5 * 60 * 1000L;

    @Nullable
    private volatile static String challengeData = null;
    @NonNull
    private volatile static String challengeRequestKey = BOT_GUARD_REQUEST_KEY;

    /**
     * {@link SystemClock#elapsedRealtime()} when the challenge was fetched.
     * A monotonic clock is used so changes to the device time cannot extend or shorten its lifetime.
     */
    private volatile static long challengeFetchedTime = -1L;

    /**
     * Margin subtracted from the integrity token lifetime the server gives,
     * so a token is not used right as it expires.
     */
    private static final long INTEGRITY_TOKEN_EXPIRATION_MARGIN_SECONDS = 5 * 60;

    private BotGuardManager() {
    }

    /**
     * Must be called off the main thread.
     */
    @Nullable
    public static synchronized String getChallengeData() {
        if (isChallengeDataNotExpired()) {
            return challengeData;
        }
        // Previously fetched data is expired and must not be used,
        // even if a new challenge cannot be fetched.
        challengeData = null;
        Challenge challenge = downloadChallenge();
        if (challenge != null) {
            challengeData = challenge.data;
            String requestKey = challenge.key;
            if (Utils.isNotEmpty(requestKey)) {
                challengeRequestKey = requestKey;
            }
            challengeFetchedTime = SystemClock.elapsedRealtime();
        }
        return challengeData;
    }

    @Nullable
    private static Challenge fetchChallenge() {
        String jsonString = downloadUrl(YOUTUBE_CONFIG_URL);
        if (jsonString == null || !jsonString.startsWith(")]}'")) {
            return null;
        }

        try {
            JSONObject json = new JSONObject(jsonString.substring(4));
            String challengeRequestKey = json.getString("challengeRequestKey");
            String rawData = json.getJSONObject("challengeParams").getString("R");
            JSONObject scrambled = new JSONObject(rawData);
            JSONObject bgChallenge = scrambled.getJSONObject("bgChallenge");
            String interpreterHash = bgChallenge.getString("interpreterHash");
            String program = bgChallenge.getString("program");
            String globalName = bgChallenge.getString("globalName");
            String clientExperimentsStateBlob = bgChallenge.getString("clientExperimentsStateBlob");
            String privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = bgChallenge
                    .getJSONObject("interpreterUrl")
                    .getString("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue");
            String privateDoNotAccessOrElseSafeScriptWrappedValue = getInterpreterScript(
                    "https:" + privateDoNotAccessOrElseTrustedResourceUrlWrappedValue, interpreterHash);
            if (privateDoNotAccessOrElseSafeScriptWrappedValue == null) {
                return null;
            }

            JSONObject interpreterJavascript = new JSONObject();
            interpreterJavascript.put("privateDoNotAccessOrElseSafeScriptWrappedValue", privateDoNotAccessOrElseSafeScriptWrappedValue);
            interpreterJavascript.put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", privateDoNotAccessOrElseTrustedResourceUrlWrappedValue);

            JSONObject challengeData = new JSONObject();
            challengeData.put("interpreterJavascript", interpreterJavascript);
            challengeData.put("interpreterHash", interpreterHash);
            challengeData.put("program", program);
            challengeData.put("globalName", globalName);
            challengeData.put("clientExperimentsStateBlob", clientExperimentsStateBlob);

            return new Challenge(challengeRequestKey, challengeData.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to parse challenge data", ex);
        }

        return null;
    }

    /**
     * The interpreter is named after the SHA-256 of its content, so a saved copy stays valid
     * for as long as the challenge names the same hash. Only the challenge itself must be fresh.
     */
    @Nullable
    private static String getInterpreterScript(String url, String hash) {
        if (!INTERPRETER_HASH_PATTERN.matcher(hash).matches()) {
            return downloadUrl(url);
        }

        File cacheDir = Utils.getContext().getCacheDir();
        File file = new File(cacheDir, INTERPRETER_FILE_PREFIX + hash + ".js");
        try {
            if (file.isFile()) {
                // Files.readString and Files.writeString need Android 13.
                //noinspection ReadWriteStringCanBeUsed
                String script = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
                if (hash.equals(sha256(script))) {
                    Logger.printDebug(() -> "BotGuard interpreter cache found: " + hash);
                    return script;
                }
                Logger.printDebug(() -> "Ignoring BotGuard interpreter cache that does not match its hash");
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not read BotGuard interpreter cache", ex);
        }

        String script = downloadUrl(url);
        try {
            if (script != null && hash.equals(sha256(script))) {
                // A partly written file would fail the hash check, but writing it fully first avoids a wasted download.
                File temp = new File(file.getPath() + ".tmp");
                //noinspection ReadWriteStringCanBeUsed
                Files.write(temp.toPath(), script.getBytes(StandardCharsets.UTF_8));
                if (temp.renameTo(file)) {
                    File[] files = cacheDir.listFiles((dir, name) ->
                            name.startsWith(INTERPRETER_FILE_PREFIX) && !name.equals(file.getName()));
                    if (files != null) {
                        for (File old : files) {
                            //noinspection ResultOfMethodCallIgnored
                            old.delete();
                        }
                    }
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    temp.delete();
                }
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not save BotGuard interpreter cache", ex);
        }
        return script;
    }

    private static String sha256(String content) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(digest, Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
    }

    public static String getUserAgent() {
        return USER_AGENT;
    }


    @Nullable
    public static IntegrityToken getIntegrityToken(@Nullable String botGuardResult) {
        CompletableFuture<IntegrityToken> integrityTokenFuture = CompletableFuture.supplyAsync(() -> fetchIntegrityToken(botGuardResult))
                .thenApply(botGuardResponse -> {
                    if (botGuardResponse != null) {
                        try {
                            int length = botGuardResponse.length();
                            final long lifetimeSeconds = length > 1
                                    ? botGuardResponse.optLong(1, -1L)
                                    : -1L;
                            final long currentTime = SystemClock.elapsedRealtime();
                            final long expirationMs;
                            if (lifetimeSeconds > INTEGRITY_TOKEN_EXPIRATION_MARGIN_SECONDS) {
                                expirationMs = currentTime
                                        + (lifetimeSeconds - INTEGRITY_TOKEN_EXPIRATION_MARGIN_SECONDS) * 1000;
                            } else {
                                // Lifetime is unknown or too short to leave a margin.
                                // Use the token only for the current request,
                                // and a new token is fetched for the next request.
                                Logger.printException(() -> "Integrity token has no usable lifetime: "
                                        + lifetimeSeconds + ", response length: " + length);
                                expirationMs = currentTime;
                            }

                            for (int i = length - 1; i >= 0; i--) {
                                if (botGuardResponse.get(i) instanceof String rawValue) {
                                    String token = BotGuardUtil.base64ToU8(rawValue);
                                    return new IntegrityToken(token, expirationMs);
                                }
                            }
                        } catch (Exception ex) {
                            Logger.printException(() -> "Failed to parse BotGuard response", ex);
                        }
                    }

                    return null;
                });
        try {
            return integrityTokenFuture.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getIntegrityToken timed out", ex);
            integrityTokenFuture.cancel(true);
        } catch (CancellationException ex) {
            Logger.printInfo(() -> "getIntegrityToken was previously cancelled");
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getIntegrityToken interrupted", ex);
            integrityTokenFuture.cancel(true);
            Thread.currentThread().interrupt(); // Restore interrupt status flag.
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getIntegrityToken failure", ex);
        }

        return null;
    }

    private static boolean isChallengeDataNotExpired() {
        return Utils.isNotEmpty(challengeData) && SystemClock.elapsedRealtime()
                - challengeFetchedTime < CHALLENGE_DATA_EXPIRATION_MS;
    }

    /**
     * Always downloads a new challenge. A failed or timed out download
     * does not prevent a later call from trying again.
     */
    @Nullable
    private static Challenge downloadChallenge() {
        Future<Challenge> challengeFuture = Utils.submitOnBackgroundThread(BotGuardManager::fetchChallenge);
        try {
            return challengeFuture.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "downloadChallenge timed out", ex);
            challengeFuture.cancel(true);
        } catch (CancellationException ex) {
            Logger.printInfo(() -> "downloadChallenge was previously cancelled");
        } catch (InterruptedException ex) {
            Logger.printException(() -> "downloadChallenge interrupted", ex);
            challengeFuture.cancel(true);
            Thread.currentThread().interrupt(); // Restore interrupt status flag.
        } catch (ExecutionException ex) {
            Logger.printException(() -> "downloadChallenge failure", ex);
        }

        return null;
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        if (SharedYouTubeSettings.DEBUG.get()) {
            Utils.showToastShort(toastMessage);
        }
        Logger.printInfo(() -> toastMessage, ex);
    }

    @Nullable
    private static String downloadUrl(@NonNull String url) {
        if (Utils.isNetworkConnected()) {
            try {
                Logger.printDebug(() -> "Starting download of: " + url);

                final long start = System.currentTimeMillis();
                HttpURLConnection connection = Requester.openConnection(url);
                connection.setFixedLengthStreamingMode(0);
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Referer", YOUTUBE_TV_URL);
                connection.setRequestProperty("User-Agent", USER_AGENT);
                connection.setConnectTimeout(HTTP_TIMEOUT_MILLISECONDS);
                connection.setReadTimeout(HTTP_TIMEOUT_MILLISECONDS);
                final int responseCode = connection.getResponseCode();

                final String content;
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    content = Requester.parseString(connection);
                } else {
                    handleConnectionError("Ignoring response code: " + responseCode, null);
                    content = null;
                }
                connection.disconnect();

                Logger.printDebug(() -> "Download took: " + (System.currentTimeMillis() - start) + "ms for URL: " + url);
                return content;
            } catch (SocketTimeoutException ex) {
                handleConnectionError("Connection timeout", ex);
            } catch (IOException ex) {
                handleConnectionError("Network error", ex);
            }
        } else {
            handleConnectionError("No internet connection: " + url, null);
        }

        return null;
    }

    @Nullable
    private static JSONArray fetchIntegrityToken(@Nullable String botGuardResult) {
        if (!Utils.isNotEmpty(botGuardResult)) {
            handleConnectionError("BotGuardResult is null", null);
            return null;
        }
        if (!Utils.isNetworkConnected()) {
            handleConnectionError("No internet connection", null);
            return null;
        }
        try {
            Logger.printDebug(() -> "Starting fetching integrity token");

            final long start = System.currentTimeMillis();
            HttpURLConnection connection = Requester.openConnection(BOT_GUARD_URL);
            connection.setDoOutput(true);
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Referer", YOUTUBE_URL);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Content-Type", "application/json+protobuf");
            connection.setRequestProperty("x-goog-api-key", "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw");
            connection.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1");
            connection.setConnectTimeout(HTTP_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(HTTP_TIMEOUT_MILLISECONDS);
            JSONArray body = new JSONArray(List.of(challengeRequestKey, botGuardResult));
            byte[] requestBody = body.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);
            final int responseCode = connection.getResponseCode();

            final JSONArray result;
            if (responseCode == HttpURLConnection.HTTP_OK) {
                result = Requester.parseJSONArray(connection);
            } else {
                Logger.printDebug(() -> "Ignoring response code: " + responseCode);
                result = null;
            }
            connection.disconnect();

            Logger.printDebug(() -> "Fetched integrity token, took: " + (System.currentTimeMillis() - start) + "ms");
            return result;
        } catch (JSONException | SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex);
        }

        return null;
    }

}