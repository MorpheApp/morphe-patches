/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

/**
 * Intercepts YouTube Music's offline browse network requests and playback data source requests
 * to serve local downloaded songs without hitting Google servers.
 */
public final class NativeDownloadsInterceptor {

    private static final String BROWSE_URL_PATH = "youtubei/v1/browse";
    private static final String OFFLINE_BROWSE_ID = "FEmusic_offline";

    static {
        initialize();
    }

    private NativeDownloadsInterceptor() {}

    /**
     * Registers the media stream interceptor into ExoPlayer via SpoofVideoStreamsPatch.
     */
    public static void initialize() {
        LocalStreamServer.start();
        SpoofVideoStreamsPatch.setStreamUriInterceptor(NativeDownloadsInterceptor::interceptMediaUri);
    }

    /**
     * Checks if in-app native downloads mode is active.
     */
    public static boolean isNativeDownloadsActive() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP;
    }

    /**
     * Injection point before sending a Cronet network request.
     * Intercepts "youtubei/v1/browse" when requesting "FEmusic_offline" to prevent
     * outbound network requests and prepare native local song injection.
     *
     * @param url The target request URL.
     * @param requestBody Optional request payload (usually contains JSON/Proto with browseId).
     * @return True if the network request should be blocked/suppressed, false to proceed normally.
     */
    public static boolean shouldInterceptBrowseRequest(@Nullable String url, @Nullable byte[] requestBody) {
        if (!isNativeDownloadsActive() || url == null) {
            return false;
        }

        if (url.contains(BROWSE_URL_PATH)) {
            // Check if this browse request targets FEmusic_offline
            if (requestBody != null) {
                String bodyStr = new String(requestBody);
                if (bodyStr.contains(OFFLINE_BROWSE_ID)) {
                    Logger.printDebug(() -> "NativeDownloadsInterceptor: Blocked outbound FEmusic_offline request to Google servers");
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Injection point for media playback data source / player URL requests.
     * If the video ID exists in local downloads, reroutes playback to the local stream URI.
     *
     * @param videoId The video/song ID requested for playback.
     * @return Local stream URI if downloaded, or null to proceed with Google streaming.
     */
    @Nullable
    public static Uri getLocalSongUriIfAvailable(String videoId) {
        if (!isNativeDownloadsActive() || videoId == null || videoId.isEmpty()) {
            return null;
        }

        if (DownloadedMark.isDownloaded(videoId)) {
            Uri localStreamUri = LocalStreamServer.getStreamUri(videoId);
            if (localStreamUri != null) {
                Logger.printDebug(() -> "NativeDownloadsInterceptor: Rerouted " + videoId + " to localhost streaming: " + localStreamUri);
                return localStreamUri;
            }
        }

        return null;
    }

    /**
     * Injection point for ExoPlayer media URI resolution.
     * Intercepts streaming URIs for downloaded tracks and streams them directly from localhost,
     * maintaining 100% compatibility with YouTube Music's native player (ExoPlayer).
     *
     * @param originalUri The remote streaming URI from YouTube servers.
     * @return Localhost HTTP streaming URI if downloaded, otherwise the original URI.
     */
    @Nullable
    public static Uri interceptMediaUri(@Nullable Uri originalUri) {
        if (!isNativeDownloadsActive() || originalUri == null) {
            return originalUri;
        }

        // 1. Try checking VideoInformation current video ID
        String currentVideoId = VideoInformation.getVideoId();
        if (currentVideoId != null && !currentVideoId.isEmpty() && DownloadedMark.isDownloaded(currentVideoId)) {
            Uri streamUri = LocalStreamServer.getStreamUri(currentVideoId);
            if (streamUri != null) {
                Logger.printDebug(() -> "NativeDownloadsInterceptor: Intercepted playback for " + currentVideoId + ", streaming locally");
                return streamUri;
            }
        }

        // 2. Try extracting video ID from videoplayback URL query parameters (id or docid)
        try {
            String queryId = originalUri.getQueryParameter("id");
            if (queryId != null && !queryId.isEmpty() && DownloadedMark.isDownloaded(queryId)) {
                Uri streamUri = LocalStreamServer.getStreamUri(queryId);
                if (streamUri != null) {
                    Logger.printDebug(() -> "NativeDownloadsInterceptor: Intercepted playback for query id " + queryId);
                    return streamUri;
                }
            }

            String docId = originalUri.getQueryParameter("docid");
            if (docId != null && !docId.isEmpty() && DownloadedMark.isDownloaded(docId)) {
                Uri streamUri = LocalStreamServer.getStreamUri(docId);
                if (streamUri != null) {
                    Logger.printDebug(() -> "NativeDownloadsInterceptor: Intercepted playback for docId " + docId);
                    return streamUri;
                }
            }
        } catch (Exception ignored) {
        }

        return originalUri;
    }
}
