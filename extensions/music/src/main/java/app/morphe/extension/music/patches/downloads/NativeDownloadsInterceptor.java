/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.net.Uri;

import androidx.annotation.Nullable;

import java.io.File;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;

/**
 * Intercepts YouTube Music's offline browse network requests and playback data source requests
 * to serve local downloaded songs directly without hitting Google servers or running a localhost server.
 */
public final class NativeDownloadsInterceptor {

    private static final String BROWSE_URL_PATH = "youtubei/v1/browse";
    private static final String OFFLINE_BROWSE_ID = "FEmusic_offline";

    private static volatile String lastRequestedTrackId = null;

    public static void notifyTrackPlaybackRequested(@Nullable String videoId) {
        lastRequestedTrackId = videoId;
    }

    static {
        initialize();
    }

    private NativeDownloadsInterceptor() {}

    /**
     * Registers the media stream interceptor into ExoPlayer via SpoofVideoStreamsPatch.
     */
    public static void initialize() {
        SpoofVideoStreamsPatch.setStreamUriInterceptor(NativeDownloadsInterceptor::interceptMediaUri);
    }

    /**
     * Checks if in-app native downloads mode is active.
     */
    public static boolean isNativeDownloadsActive() {
        return Settings.DOWNLOAD_MODE.get() != DownloadMode.DEFAULT
                || Settings.EXTERNAL_DOWNLOADER_MENU.get();
    }

    /**
     * Injection point before sending a Cronet network request.
     * Intercepts "youtubei/v1/browse" when requesting "FEmusic_offline" to prevent
     * outbound network requests.
     */
    public static boolean shouldInterceptBrowseRequest(@Nullable String url, @Nullable byte[] requestBody) {
        if (!isNativeDownloadsActive() || url == null) {
            return false;
        }

        if (url.contains(BROWSE_URL_PATH)) {
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
     * If the video ID exists in local downloads, reroutes playback to the local file URI.
     */
    @Nullable
    public static Uri getLocalSongUriIfAvailable(String videoId) {
        if (!isNativeDownloadsActive() || videoId == null || videoId.isEmpty()) {
            return null;
        }

        File file = LocalDownloadManager.getSongFile(videoId);
        if (file != null && file.isFile()) {
            Uri localFileUri = Uri.fromFile(file);
            Logger.printDebug(() -> "NativeDownloadsInterceptor: Rerouted " + videoId + " to direct file: " + localFileUri);
            return localFileUri;
        }

        return null;
    }

    /**
     * Injection point for ExoPlayer media URI resolution.
     * Intercepts streaming URIs for downloaded tracks and streams them directly from internal storage,
     * maintaining 100% compatibility with YouTube Music's native player (ExoPlayer).
     */
    @Nullable
    public static Uri interceptMediaUri(@Nullable Uri originalUri) {
        if (!isNativeDownloadsActive() || originalUri == null) {
            return originalUri;
        }

        // 1. Try checking lastRequestedTrackId if set and downloaded
        String pendingId = lastRequestedTrackId;
        if (pendingId != null && !pendingId.isEmpty()) {
            Uri localUri = getLocalSongUriIfAvailable(pendingId);
            if (localUri != null) {
                Logger.printDebug(() -> "NativeDownloadsInterceptor: Intercepted playback for requested " + pendingId + ", playing local file");
                return localUri;
            }
        }

        // 2. Try checking VideoInformation current video ID
        String currentVideoId = VideoInformation.getVideoId();
        if (currentVideoId != null && !currentVideoId.isEmpty()) {
            Uri localUri = getLocalSongUriIfAvailable(currentVideoId);
            if (localUri != null) {
                Logger.printDebug(() -> "NativeDownloadsInterceptor: Intercepted playback for " + currentVideoId + ", playing local file");
                return localUri;
            }
        }

        // 3. Try extracting video ID from videoplayback URL query parameters (id or docid)
        try {
            String queryId = originalUri.getQueryParameter("id");
            if (queryId != null && !queryId.isEmpty()) {
                Uri localUri = getLocalSongUriIfAvailable(queryId);
                if (localUri != null) {
                    Logger.printDebug(() -> "NativeDownloadsInterceptor: Intercepted playback for query id " + queryId);
                    return localUri;
                }
            }

            String docId = originalUri.getQueryParameter("docid");
            if (docId != null && !docId.isEmpty()) {
                Uri localUri = getLocalSongUriIfAvailable(docId);
                if (localUri != null) {
                    Logger.printDebug(() -> "NativeDownloadsInterceptor: Intercepted playback for docId " + docId);
                    return localUri;
                }
            }
        } catch (Exception ignored) {
        }

        return originalUri;
    }
}
