/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.spoof;

import static app.morphe.extension.shared.patches.AppCheckPatch.IS_YOUTUBE_MUSIC;

import android.net.Uri;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.lang.ref.WeakReference;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.SavedVideo.OfflineStateAction;
import app.morphe.extension.shared.innertube.SavedVideo.SavedPlayerResponse;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.patches.MediaHttpRequest;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

@SuppressWarnings("unused")
public class SpoofVideoStreamsPatch {

    public static final class JavaScriptClientAvailability implements Setting.Availability {
        @Override
        public boolean isAvailable() {
            return SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get() && preferredClient.requireJS;
        }

        @Override
        public List<Setting<?>> getParentSettings() {
            return List.of(SharedYouTubeSettings.SPOOF_VIDEO_STREAMS);
        }
    }

    private static final boolean SPOOF_VIDEO_STREAMS = isPatchIncluded()
            && SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get();

    @NonNull
    private static volatile Locale localeOverride = AppLanguage.DEFAULT.getLocale();

    private static volatile ClientType preferredClient = ClientType.VISIONOS_1_02;

    private static final String INNERTUBE_PATH = "/youtubei/";

    /**
     * Path of the request that loads a video, which holds a player request and a watch next request.
     */
    private static final String WATCH_REQUEST_PATH = "get_watch";

    /**
     * Path of the request for what is shown below the player. The app sends it when it loads a video,
     * also when the player response comes with a request that is not seen, such as the Onesie request.
     */
    private static final String WATCH_NEXT_REQUEST_PATH = "/next";

    /**
     * Path of the request that loads a Short, which holds a player request.
     */
    private static final String REEL_WATCH_REQUEST_PATH = "reel_item_watch";

    private static final String STREAM_URL_PATH = "/videoplayback";

    private static final String STREAM_URL_HOST_SUFFIX = ".googlevideo.com";

    /**
     * Client name of the app itself, whose stream requests are left as they are.
     */
    private static final String APP_CLIENT_NAME = IS_YOUTUBE_MUSIC ? "ANDROID_MUSIC" : "ANDROID";

    /**
     * Largest byte range of a plain stream request. The server sends larger ranges at about playback speed.
     */
    private static final long MAX_STREAM_RANGE_LENGTH = 10 * 1024 * 1024;

    /**
     * If the next network request built on the thread is opened through a priority data source.
     */
    private static final ThreadLocal<Boolean> priorityDataSourceOpenOnThread = ThreadLocal.withInitial(() -> false);

    /**
     * If the last request built on the thread is of a shortened stream range.
     */
    private static final ThreadLocal<Boolean> streamRangeLimitedOnThread = ThreadLocal.withInitial(() -> false);

    /**
     * UMP part type of a SABR error. The server can send it instead of the media of a plain stream range.
     */
    private static final byte UMP_PART_SABR_ERROR = 44;

    /**
     * Response of the last plain stream request built on the thread. A request is built and read on the same thread.
     */
    private static final class StreamResponse {
        /**
         * If the response is a UMP response of a plain stream, and its first part is not read yet.
         */
        boolean checkFirstPart;
        /**
         * Position of the buffer when the current read of the response started.
         */
        int readStart;
        /**
         * If the response starts with a SABR error.
         */
        boolean sabrError;
    }

    private static final ThreadLocal<StreamResponse> streamResponseOnThread = ThreadLocal.withInitial(StreamResponse::new);

    /**
     * If the streams of the player responses created on this thread are kept.
     * Used for videos that are saved, which use streams that the app can save instead of the playback streams.
     */
    private static final ThreadLocal<Boolean> keepStreamsOnThread = ThreadLocal.withInitial(() -> false);

    /**
     * @return If this patch was included during patching.
     */
    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    @NonNull
    public static Locale getLocaleOverride() {
        return localeOverride;
    }

    /**
     * @param locale Locale override for non-authenticated requests.
     */
    public static void setLocaleOverride(@Nullable Locale locale) {
        if (locale != null) {
            localeOverride = locale;
        }
    }

