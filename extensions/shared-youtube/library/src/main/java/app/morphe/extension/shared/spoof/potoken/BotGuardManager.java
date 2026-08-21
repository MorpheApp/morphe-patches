/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2533
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.potoken;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

public final class BotGuardManager {
    private static final String BOT_GUARD_URL = "https://www.youtube.com/api/jnn/v1/GenerateIT";
    private static final String BOT_GUARD_REQUEST_KEY = "O43z0dpjhgX20SCx4KAo";
    private static final String YOUTUBE_CONFIG_URL = "https://www.youtube.com/tv_config?action_get_config=true";
    private static final String YOUTUBE_URL = "https://www.youtube.com/";
    private static final String YOUTUBE_TV_URL = "https://www.youtube.com/tv";
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

    private volatile static long challengeFetchedTime = -1L;

    private static final CompletableFuture<Challenge> challengeFuture = CompletableFuture.supplyAsync(() -> downloadUrl(YOUTUBE_CONFIG_URL))
            .thenApplyAsync(jsonString -> {
                if (jsonString != null && jsonString.startsWith(")]}'")) {
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
                        String privateDoNotAccessOrElseSafeScriptWrappedValue =
                                downloadUrl("https:" + privateDoNotAccessOrElseTrustedResourceUrlWrappedValue);

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
                }

                return null;
            });

    private BotGuardManager() {
    }

    @Nullable
    public static String getChallengeData() {
        if (isChallengeDataNotExpired()) {
            return challengeData;
        }
        Challenge challenge = downloadChallenge();
        if (challenge != null) {
            challengeData = challenge.data;
            String requestKey = challenge.key;
            if (Utils.isNotEmpty(requestKey)) {
                challengeRequestKey = requestKey;
            }
            challengeFetchedTime = System.currentTimeMillis();
        }
        return challengeData;
    }

    public static String getUserAgent() {
        return USER_AGENT;
    }

    public record Challenge(String key, String data) {
    }

    public record IntegrityToken(String token, long expirationMs) {
    }

    @Nullable
    public static IntegrityToken getIntegrityToken(@Nullable String botGuardResult) {
        CompletableFuture<IntegrityToken> integrityTokenFuture = CompletableFuture.supplyAsync(() -> fetchIntegrityToken(botGuardResult))
                .thenApply(botGuardResponse -> {
                    if (botGuardResponse != null) {
                        try {
                            long expirationSecond = -1L;
                            int length = botGuardResponse.length();
                            if (length > 1) {
                                expirationSecond = Math.max(botGuardResponse.getLong(1), 7200L);
                            }
                            long expirationMs = System.currentTimeMillis() + ((expirationSecond - 300L) * 1000);

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
        return Utils.isNotEmpty(challengeData) && System.currentTimeMillis()
                - challengeFetchedTime < CHALLENGE_DATA_EXPIRATION_MS;
    }

    @Nullable
    private static Challenge downloadChallenge() {
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