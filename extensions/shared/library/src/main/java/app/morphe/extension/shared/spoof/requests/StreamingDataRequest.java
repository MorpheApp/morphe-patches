package app.morphe.extension.shared.spoof.requests;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.Utils.isNotEmpty;
import static app.morphe.extension.shared.spoof.js.J2V8Support.supportJ2V8;
import static app.morphe.extension.shared.spoof.js.JavaScriptManager.deobfuscateStreamingUrl;
import static app.morphe.extension.shared.spoof.requests.PlayerRoutes.GET_STREAMING_DATA;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.StreamingData;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.spoof.ClientType;

/**
 * Video streaming data.  Fetching is tied to the behavior YT uses,
 * where this class fetches the streams only when YT fetches.
 * <p>
 * Effectively the cache expiration of these fetches is the same as the stock app,
 * since the stock app would not use expired streams and therefor
 * the extension replace stream hook is called only if YT
 * did use its own client streams.
 */
public class StreamingDataRequest {

    private static volatile ClientType[] clientOrderToUse = ClientType.values();

    public static void setClientOrderToUse(List<ClientType> availableClients, ClientType preferredClient) {
        Objects.requireNonNull(preferredClient);

        int availableClientSize = availableClients.size();
        if (!availableClients.contains(preferredClient)) {
            availableClientSize++;
        }

        clientOrderToUse = new ClientType[availableClientSize];
        clientOrderToUse[0] = preferredClient;

        int i = 1;
        for (ClientType c : availableClients) {
            if (c.requireJS && !supportJ2V8()) {
                Logger.printDebug(() -> "Could not find J2V8 runtime. Skipping JavaScript client: " + c.name());
                continue;
            }

            if (c != preferredClient) {
                clientOrderToUse[i++] = c;
            }
        }

        Logger.printDebug(() -> "Available spoof clients: " + Arrays.toString(clientOrderToUse));
    }

    private static final String AUTHORIZATION_HEADER = "Authorization";