    public static void setClientsToUse(List<ClientType> availableClients, ClientType client) {
        preferredClient = Objects.requireNonNull(client);

        StreamingDataRequest.setClientOrderToUse(availableClients, client);

        if (SPOOF_VIDEO_STREAMS) {
            Utils.runOnBackgroundThread(StreamingDataRequest::prepareClients);
        }
    }

    public static ClientType getPreferredClient() {
        return preferredClient;
    }

    /**
     * @param keepStreams If the streams of the player responses created on the current thread are not overridden.
     */
    public static void setKeepStreamsOnThread(boolean keepStreams) {
        keepStreamsOnThread.set(keepStreams);
    }

    public static boolean spoofingToClientWithNoMultiAudioStreams() {
        return SPOOF_VIDEO_STREAMS && !preferredClient.supportsMultiAudioTracks;
    }

    public static boolean spoofingToClientWithSABROrSpoofingDisabled() {
        return !SPOOF_VIDEO_STREAMS || preferredClient.requireSABR;
    }

    /**
     * Supplies the length of the video the streams are really of, when it is not the video the
     * app thinks it is playing.
     */
    public interface VideoLengthResolver {
        /**
         * @return Length in seconds, or zero to keep the length of the app.
         */
        long getVideoLengthSeconds(@NonNull String videoId);
    }

    @Nullable
    private static volatile VideoLengthResolver videoLengthResolver;

    public static void setVideoLengthResolver(@Nullable VideoLengthResolver resolver) {
        videoLengthResolver = resolver;
    }

    /**
     * Injection point.
     * The app takes the length of the track from the response of the video it asked for, which is
     * not the video the streams are of when another one is served in its place.
     *
     * @return Length in seconds, or zero to keep the length of the app.
     */
    public static long getVideoLengthSeconds(String videoId) {
        try {
            VideoLengthResolver resolver = videoLengthResolver;
            if (resolver == null || videoId == null) return 0;

            final long lengthSeconds = resolver.getVideoLengthSeconds(videoId);
            if (lengthSeconds > 0) {
                Logger.printDebug(() -> "Overriding video length of: " + videoId
                        + " with: " + lengthSeconds + " seconds");
            }
            return lengthSeconds;
        } catch (Exception ex) {
            Logger.printException(() -> "getVideoLengthSeconds failure", ex);
            return 0;
        }
    }

    private record OfflineCheck(WeakReference<Object> playerProto, boolean offline) {}

    /**
     * Number of recently checked player responses to remember.
     * Inline feed playback can check several responses before checking the first one again.
     */
    private static final int OFFLINE_CHECKS_TO_REMEMBER = 16;

    /**
     * Recently checked player responses and their results, newest first. The app can create the
     * player response model again from a response that was already checked. Checking serializes
     * the player response, which caches its size, and patch_setStreamingData then sets the player
     * config of that same response. Serializing it again fails because the cached size no longer
     * matches. Every response patch_setStreamingData changes was checked first, so it is in here.
     */
    private static final ArrayDeque<OfflineCheck> offlineChecks = new ArrayDeque<>(OFFLINE_CHECKS_TO_REMEMBER);

