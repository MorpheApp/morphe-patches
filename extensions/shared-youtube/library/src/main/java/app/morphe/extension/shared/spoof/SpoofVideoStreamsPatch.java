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

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.settings.AppLanguage;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.shared.spoof.requests.VisitorIdRequester;

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

    /**
     * Domain used for internet connectivity verification.
     * It has an empty response body and is only used to check for a 204 response code.
     * <p>
     * If an unreachable IP address (127.0.0.1) is used, no response code is provided.
     * <p>
     * YouTube handles unreachable IP addresses without issue.
     * YouTube Music has an issue with waiting for the Cronet connect timeout of 30s on mobile networks.
     * <p>
     * Using a VPN or DNS can temporarily resolve this issue,
     * But the ideal workaround is to avoid using an unreachable IP address.
     */
    private static final String INTERNET_CONNECTION_CHECK_URI_STRING = "https://www.google.com/gen_204";
    private static final Uri INTERNET_CONNECTION_CHECK_URI = Uri.parse(INTERNET_CONNECTION_CHECK_URI_STRING);

    private static final boolean SPOOF_VIDEO_STREAMS = isPatchIncluded()
            && SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get();

    @NonNull
    private static volatile Locale localeOverride = AppLanguage.DEFAULT.getLocale();

    private static volatile ClientType preferredClient = ClientType.VISIONOS_1_02;

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
     * If the last request built on the thread is of a shortened stream range.
     */
    private static final ThreadLocal<Boolean> streamRangeLimitedOnThread = ThreadLocal.withInitial(() -> false);

    /**
     * If the streams of the player responses created on this thread are kept.
     * Used for videos that are saved, which use streams that the app can save instead of the playback streams.
     */
    private static final ThreadLocal<Boolean> keepStreamsOnThread = ThreadLocal.withInitial(() -> false);

    /**
     * @return If this patch was included during patching.
     */
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
            // Prefetch visitorId for default client.
            Utils.runOnBackgroundThread(() -> VisitorIdRequester.getVisitorId(client));
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
     * Injection point.
     * Blocks '/get_watch' endpoint requests by returning an unreachable URI.
     *
     * @param innerTubeRequestBuilder The URI builder of the innertube request.
     * @return An unreachable URI builder if the request is a '/get_watch' endpoint, otherwise the original URI.
     */
    public static Uri.Builder blockGetWatchRequest(Uri.Builder innerTubeRequestBuilder) {
        if (SPOOF_VIDEO_STREAMS) {
            try {
                String path = innerTubeRequestBuilder.build().getPath();

                if (path != null && path.contains("get_watch")) {
                    Logger.printDebug(() -> "Blocking 'get_watch' by returning internet connection check URI");

                    return INTERNET_CONNECTION_CHECK_URI.buildUpon();
                }
            } catch (Exception ex) {
                Logger.printException(() -> "blockGetWatchRequest failure", ex);
            }
        }

        return innerTubeRequestBuilder;
    }

    /**
     * Injection point.
     * <p>
     * Blocks /initplayback requests.
     */
    public static String blockInitPlaybackRequest(String originalUrlString) {
        if (SPOOF_VIDEO_STREAMS) {
            try {
                var originalUri = Uri.parse(originalUrlString);
                String path = originalUri.getPath();

                if (path != null && path.contains("initplayback")) {
                    Logger.printDebug(() -> "Blocking 'initplayback' by returning internet connection check URI");

                    return INTERNET_CONNECTION_CHECK_URI_STRING;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "blockInitPlaybackRequest failure", ex);
            }
        }

        return originalUrlString;
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

    public interface OfflineResponseResolver {
        boolean isOffline(@NonNull String videoId, @Nullable Object playerProto);
    }

    @Nullable
    private static volatile OfflineResponseResolver offlineResponseResolver;

    public static void setOfflineResponseResolver(@Nullable OfflineResponseResolver resolver) {
        offlineResponseResolver = resolver;
    }

    private static boolean isOfflinePlayerResponse(@NonNull String videoId, @Nullable Object playerProto) {
        try {
            OfflineResponseResolver resolver = offlineResponseResolver;
            if (resolver != null) {
                return resolver.isOffline(videoId, playerProto);
            }
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
     * Fix audio stuttering in YouTube Music.
     */
    public static boolean disableSABR() {
        return SPOOF_VIDEO_STREAMS && !StreamingDataRequest.getLastSpoofedClientUseSABR();
    }

    /**
     * Injection point.
     * Turns off a feature flag that interferes with spoofing.
     */
    public static boolean useMediaFetchHotConfigReplacement(boolean original) {
        if (original) {
            Logger.printDebug(() -> "useMediaFetchHotConfigReplacement is set on");
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
     */
    public static void fetchStreams(String url, Map<String, String> requestHeaders) {
        if (SPOOF_VIDEO_STREAMS) {
            try {
                Uri uri = Uri.parse(url);
                String path = uri.getPath();
                if (path == null || !path.contains("player")) {
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

                String id = uri.getQueryParameter("id");
                if (id == null) {
                    Logger.printException(() -> "Ignoring request with no ID: " + url);
                    return;
                }
                boolean isInline = "1".equals(uri.getQueryParameter("inline"));

                StreamingDataRequest.fetchRequest(id, isInline, requestHeaders);
            } catch (Exception ex) {
                Logger.printException(() -> "buildRequest failure", ex);
            }
        }
    }

    /**
     * Injection point.
     * Fix playback by replace the streaming data.
     * Called after {@link #fetchStreams(String, Map)}.
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
                return null;
            }

            // Without a connection the replacement streams cannot load,
            // and a video that is saved plays with the streams of its own player response.
            if (!Utils.isNetworkConnected()) {
                Logger.printDebug(() -> "Not overriding streaming data (network is not connected): " + videoId);
                return null;
            }

            try {
                StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
                if (request != null) {
                    var buffers = request.getStream();
                    if (buffers != null) {
                        byte[] stream = buffers.streamingData();
                        Logger.printDebug(() -> "Overriding video stream: " + videoId);
                        return stream;
                    }
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
     * Fix playback by replace the player config.
     * Called after {@link #getStreamingData(String)}.
     */
    @Nullable
    public static byte[] getPlayerConfig(String videoId) {
        if (SPOOF_VIDEO_STREAMS) {
            try {
                StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
                if (request != null) {
                    var buffers = request.getStream();
                    if (buffers != null) {
                        byte[] config = buffers.playerConfig();
                        if (config != null) {
                            Logger.printDebug(() -> "Overriding player config: " + videoId);
                            return config;
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
     * Called after {@link #getPlayerConfig(String)}.
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
     * Called for every network request the app makes, when the request is built.
     * Saving a video requests the rest of a stream with a single range, but the server sends a large range
     * at about playback speed. The range is shortened, and {@link #isLimitedStreamRangeFinished(long)}
     * makes the app request the following range after this one ends.
     * Ranges without an end are left as they are, because the app reads those until the stream ends.
     *
     * @param url Url of the request.
     * @return The url to request.
     */
    @Nullable
    public static String limitPlainStreamRange(@Nullable String url) {
        try {
            // A request is built and read on the same thread.
            streamRangeLimitedOnThread.set(false);
            if (url == null || !isPlainStreamRequest(url)) {
                return url;
            }

            String range = Uri.parse(url).getQueryParameter("range");
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
        if (remainingBytes != 0 || !streamRangeLimitedOnThread.get()) {
            return false;
        }

        streamRangeLimitedOnThread.set(false);
        Logger.printDebug(() -> "Requesting the rest of the shortened stream range");
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
