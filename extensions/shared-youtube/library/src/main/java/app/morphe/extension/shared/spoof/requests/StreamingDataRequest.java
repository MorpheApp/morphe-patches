/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.spoof.requests;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.ResponseContextOuterClass.*;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.*;
import app.morphe.extension.shared.innertube.ReelItemWatchResponseOuterClass.ReelItemWatchResponse;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.js.JavaScriptEngineSupport;
import app.morphe.extension.shared.spoof.js.JavaScriptManager;

/**
 * Video streaming data. Fetching is tied to the behavior YT uses,
 * where this class fetches the streams only when YT fetches.
 * <p>
 * Effectively the cache expiration of these fetches is the same as the stock app,
 * since the stock app would not use expired streams and therefor
 * the extension replace stream hook is called only if YT
 * did use its own client streams.
 */
public class StreamingDataRequest {

    public record StreamData(byte[] streamingData, @Nullable byte[] playerConfig) {
    }

    private static volatile ClientType[] clientOrderToUse = ClientType.values();

    public static void setClientOrderToUse(List<ClientType> availableClients, ClientType preferredClient) {
        Objects.requireNonNull(preferredClient);

        List<ClientType> orderToUse = new ArrayList<>(availableClients.size());
        orderToUse.add(preferredClient);

        for (ClientType client : availableClients) {
            if (client.requireJS && !JavaScriptEngineSupport.supportsJavaScriptEngine()) {
                Logger.printDebug(() -> "Could not find JavaScript engine. Skipping JavaScript client: " + client.name());
                continue;
            }

            if (client != preferredClient) {
                orderToUse.add(client);
            }
        }

        clientOrderToUse = orderToUse.toArray(new ClientType[0]);
        Logger.printDebug(() -> "Available spoof clients: " + orderToUse);
    }

    private static final String AUTHORIZATION_HEADER = "Authorization"; // Available only to logged-in users.
    private static final String API_FORMAT_VERSION_HEADER = "X-GOOG-API-FORMAT-VERSION";
    private static final String VISITOR_ID_HEADER = "X-Goog-Visitor-Id";

    /**
     * TCP connection and HTTP read timeout.
     */
    private static final int HTTP_TIMEOUT_MILLISECONDS = 10 * 1000;

    /**
     * Any arbitrarily large value, but must be at least twice {@link #HTTP_TIMEOUT_MILLISECONDS}
     */
    private static final int MAX_MILLISECONDS_TO_WAIT_FOR_FETCH = 20 * 1000;

    /**
     * Cache limit must be greater than the maximum number of videos open at once,
     * which theoretically is more than 4 (3 Shorts + one regular minimized video).
     * But instead use a much larger value, to handle if a video viewed a while ago
     * is somehow still referenced. Each stream is a small array of Strings
     * so memory usage is not a concern.
     */
    private static final Map<String, StreamingDataRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private static volatile ClientType lastSpoofedClientType;
    private static volatile boolean fallbackWithTVDash;
    private static volatile Map<String, String> lastPlayerHeaders = Collections.emptyMap();

    /**
     * Used only for stats for nerds to show VR sign-in was used.
     */
    private static volatile boolean authHeadersOverrides;

    public static String getLastSpoofedClientName() {
        ClientType client = lastSpoofedClientType;
        if (client == null) {
            return "Unknown";
        } else {
            String clientName = client.friendlyName;
            if (client.supportsOAuth2 && authHeadersOverrides) {
                clientName += " Signed in";
            }
            return clientName;
        }
    }

    public static boolean getLastSpoofedClientUseSABR() {
        ClientType client = lastSpoofedClientType;
        return client != null && client.requireSABR;
    }

    private final String videoId;

    private final Future<StreamData> future;