    /**
     * @return If the player response is of a video that is saved and can be played,
     *         such as a video the app is saving. Its streams are the streams the app saves.
     */
    private static boolean isOfflinePlayerResponse(@NonNull String videoId, @Nullable Object playerProto) {
        try {
            if (!(playerProto instanceof MessageLite message)) {
                return false;
            }

            synchronized (offlineChecks) {
                for (OfflineCheck check : offlineChecks) {
                    if (check.playerProto().get() == playerProto) {
                        return check.offline();
                    }
                }
            }

            SavedPlayerResponse response = SavedPlayerResponse.parseFrom(message.toByteArray());
            final boolean offline = response.hasOfflineState()
                    && response.getOfflineState().getAction() == OfflineStateAction.OFFLINE_STATE_ACTION_OK;
            if (response.hasOfflineState() && !offline) {
                Logger.printDebug(() -> "Offline state action is not OK: "
                        + response.getOfflineState().getActionValue() + " for: " + videoId);
            }
            synchronized (offlineChecks) {
                offlineChecks.removeIf(check -> check.playerProto().get() == null);
                if (offlineChecks.size() >= OFFLINE_CHECKS_TO_REMEMBER) {
                    offlineChecks.removeLast();
                }
                offlineChecks.addFirst(new OfflineCheck(new WeakReference<>(playerProto), offline));
            }
            return offline;
        } catch (Exception ex) {
            Logger.printException(() -> "isOfflinePlayerResponse failure", ex);
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean isSpoofingEnabled() {
        return SPOOF_VIDEO_STREAMS;
    }

    /**
     * Injection point.
     * Only invoked when playing a live stream on an Apple client.
     */
    public static boolean fixHLSCurrentTime(boolean original) {
        if (SPOOF_VIDEO_STREAMS) {
            return false;
        }
        return original;
    }

    /**
     * Injection point.
     * Called when the player is set up for a video, after its streams were given by {@link #getStreamingData}.
     * Fix audio stuttering in YouTube Music.
     */
    public static boolean disableSABR(@Nullable String videoId) {
        if (!SPOOF_VIDEO_STREAMS) {
            return false;
        }
        final boolean useSABR = StreamingDataRequest.playerClientUsesSABR(videoId);
        Logger.printDebug(() -> "Player uses SABR: " + useSABR + " for: " + videoId);
        return !useSABR;
    }

    /**
     * @return If SABR is turned off for the streams last given to the player.
     */
    public static boolean disableSABR() {
        return SPOOF_VIDEO_STREAMS && !StreamingDataRequest.getLastSpoofedClientUseSABR();
    }

    /**
     * Injection point.
     * Turns off a feature flag that interferes with video playback.
     */
    public static boolean usePlaybackStartFeatureFlag(boolean original) {
        if (original) {
            Logger.printDebug(() -> "usePlaybackStartFeatureFlag is set on");
        }

        if (SPOOF_VIDEO_STREAMS) {
            return false;
        }
        return original;
    }

    /**
     * Injection point.
     * Turns off a feature flag that interferes with video playback.
     */
    public static boolean useReelItemWatchResponseFeatureFlag(boolean original) {
        if (original) {
            Logger.printDebug(() -> "useReelItemWatchResponse is set on");
        }

        if (SPOOF_VIDEO_STREAMS) {
            return false;
        }
        return original;
    }

    /**
     * Injection point.
     * Turns off a feature flag that interferes with video playback.
     */
    public static boolean useMediaSessionFeatureFlag(boolean original) {
        if (original) {
            Logger.printDebug(() -> "useMediaSessionFeatureFlag is set on");
        }

        if (SPOOF_VIDEO_STREAMS) {
            return false;
        }
        return original;
    }

    /**
     * Injection point.
     * Starts fetching the streams of a video when the app requests the video, so both requests run at the same time.
     * The app can also load a video with a request that is not seen here, and then the streams are fetched
     * when the app builds the streams of the video.
     *
     * @param body Body of the request, or null if it has none.
     */
    public static void fetchStreams(String url, Map<String, String> requestHeaders, @Nullable byte[] body) {
        if (SPOOF_VIDEO_STREAMS) {
            try {
                if (!Utils.isNetworkConnected()) {
                    return;
                }

                Uri uri = Uri.parse(url);
                String path = uri.getPath();
                if (path == null || !path.startsWith(INNERTUBE_PATH)) {
                    return;
                }
                StreamingDataRequest.setAppHeaders(requestHeaders);

                // The app can also send a player request for the same video,
                // so these start a fetch only if there is none that can still be used.
                final boolean isReelWatchRequest = path.contains(REEL_WATCH_REQUEST_PATH);
                if (isReelWatchRequest || path.endsWith(WATCH_NEXT_REQUEST_PATH)) {
                    // Requests for more comments or related videos have no video id.
                    String id = body == null ? null : InnerTubeRequestBody.readVideoId(body, isReelWatchRequest);
                    if (id != null) {
                        StreamingDataRequest.fetchRequestIfNeeded(id);
                    }
                    return;
                }

                final boolean isWatchRequest = path.contains(WATCH_REQUEST_PATH);
                if (!isWatchRequest && !path.contains("player")) {
                    return;
                }

                // 'get_drm_license' has no video ID and appears to happen when waiting for a paid video to start.
                // 'heartbeat' has no video and appears to be only after playback has started.
                // 'refresh' has no video ID and appears to happen when waiting for a live stream to start.
                // 'ad_break' has no video ID.
                if (path.contains("get_drm_license") ||
                        path.contains("heartbeat") ||
                        path.contains("refresh") ||
                        path.contains("ad_break")) {
                    Logger.printDebug(() -> "Ignoring path: " + path);
                    return;
                }

                String urlId = uri.getQueryParameter("id");
                // The url of a watch request has no video id.
                final String id = urlId == null && body != null
                        ? InnerTubeRequestBody.readVideoId(body, isWatchRequest)
                        : urlId;
                if (id == null) {
                    Logger.printException(() -> "Ignoring request with no ID: " + url);
                    return;
                }

                StreamingDataRequest.fetchRequest(id, requestHeaders);
            } catch (Exception ex) {
                Logger.printException(() -> "fetchStreams failure", ex);
            }
        }
    }

    /**
     * Injection point.
     * Fix playback by replace the streaming data.
     * Called after {@link #fetchStreams(String, Map, byte[])}, or for a video whose request was not seen there.
     */
    @Nullable
    public static byte[] getStreamingData(String videoId) {
        return getStreamingData(videoId, null);
    }

    @Nullable
    public static byte[] getStreamingData(String videoId, @Nullable Object playerProto) {
        if (SPOOF_VIDEO_STREAMS) {
            if (Boolean.TRUE.equals(keepStreamsOnThread.get())) {
                Logger.printDebug(() -> "Keeping the streams of the player response: " + videoId);
                return null;
            }

            if (isOfflinePlayerResponse(videoId, playerProto)) {
                Logger.printDebug(() -> "Not overriding streaming data (saved offline video): " + videoId);
                StreamingDataRequest.setPlayerClient(videoId, null);
                return null;
            }

            // Without a connection the replacement streams cannot load,
            // and a video that is saved plays with the streams of its own player response.
            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Not overriding streaming data (network is not connected): " + videoId);
                StreamingDataRequest.setPlayerClient(videoId, null);
                return null;
            }

            try {
                var buffers = StreamingDataRequest.getOrFetchRequest(videoId).getStream();
                // The player chooses SABR from the client of the streams it is given for this video.
                StreamingDataRequest.setPlayerClient(videoId, buffers == null ? null : buffers.client());
                if (buffers != null) {
                    byte[] stream = buffers.streamingData();
                    Logger.printDebug(() -> "Overriding video stream: " + videoId
                            + " with client: " + buffers.client());
                    return stream;
                }

                Logger.printDebug(() -> "Not overriding streaming data (video stream is null): " + videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "getStreamingData failure", ex);
            }
        }

        return null;
    }

    /**
     * Injection point.
     * Called when a media response asks the player to load the player response of its video again.
     * The app then loads the video again, which can be with requests that are not seen,
     * and the streams fetched before are not given to the player again.
     */
    public static void onReloadPlayerResponse() {
        if (SPOOF_VIDEO_STREAMS) {
            Logger.printDebug(() -> "Player response reload requested");
            StreamingDataRequest.onPlayerResponseReloadRequested();
        }
    }

    /**
     * Injection point.
     * Fix playback by replace the player config.
     * Called after {@link #getStreamingData(String)}.
     *
     * @param appPlayerConfig The player config of the response of the app, or null if it has none.
     */
    @Nullable
    public static byte[] getPlayerConfig(String videoId, @Nullable byte[] appPlayerConfig) {
        if (SPOOF_VIDEO_STREAMS) {
            try {
                StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
                if (request != null) {
                    var buffers = request.getStream();
                    if (buffers != null) {
                        byte[] config = buffers.playerConfig();
                        if (config != null) {
                            Logger.printDebug(() -> "Overriding player config: " + videoId);
                            // This config replaces the whole config of the app, which tells how the
                            // video is played inline, such as a muted preview in the feed, and how it is buffered.
                            return appPlayerConfig != null
                                    ? StreamingDataRequest.addAppPlayerConfig(config, appPlayerConfig, buffers.hasAndroidMedia())
                                    : config;
                        }
                    }
                }

                Logger.printDebug(() -> "Not overriding player config: " + videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "getPlayerConfig failure", ex);
            }
        }

        return null;
    }

    /**
     * Injection point.
     * Called after {@link #getPlayerConfig(String, byte[])}.
     */
    public static boolean hasAndroidMedia(String videoId) {
        if (SPOOF_VIDEO_STREAMS) {
            try {
                StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
                if (request != null) {
                    var buffers = request.getStream();
                    if (buffers != null) {
                        return buffers.hasAndroidMedia();
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "hasAndroidMedia failure", ex);
            }
        }

        return false;
    }

    /**
     * Injection point.
     * Called after {@link #getStreamingData(String)}.
     */
    @Nullable
    public static byte[] removeVideoPlaybackPostBody(Uri uri, int method, byte[] postData) {
        if (SPOOF_VIDEO_STREAMS) {
            try {
                final int methodPost = 2;
                if (method == methodPost) {
                    String path = uri.getPath();
                    if (path != null && path.contains("videoplayback")) {
                        return null;
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "removeVideoPlaybackPostBody failure", ex);
            }
        }

        return postData;
    }

    /**
     * Injection point.
     * <p>
     * Called off the main thread when native code starts a media request.
     * The app makes the requests of SABR streams itself, and names itself and the device it runs on,
     * even when the streams are from a TV client. These requests name the TV client instead.
     *
     * @param callbacks Native callbacks of the request, which are also given the response.
     * @return The request to start.
     */
    public static MediaHttpRequest onMediaRequest(MediaHttpRequest request, Object callbacks) {
        if (!SPOOF_VIDEO_STREAMS) {
            return request;
        }
        try {
            String url = request.patch_getUrl();
            if (url == null || !url.contains(STREAM_URL_PATH)) {
                return request;
            }
            Uri uri = Uri.parse(url);
            if (!STREAM_URL_PATH.equals(uri.getPath()) || uri.getQueryParameter("sabr") == null) {
                return request;
            }

            String urlClient = uri.getQueryParameter("c");
            MediaHttpRequest requestToStart = request;
            byte[] body = request.patch_getBody();
            // The other TV clients that use SABR are the same client on another device.
            if (body != null && ClientType.TV_SABR.clientName.equals(urlClient)) {
                byte[] clientInfo = SabrRequestBody.buildClientInfo(ClientType.TV_SABR, getLocaleOverride());
                requestToStart = request.patch_withBody(SabrRequestBody.replaceClientInfo(body, clientInfo));
            }

            if (SabrMediaLogger.isEnabled()) {
                SabrMediaLogger.onRequest(urlClient, requestToStart.patch_getBody(), callbacks);
            }
            return requestToStart;
        } catch (Exception ex) {
            Logger.printException(() -> "onMediaRequest failure", ex);
            return request;
        }
    }

    /**
     * Injection point.
     * Called with each chunk of a media response, before native code reads it.
     */
    public static void onMediaResponseChunk(Object callbacks, @Nullable ByteBuffer chunk) {
        if (!SPOOF_VIDEO_STREAMS || chunk == null) {
            return;
        }
        try {
            SabrMediaLogger.onResponseChunk(callbacks, chunk);
        } catch (Exception ex) {
            Logger.printException(() -> "onMediaResponseChunk failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Called for every network request the app makes, before the request is built.
     * The app requests streams with a POST body made for its own client. The plain stream urls of the
     * other clients do not accept that body, and the server answers with an error or sends the stream slowly.
     *
     * @param url Url of the request.
     * @return If the request is for a plain stream url of another client, and must be sent as a GET request without a body.
     */
    public static boolean isPlainStreamRequest(@Nullable String url) {
        try {
            // Saved videos use plain streams of other clients, even if spoofing is off.
            if ((!SPOOF_VIDEO_STREAMS && SharedYouTubeSettings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP)
                    || url == null || !url.contains(STREAM_URL_PATH)) {
                return false;
            }

            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            if (host == null || !host.endsWith(STREAM_URL_HOST_SUFFIX) || !STREAM_URL_PATH.equals(uri.getPath())
                    // Streams of server side adaptive bitrate (SABR) clients are not plain urls.
                    || uri.getQueryParameter("sabr") != null) {
                return false;
            }

            String clientName = uri.getQueryParameter("c");
            if (clientName == null || APP_CLIENT_NAME.equals(clientName)) {
                return false;
            }
            for (ClientType client : ClientType.values()) {
                if (!client.requireSABR && client.clientName.equals(clientName)) {
                    return true;
                }
            }
            return false;
        } catch (Exception ex) {
            Logger.printException(() -> "isPlainStreamRequest failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when a priority data source opens the data source it wraps, which builds its request on the same thread.
     * Only saved videos are transferred through a priority data source.
     */
    public static void onPriorityDataSourceOpen() {
        priorityDataSourceOpenOnThread.set(true);
    }

    /**
     * Injection point.
     * <p>
     * Called for every network request the app makes, when the request is built.
     * Saving a video requests the rest of a stream with a single range, but the server sends a large range
     * at about playback speed. The range is shortened, and {@link #isLimitedStreamRangeFinished(long)}
     * makes the app request the following range after this one ends.
     * Only ranges opened by {@link #onPriorityDataSourceOpen()} are shortened. Playback takes the pause
     * of a transfer as an error of the stream.
     * Ranges without an end are left as they are, because the app reads those until the stream ends.
     * The first part of the response is checked by {@link #onStreamResponseRead(ByteBuffer)}.
     *
     * @param url Url of the request.
     * @return The url to request.
     */
    @Nullable
    public static String limitPlainStreamRange(@Nullable String url) {
        try {
            // A request is built and read on the same thread.
            final boolean priorityDataSource = Boolean.TRUE.equals(priorityDataSourceOpenOnThread.get());
            priorityDataSourceOpenOnThread.set(false);
            streamRangeLimitedOnThread.set(false);
            StreamResponse response = Objects.requireNonNull(streamResponseOnThread.get());
            response.checkFirstPart = false;
            response.sabrError = false;
            if (url == null || !isPlainStreamRequest(url)) {
                return url;
            }

            Uri uri = Uri.parse(url);
            response.checkFirstPart = "1".equals(uri.getQueryParameter("ump"));
            if (!priorityDataSource) {
                return url;
            }

            String range = uri.getQueryParameter("range");
            if (range == null) {
                return url;
            }
            final int separatorIndex = range.indexOf('-');
            if (separatorIndex <= 0 || separatorIndex == range.length() - 1) {
                return url;
            }

            final long start = Long.parseLong(range.substring(0, separatorIndex));
            final long end = Long.parseLong(range.substring(separatorIndex + 1));
            final long limitedEnd = start + MAX_STREAM_RANGE_LENGTH - 1;
            if (end <= limitedEnd) {
                return url;
            }

            String rangeParameter = "range=" + range;
            final int rangeIndex = url.indexOf(rangeParameter);
            final int rangeEndIndex = rangeIndex + rangeParameter.length();
            if (rangeIndex <= 0 || (url.charAt(rangeIndex - 1) != '&' && url.charAt(rangeIndex - 1) != '?')
                    || (rangeEndIndex < url.length() && url.charAt(rangeEndIndex) != '&')) {
                return url;
            }

            String limitedRange = start + "-" + limitedEnd;
            Logger.printDebug(() -> "Limiting stream range: " + range + " to: " + limitedRange);
            streamRangeLimitedOnThread.set(true);
            return url.substring(0, rangeIndex) + "range=" + limitedRange + url.substring(rangeEndIndex);
        } catch (Exception ex) {
            Logger.printException(() -> "limitPlainStreamRange failure", ex);
            return url;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when the response of a network request is read.
     * A response of a shortened range ends before the requested range does, and the app takes that
     * as the end of the stream. If this is true, the app pauses the transfer instead, and continues it
     * from the last byte it got.
     *
     * @param remainingBytes Bytes of the response that are not read yet.
     * @return If the response of a shortened range has ended, and the rest of the range must be requested.
     */
    public static boolean isLimitedStreamRangeFinished(long remainingBytes) {
        if (remainingBytes != 0 || !Boolean.TRUE.equals(streamRangeLimitedOnThread.get())) {
            return false;
        }

        streamRangeLimitedOnThread.set(false);
        Logger.printDebug(() -> "Requesting the rest of the shortened stream range");
        return true;
    }

    /**
     * Injection point.
     * Called before the response of a network request is read into the buffer.
     */
    public static void onStreamResponseReadStart(ByteBuffer buffer) {
        StreamResponse response = Objects.requireNonNull(streamResponseOnThread.get());
        if (response.checkFirstPart) {
            response.readStart = buffer.position();
        }
    }

    /**
     * Injection point.
     * Called after the response of a network request is read into the buffer.
     * The server can answer a plain stream range with only a SABR error and no media, for seconds
     * or minutes. The app ignores the error, finds no media, and requests the same range again right
     * away, over and over, which freezes playback.
     */
    public static void onStreamResponseRead(ByteBuffer buffer) {
        StreamResponse response = Objects.requireNonNull(streamResponseOnThread.get());
        if (!response.checkFirstPart || buffer.position() <= response.readStart) {
            return;
        }

        response.checkFirstPart = false;
        // A part type below 128 is a single byte.
        if (buffer.get(response.readStart) == UMP_PART_SABR_ERROR) {
            response.sabrError = true;
        }
    }

    /**
     * Injection point.
     * Called when the response of a network request is read.
     *
     * @return If the response is a SABR error, and must fail like a response with a server error status.
     *         The app then requests the range again from a fallback host.
     */
    public static boolean isStreamSabrErrorResponse() {
        StreamResponse response = Objects.requireNonNull(streamResponseOnThread.get());
        if (!response.sabrError) {
            return false;
        }

        response.sabrError = false;
        Logger.printDebug(() -> "Stream response is a SABR error, failing it so the range is requested from a fallback host");
        return true;
    }

    /**
     * Injection point.
     */
    public static String appendSpoofedClient(String videoFormat) {
        try {
            if (SPOOF_VIDEO_STREAMS && SharedYouTubeSettings.SPOOF_VIDEO_STREAMS_STATS_FOR_NERDS.get()
                    && !TextUtils.isEmpty(videoFormat)) {
                // Force LTR layout, to match the same LTR video time/length layout YouTube uses for all languages.
                return "\u202D" + videoFormat + "\u2009(" // u202D = left to right override
                        + StreamingDataRequest.getLastSpoofedClientName() + ")";
            }
        } catch (Exception ex) {
            Logger.printException(() -> "appendSpoofedClient failure", ex);
        }

        return videoFormat;
    }
}