    private static final String[] REQUEST_HEADER_KEYS = {
            AUTHORIZATION_HEADER, // Available only to logged-in users.
            "X-GOOG-API-FORMAT-VERSION",
            "X-Goog-Visitor-Id"
    };

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
     * is somehow still referenced.  Each stream is a small array of Strings
     * so memory usage is not a concern.
     */
    private static final Map<String, StreamingDataRequest> cache = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(50));

    private static volatile ClientType lastSpoofedClientType;

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

    private final String videoId;

    private final Future<byte[]> future;

    private StreamingDataRequest(String videoId, Map<String, String> playerHeaders) {
        Objects.requireNonNull(playerHeaders);
        this.videoId = videoId;
        this.future = Utils.submitOnBackgroundThread(() -> fetch(videoId, playerHeaders));
    }

    public static void fetchRequest(String videoId, Map<String, String> fetchHeaders) {
        // Always fetch, even if there is an existing request for the same video.
        cache.put(videoId, new StreamingDataRequest(videoId, fetchHeaders));
    }

    @Nullable
    public static StreamingDataRequest getRequestForVideoId(String videoId) {
        return cache.get(videoId);
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex, boolean showToast) {
        if (showToast) Utils.showToastShort(toastMessage);
        Logger.printInfo(() -> toastMessage, ex);
    }

    private static void handleDebugToast(String toastMessage, ClientType clientType) {
        if (BaseSettings.DEBUG.get() && BaseSettings.DEBUG_TOAST_ON_ERROR.get()) {
            Utils.showToastShort(String.format(toastMessage, clientType));
        }
    }

    @Nullable
    private static HttpURLConnection send(ClientType clientType,
                                          String videoId,
                                          Map<String, String> playerHeaders,
                                          boolean showErrorToasts) {
        Objects.requireNonNull(clientType);
        Objects.requireNonNull(videoId);
        Objects.requireNonNull(playerHeaders);

        final long startTime = System.currentTimeMillis();

        try {
            HttpURLConnection connection = PlayerRoutes.getPlayerResponseConnectionFromRoute(GET_STREAMING_DATA, clientType);
            connection.setConnectTimeout(HTTP_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(HTTP_TIMEOUT_MILLISECONDS);

            boolean authHeadersIncludes = false;
            authHeadersOverrides = false;

            for (String key : REQUEST_HEADER_KEYS) {
                String value = playerHeaders.get(key);

                if (value != null) {
                    if (key.equals(AUTHORIZATION_HEADER)) {
                        if (clientType.supportsOAuth2) {
                            String authorization = OAuth2Requester.getAndUpdateAccessTokenIfNeeded();
                            if (authorization.isEmpty()) {
                                // Access token is empty, the user has not signed in to VR.
                                // YouTube/YouTube Music access tokens cannot be used with YouTube VR.
                                // Do not set the header.
                                Logger.printDebug(() -> "Not including request header: " + key);
                                continue;
                            } else {
                                // Access token is not empty, the user has signed in to VR.
                                // Set the header.
                                value = authorization;
                                authHeadersOverrides = true;
                            }
                        } else if (!clientType.canLogin) {
                            Logger.printDebug(() -> "Not including request header: " + key);
                            continue;
                        }
                        authHeadersIncludes = true;
                    }

                    Logger.printDebug(() -> "Including request header: " + key);
                    connection.setRequestProperty(key, value);
                }
            }

            if (!authHeadersIncludes && clientType.requireLogin) {
                Logger.printDebug(() -> "Skipping client since user is not logged in: " + clientType
                        + " videoId: " + videoId);
                return null;
            }

            Logger.printDebug(() -> "Fetching video streams for: " + videoId + " using client: " + clientType);

            String innerTubeBody = PlayerRoutes.createInnertubeBody(clientType, videoId);
            byte[] requestBody = innerTubeBody.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode == 200) return connection;

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
            Logger.printDebug(() -> "video: " + videoId + " took: " + (System.currentTimeMillis() - startTime) + "ms");
        }

        return null;
    }

    @Nullable
    private static byte[] buildPlayerResponseBuffer(ClientType clientType,
                                                    String videoId,
                                                    HttpURLConnection connection) {
        // gzip encoding doesn't response with content length (-1),
        // but empty response body does.
        if (connection.getContentLength() == 0) {
            handleDebugToast("Debug: Ignoring empty spoof stream client (%s)", clientType);
            return null;
        }

        try (InputStream inputStream = connection.getInputStream()) {
            PlayerResponse playerResponse = PlayerResponse.parseFrom(inputStream);
            var playabilityStatus = playerResponse.getPlayabilityStatus();
            String status = playabilityStatus.getStatus().name();

            if (!"OK".equals(status)) {
                handleDebugToast("Debug: Ignoring unplayable video (%s)", clientType);
                String reason = playabilityStatus.getReason();
                if (isNotEmpty(reason)) {
                    Logger.printDebug(() -> String.format("Debug: Ignoring unplayable video (%s), reason: %s", clientType, reason));
                }

                return null;
            }

            PlayerResponse.Builder responseBuilder = playerResponse.toBuilder();

            if (!playerResponse.hasStreamingData()) {
                handleDebugToast("Debug: Ignoring empty streaming data (%s)", clientType);
                return null;
            }
            StreamingData streamingData = playerResponse.getStreamingData();
            if (streamingData.getAdaptiveFormatsCount() == 0) {
                handleDebugToast("Debug: Ignoring empty adaptiveFormat (%s)", clientType);
                return null;
            }

            if (clientType.requireJS) {
                StreamingData.Builder streamingDataBuilder = streamingData.toBuilder();

                String serverAbrStreamingUrl = streamingData.getServerAbrStreamingUrl();
                if (isNotEmpty(serverAbrStreamingUrl)) {
                    String deobfuscatedAbrUrl = deobfuscateStreamingUrl(
                            videoId,
                            serverAbrStreamingUrl,
                            null
                    );
                    if (isNotEmpty(deobfuscatedAbrUrl)) {
                        streamingDataBuilder.setServerAbrStreamingUrl(deobfuscatedAbrUrl);
                    } else {
                        streamingDataBuilder.setServerAbrStreamingUrl("");
                    }
                }

                streamingDataBuilder.clearFormats();
                for (Format format : streamingData.getFormatsList()) {
                    var newFormat = processFormat(videoId, format);
                    if (newFormat != null) {
                        streamingDataBuilder.addFormats(newFormat);
                    } else {
                        handleDebugToast("Debug: Ignoring failure to deobfuscate in format (%s)", clientType);
                        return null;
                    }
                }

                streamingDataBuilder.clearAdaptiveFormats();
                for (Format format : streamingData.getAdaptiveFormatsList()) {
                    var newFormat = processFormat(videoId, format);
                    if (newFormat != null) {
                        streamingDataBuilder.addAdaptiveFormats(newFormat);
                    } else {
                        handleDebugToast("Debug: Ignoring failure to deobfuscate in adaptiveFormat (%s)", clientType);
                        return null;
                    }
                }

                responseBuilder.setStreamingData(streamingDataBuilder);
            }

            return responseBuilder.build().toByteArray();
        } catch (IOException ex) {
            Logger.printException(() -> "Failed to write player response to buffer array", ex);
            return null;
        }
    }

    @Nullable
    private static Format processFormat(String videoId, Format format) {
        Format.Builder formatBuilder = format.toBuilder();

        String deobfuscatedUrl = deobfuscateStreamingUrl(
                videoId,
                format.getUrl(),
                format.getSignatureCipher()
        );

        if (isNotEmpty(deobfuscatedUrl)) {
            formatBuilder.setUrl(deobfuscatedUrl);
            formatBuilder.clearSignatureCipher();
        } else {
            return null;
        }

        return formatBuilder.build();
    }

    private static byte[] fetch(String videoId, Map<String, String> playerHeaders) {
        final boolean debugEnabled = BaseSettings.DEBUG.get();

        // Retry with different client if empty response body is received.
        int i = 0;
        for (ClientType clientType : clientOrderToUse) {
            // Show an error if the last client type fails, or if debug is enabled then show for all attempts.
            final boolean showErrorToast = (++i == clientOrderToUse.length) || debugEnabled;

            HttpURLConnection connection = send(clientType, videoId, playerHeaders, showErrorToast);
            if (connection != null) {
                byte[] playerResponseBuffer = buildPlayerResponseBuffer(clientType, videoId, connection);

                if (playerResponseBuffer != null) {
                    lastSpoofedClientType = clientType;

                    return playerResponseBuffer;
                }
            }
        }

        lastSpoofedClientType = null;
        handleConnectionError(str("morphe_spoof_video_streams_no_clients_toast"), null, true);

        var preferredClient = clientOrderToUse[0];
        if (preferredClient != ClientType.ANDROID_VR_1_47_48 && preferredClient != ClientType.ANDROID_VR_1_54_20
                && !BaseSettings.OAUTH2_REFRESH_TOKEN.get().isBlank()) {
            handleConnectionError(str("morphe_spoof_video_streams_no_clients_suggest_vr_toast"), null, true);
        }
        return null;
    }

    public boolean fetchCompleted() {
        return future.isDone();
    }

    @Nullable
    public byte[] getStream() {
        try {
            return future.get(MAX_MILLISECONDS_TO_WAIT_FOR_FETCH, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            Logger.printInfo(() -> "getStream timed out", ex);
        } catch (InterruptedException ex) {
            Logger.printException(() -> "getStream interrupted", ex);
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
