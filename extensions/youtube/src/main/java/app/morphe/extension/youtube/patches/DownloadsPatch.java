/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.settings.preference.ExternalDownloaderPreference;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.ExternalDownloadButton;

@SuppressWarnings("unused")
public final class DownloadsPatch {

    /**
     * Ordinal of the action of the offline playlist endpoint that saves the playlist.
     */
    private static final int OFFLINE_PLAYLIST_ACTION_ADD_ORDINAL = 1;

    /**
     * Injection point.
     * <p>
     * Called from the in-app download hook,
     * for both the player action button (below the video)
     * and the 'Download video' flyout option for feed videos.
     * <p>
     * Appears to always be called from the main thread.
     */
    public static boolean inAppDownloadButtonOnClick(String videoId) {
        return downloadVideo(videoId);
    }

    /**
     * @return If videos are downloaded by Morphe instead of the app.
     */
    public static boolean isDownloadModeHandledByMorphe() {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.EXTERNAL;
    }

    /**
     * Downloads a video that has no Download button of the app, such as a Short.
     * Must be called from the main thread.
     */
    public static void downloadVideoWithoutAppButton(String videoId) {
        if (Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP) {
            InAppDownloadsPatch.saveVideo(videoId);
        } else {
            downloadVideo(videoId);
        }
    }

    /**
     * Downloads a video with the download mode that is set.
     *
     * @return If the download was handled, otherwise the app downloads the video itself.
     */
    public static boolean downloadVideo(String videoId) {
        try {
            if (!isDownloadModeHandledByMorphe()) {
                return false;
            }

            if (ExternalDownloadButton.quickMenuOnTap()) {
                ExternalDownloadButton.showQuickDownloadMenu(Utils.getActivity(), videoId);
                return true;
            }

            ExternalDownloadButton.launchDownloader(Utils.getActivity(), videoId, null);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "downloadVideo failure", ex);
        }
        return false;
    }

    /**
     * Injection point.
     * <p>
     * Called when a playlist is saved or removed,
     * such as with the Download button of a playlist page header.
     * Only saving opens the external downloader, so playlists saved in-app can still be removed.
     *
     * @param action Action of the offline playlist endpoint.
     */
    public static boolean offlinePlaylistEndpointOnClick(String playlistId, Enum<?> action) {
        try {
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.EXTERNAL
                    || action.ordinal() != OFFLINE_PLAYLIST_ACTION_ADD_ORDINAL) {
                return false;
            }

            ExternalDownloaderPreference.launchExternalDownloader(playlistId, Utils.getActivity(),
                    "https://www.youtube.com/playlist?list=" + playlistId);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "offlinePlaylistEndpointOnClick failure", ex);
        }
        return false;
    }

}