    private StreamingDataRequest(String videoId, Map<String, String> playerHeaders) {
        this.videoId = videoId;
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId, playerHeaders));
    }

    public static void fetchRequest(String videoId, Map<String, String> fetchHeaders) {
        // Keep the latest valid player headers so collection downloads can resolve tracks
        // that have not been opened individually.
        if (fetchHeaders != null && !fetchHeaders.isEmpty()) {
            lastPlayerHeaders = new HashMap<>(fetchHeaders);
        }
        // Always fetch, even if there is an existing request for the same video.
        cache.put(videoId, new StreamingDataRequest(videoId, fetchHeaders));
    }

    /** Resolve an arbitrary collection item using the latest YouTube Music player context. */
    public static StreamingDataRequest fetchRequestForDownload(String videoId) {
        StreamingDataRequest request = new StreamingDataRequest(videoId, new HashMap<>(lastPlayerHeaders));
        cache.put(videoId, request);
        return request;
    }

    @Nullable
    public static StreamingDataRequest getRequestForVideoId(String videoId) {
        return cache.get(videoId);
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex, boolean showToast) {
        if (showToast) Utils.showToastShort(toastMessage);
        Logger.printInfo(() -> toastMessage, ex);
    }

    private static void handleDebugToast(String toastMessage,
                                         ClientType clientType) {
        if (BaseSettings.DEBUG.get() && BaseSettings.DEBUG_TOAST_ON_ERROR.get()) {
            Utils.showToastShort(String.format(toastMessage, clientType));
        }
    }

    @Nullable
    private static HttpURLConnection send(ClientType clientType,
                                          String videoId,
                                          Map<String, String> playerHeaders,
                                          boolean showErrorToasts) {
        Utils.verifyOffMainThread();

        Objects.requireNonNull(clientType);
        Objects.requireNonNull(videoId);
        Objects.requireNonNull(playerHeaders);

        final long startTime = System.currentTimeMillis();

        try {
            HttpURLConnection connection = PlayerRoutes.getPlayerResponseConnectionFromRoute(clientType);
            connection.setConnectTimeout(HTTP_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(HTTP_TIMEOUT_MILLISECONDS);

            authHeadersOverrides = false;

            String authorization = playerHeaders.get(AUTHORIZATION_HEADER);
            boolean authHeadersIncludes = Utils.isNotEmpty(authorization);

            // Auth header is required, but the user is not logged in. These clients are skipped:
            // ANDROID_CREATOR, TV_SIMPLY, ANDROID_MUSIC_REEL, ANDROID_MUSIC_NO_SDK.
            if (clientType.canLogin && clientType.requireLogin && !authHeadersIncludes) {
                Logger.printDebug(() -> "Skipping client since user is not logged in: " + clientType
                        + ", videoId: " + videoId);
                return null;
            }
            // If the Bearer token is compatible and the user is logged in, the header is set:
            // ANDROID_CREATOR, ANDROID_MUSIC_REEL, ANDROID_MUSIC_NO_SDK, TV_SABR, TV_SIMPLY.
            else if (clientType.canLogin && authHeadersIncludes) {
                connection.setRequestProperty(AUTHORIZATION_HEADER, authorization);
                Logger.printDebug(() -> "Set auth header: " + clientType + ", videoId: " + videoId);
            }
            // If oauth2 login is supported and the user is logged in via oauth2 flow, the header is set:
            // ANDROID_VR (ANDROID_XR).
            else if (clientType.supportsOAuth2 && authHeadersIncludes) {
                String oauth2Authorization = OAuth2Requester.getAndUpdateAccessTokenIfNeeded();
                if (Utils.isNotEmpty(oauth2Authorization)) {
                    authHeadersOverrides = true;
                    connection.setRequestProperty(AUTHORIZATION_HEADER, oauth2Authorization);
                    Logger.printDebug(() -> "Set oauth2 auth header: " + clientType + ", videoId: " + videoId);
                }
            }
            // These clients can play videos without the auth header:
            // ANDROID_VR (ANDROID_XR), TV_SABR, VISIONOS_1_02 (VISIONOS_1_03).
            else {
                Logger.printDebug(() -> "Do not set auth header: " + clientType + ", videoId: " + videoId);
            }

            Logger.printDebug(() -> "Fetching video stream for: " + videoId + " using client: " + clientType);

            // Using the same visitorId across multiple clients increases the bot score.
            // To prevent this, each client uses a different visitorId.
            // See: https://github.com/MorpheApp/morphe-patches/issues/2283.
            String visitorId = VisitorIdRequester.getVisitorId(clientType);
            if (Utils.isNotEmpty(visitorId)) {
                connection.setRequestProperty(VISITOR_ID_HEADER, visitorId);
            } else {
                // A few requests without visitorId are okay, but if repeated excessively, increase the bot score.
                Logger.printDebug(() -> "Do not set visitorId: " + clientType + ", videoId: " + videoId);
            }

            // Only 'X-GOOG-API-FORMAT-VERSION = 2' can have a proto response.
            connection.setRequestProperty(API_FORMAT_VERSION_HEADER, "2");

            String innerTubeBody = PlayerRoutes.createInnertubeBody(clientType, videoId, visitorId);
            byte[] requestBody = innerTubeBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) return connection;

            // This situation likely means the patches are outdated.
            // Use a toast message that suggests updating.
            handleConnectionError("Playback error (App is outdated?) " + clientType + ": "
                            + responseCode + " response: " + connection.getResponseMessage(),
                    null, showErrorToasts);
        } catch (SocketTimeoutException ex) {
            handleConnectionError("Connection timeout", ex, showErrorToasts);
        } catch (IOException ex) {
            handleConnectionError("Network error", ex, showErrorToasts);
        } catch (Exception ex) {
            Logger.printException(() -> "send failed", ex);
        } finally {
            Logger.printDebug(() -> "video: " + videoId + " took: "
                    + (System.currentTimeMillis() - startTime) + "ms");
        }

        return null;
    }

    @Nullable
    private static StreamData buildPlayerResponseBuffer(ClientType clientType,
                                                        HttpURLConnection connection) {
        if (connection == null) {
            return null;
        }
        // gzip encoding doesn't response with content length (-1),
        // but empty response body does.
        if (connection.getContentLength() == 0) {
            handleDebugToast("Debug: Ignoring empty spoof stream client (%s)", clientType);
            return null;
        }

        try (InputStream inputStream = connection.getInputStream()) {
            PlayerResponse playerResponse;

            if (clientType.usePlayerEndpoint) {
                playerResponse = PlayerResponse.parseFrom(inputStream);
                VisitorIdRequester.updateVisitorIdIfNeed(clientType, playerResponse.getResponseContext().getVisitorData());
            } else {
                ReelItemWatchResponse reelItemWatchResponse = ReelItemWatchResponse.parseFrom(inputStream);
                VisitorIdRequester.updateVisitorIdIfNeed(clientType, reelItemWatchResponse.getResponseContext().getVisitorData());
                playerResponse = reelItemWatchResponse.getPlayerResponse();
            }
            PlayabilityStatus playabilityStatus = playerResponse.getPlayabilityStatus();

            String status = playabilityStatus.getStatus().name();
            if (!"OK".equals(status)) {
                handleDebugToast("Debug: Ignoring unplayable video (%s)", clientType);
                String reason = playabilityStatus.getReason();
                if (Utils.isNotEmpty(reason)) {
                    Logger.printDebug(() -> String.format(
                            "Debug: Ignoring unplayable video (%s), reason: %s", clientType, reason));
                }

                return null;
            }

            PlayerResponse.Builder responseBuilder = playerResponse.toBuilder();
            if (!playerResponse.hasStreamingData()) {
                handleDebugToast("Debug: Ignoring empty streaming data (%s)", clientType);
                return null;
            }

            // Android Studio only supports the HLS protocol for live streams.
            // HLS protocol can theoretically be played with ExoPlayer,
            // but the related code has not yet been implemented.
            // If DASH protocol is not available, the client will be skipped.
            StreamingData streamingData = playerResponse.getStreamingData();
            if (streamingData.getAdaptiveFormatsCount() == 0) {
                handleDebugToast("Debug: Ignoring empty adaptiveFormat (%s)", clientType);
                return null;
            }

            // In YouTube 20.21.37, manifestless livestreams cannot be played using the SABR protocol, or there are playback issues.
            // Until code to assemble the manifestUrl is implemented or code to override the exoPlayerConfig is ready,
            // TV SABR clients in livestreams will be temporarily fallbacked to TV DASH clients.
            //
            // TODO: Override other playerConfigs such as exoPlayerConfig.
            if (clientType.requireSABR && clientType == ClientType.TV_SABR
                    && Utils.containsAny(streamingData.getServerAbrStreamingUrl(), "yt_live_broadcast", "yt_premiere_broadcast")) {
                Logger.printDebug(() -> "Livestream detected, fallback to TV dash");
                fallbackWithTVDash = true;
                return null;
            }

            if (clientType.requireJS) {
                StreamingData.Builder deobfuscatedStreamingDataBuilder =
                        JavaScriptManager.getDeobfuscatedStreamingData(streamingData, clientType.requireSABR);
                if (deobfuscatedStreamingDataBuilder == null) {
                    handleDebugToast("Debug: Ignoring obfuscated streamingData (%s)", clientType);
                    return null;
                }
                responseBuilder.setStreamingData(deobfuscatedStreamingDataBuilder);
            }

            byte[] streamingDataBuffer = responseBuilder.build().toByteArray();
            byte[] playerConfigBuffer = clientType.requireSABR && playerResponse.hasPlayerConfig()
                    ? playerResponse.getPlayerConfig().toByteArray()
                    : null;

            return new StreamData(streamingDataBuffer, playerConfigBuffer);
        } catch (IOException ex) {
            Logger.printException(() -> "Failed to write player response for video stream", ex);
            return null;
        }
    }

    private static boolean skipClient(ClientType client) {
        if (client.requireJS && !JavaScriptEngineSupport.supportsJavaScriptEngine()) {
            Logger.printDebug(() -> "Skipping JavaScript client: " + client.name());
            return true;
        }
        return false;
    }

    private static StreamData fetch(String videoId, @Nullable Map<String, String> playerHeaders) {
        final boolean debugEnabled = BaseSettings.DEBUG.get();
        final long fetchStartTime = System.currentTimeMillis();

        // Retry with different client if empty response body is received.
        int i = 0;
        for (ClientType clientType : clientOrderToUse) {
            if (skipClient(clientType)) {
                continue;
            }

            // Show an error if the last client type fails, or if debug is enabled then show for all attempts.
            final boolean showErrorToast = (++i == clientOrderToUse.length) || debugEnabled;

            HttpURLConnection connection = send(clientType, videoId, playerHeaders, showErrorToast);
            StreamData streamingData = buildPlayerResponseBuffer(clientType, connection);

            if (clientType == ClientType.TV_SABR && fallbackWithTVDash) {
                fallbackWithTVDash = false;
                clientType = ClientType.TV_DASH;
                HttpURLConnection fallBackConnection = send(clientType, videoId, playerHeaders, showErrorToast);
                streamingData = buildPlayerResponseBuffer(clientType, fallBackConnection);
            }

            if (streamingData != null) {
                lastSpoofedClientType = clientType;

                if (clientType.requireJS) {
                    Logger.printDebug(() -> "End of fetch for JavaScript required client" +
                            ", video: " + videoId +
                            ", hash: " + JavaScriptManager.getJavaScriptHash() +
                            ", variant: " + JavaScriptManager.getJavaScriptVariant() +
                            ", took: " + (System.currentTimeMillis() - fetchStartTime) + "ms");
                }

                return streamingData;
            }
        }

        lastSpoofedClientType = null;
        handleConnectionError(str("morphe_spoof_video_streams_no_clients_toast"), null, true);

        ClientType preferredClient = clientOrderToUse[0];
        if (!preferredClient.supportsOAuth2 && !SharedYouTubeSettings.OAUTH2_REFRESH_TOKEN.get().isBlank()) {
            handleConnectionError(str("morphe_spoof_video_streams_no_clients_suggest_vr_toast"), null, true);
        }

        return null;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean fetchIsDone() {
        return future.isDone();
    }

    @Nullable
    public StreamData getStream() {
        try {
            // This hook is always called off the main thread,
            // but this can later be called for the same video ID from the main thread.
            // This is not a concern, since the fetch will always be finished
            // and never block the main thread.
            // But if debugging, then still verify this is the situation.
            if (BaseSettings.DEBUG.get() && !fetchIsDone() && Utils.isCurrentlyOnMainThread()) {
                Logger.printException(() -> "Debug: Blocking main thread");
            }
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getStream timed out", ex);
            future.cancel(true);
        } catch (CancellationException ex) {
            Logger.printInfo(() -> "getStream was previously cancelled");
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getStream interrupted", ex);
            future.cancel(true);
            Thread.currentThread().interrupt(); // Restore interrupt status flag.
        } catch (ExecutionException ex) {
            Logger.printException(() -> "getStream failure", ex);
        }

        return null;
    }

    @NonNull
    @Override
    public String toString() {
        return "StreamingDataRequest{" + "videoId='" + videoId + '\'' + '}';
    }
}
