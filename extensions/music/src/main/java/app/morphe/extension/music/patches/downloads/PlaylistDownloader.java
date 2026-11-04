/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4398
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.text.format.Formatter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Downloads every track of a playlist or an album with the local downloads, one track at a time.
 * <p>
 * The tracks are listed first, and the download starts once the number of tracks is confirmed,
 * since a large playlist such as Liked music can take hours and many gigabytes.
 */
public final class PlaylistDownloader {

    /** Key of the single notification that shows the progress of the playlist. */
    static final String NOTIFICATION_KEY = "morphe_playlist_download";

    /** Rough size of a track, only used to tell how much space the playlist needs. */
    private static final long AVERAGE_TRACK_BYTES = 4L * 1024 * 1024;

    /** How long a lost connection is waited for before the download stops. */
    private static final long MAX_NETWORK_WAIT_MILLISECONDS = 10 * 60 * 1000;
    private static final long NETWORK_CHECK_INTERVAL_MILLISECONDS = 5000;

    /**
     * Set while the tracks are listed or downloaded. It is not set while the confirmation waits,
     * so a dialog closed in any way never leaves it set.
     */
    private static final AtomicBoolean running = new AtomicBoolean();

    /** Set when the system ends the download service. The download stops before the next track. */
    private static volatile boolean stopRequested;

    private PlaylistDownloader() {
    }

    /**
     * @param playlistId Id of the playlist or the album, such as 'LM' for Liked music,
     *                   'OLAK5uy_...' for the playlist of an album, or the 'MPREb_...' browse id
     *                   of an album.
     */
    public static void start(String playlistId) {
        // Listing the tracks retries for a long while before it gives up without the network.
        if (!Utils.isNetworkConnected()) {
            Utils.showToastShort(str("morphe_music_downloads_playlist_download_no_network"));
            return;
        }
        if (!running.compareAndSet(false, true)) {
            Utils.showToastShort(str("morphe_music_downloads_playlist_download_already_running"));
            return;
        }

        String browseId = toBrowseId(playlistId);
        Logger.printDebug(() -> "Listing the tracks of " + browseId + " to download");
        Utils.showToastShort(str("morphe_music_downloads_playlist_download_listing"));
        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                str("morphe_music_downloads_playlist_download_title"), -1, true,
                str("morphe_music_downloads_playlist_download_listing"));

        Utils.runOnBackgroundThread(() -> {
            try {
                listAndConfirm(browseId);
            } catch (Exception ex) {
                endWithMessage(str("morphe_music_downloads_playlist_download_listing_failed"));
                Logger.printException(() -> "Playlist listing failed", ex);
            } finally {
                // Nothing runs while the answer is awaited.
                running.set(false);
            }
        });
    }

    private static void listAndConfirm(String browseId) {
        PlaylistTracksRequest.Result result = PlaylistTracksRequest.fetchVideoIds(browseId,
                tracksListed -> {
                    if (LocalDownloadManager.canPostProgress()) {
                        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                                str("morphe_music_downloads_playlist_download_title"), -1, true,
                                str("morphe_music_downloads_playlist_download_listed",
                                        OfflineUi.trackCount(tracksListed)));
                    }
                });

        if (result == PlaylistTracksRequest.NEEDS_ACCOUNT) {
            endWithMessage(str("morphe_music_downloads_playlist_download_needs_account"));
            return;
        }
        if (result == null || result.videoIds().isEmpty()) {
            endWithMessage(str("morphe_music_downloads_playlist_download_listing_failed"));
            return;
        }

        List<String> videoIds = result.videoIds();
        List<String> missing = new ArrayList<>(videoIds.size());
        for (String videoId : videoIds) {
            if (!LocalDownloadManager.isDownloaded(videoId)) missing.add(videoId);
        }
        final int alreadySaved = videoIds.size() - missing.size();
        Logger.printDebug(() -> "Playlist has " + videoIds.size() + " tracks, "
                + alreadySaved + " already downloaded");

        if (missing.isEmpty()) {
            String count = OfflineUi.trackCount(videoIds.size());
            endWithMessage(result.complete()
                    ? str("morphe_music_downloads_playlist_download_all_saved", count)
                    : str("morphe_music_downloads_playlist_download_listed_all_saved", count));
            return;
        }

        LocalDownloadManager.cancelDownloadNotification(NOTIFICATION_KEY);
        Utils.runOnMainThread(() -> confirm(videoIds.size(), missing, result.complete()));
    }

    /**
     * 'LM' is the playlist id of Liked music, and its page is 'VLLM' like any other playlist.
     */
    private static String toBrowseId(String playlistId) {
        if (playlistId.startsWith("VL") || playlistId.startsWith("MPRE")) return playlistId;
        return "VL" + playlistId;
    }

    private static void confirm(int trackCount, List<String> missing, boolean complete) {
        Activity activity = Utils.getActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Logger.printInfo(() -> "No activity to confirm the playlist download");
            return;
        }

        String message = str("morphe_music_downloads_playlist_download_confirm_message",
                OfflineUi.trackCount(trackCount), OfflineUi.trackCount(missing.size()),
                Formatter.formatShortFileSize(activity, missing.size() * AVERAGE_TRACK_BYTES));
        if (!complete) {
            message += "\n\n" + str("morphe_music_downloads_playlist_download_incomplete");
        }

        CustomDialog.create(activity,
                str("morphe_music_downloads_playlist_download_title"), message, null,
                str("morphe_music_downloads_playlist_download_confirm_button"),
                () -> {
                    if (!running.compareAndSet(false, true)) {
                        Utils.showToastShort(str("morphe_music_downloads_playlist_download_already_running"));
                        return;
                    }
                    Utils.showToastShort(str("morphe_music_downloads_playlist_download_started",
                            OfflineUi.trackCount(missing.size())));
                    PlaylistDownloadService.start(activity, missing);
                },
                () -> {
                }, null, null, true).first.show();
    }

    /** Stops the download before its next track. */
    static void stop() {
        stopRequested = true;
    }

    /** Runs in the download service, off the main thread. */
    static void downloadAll(List<String> videoIds) {
        stopRequested = false;
        final int total = videoIds.size();
        int saved = 0;
        int failed = 0;
        int done = 0;

        try {
            for (; done < total; done++) {
                if (!awaitNetwork(done, total)) break;

                if (LocalDownloadManager.canPostProgress()) {
                    LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                            str("morphe_music_downloads_playlist_download_title"), done * 100 / total, true,
                            str("morphe_music_downloads_playlist_download_progress", done + 1, total));
                }

                String videoId = videoIds.get(done);
                boolean trackSaved = LocalDownloadManager.downloadPlaylistTrack(videoId);
                // A connection lost in the middle of a track fails it, so it is tried again
                // once the network is back.
                if (!trackSaved && !Utils.isNetworkConnected()) {
                    if (!awaitNetwork(done, total)) break;
                    trackSaved = LocalDownloadManager.downloadPlaylistTrack(videoId);
                }
                if (trackSaved) {
                    saved++;
                } else {
                    failed++;
                }
            }

            final int doneCount = done;
            final int savedCount = saved;
            final int failedCount = failed;
            Logger.printDebug(() -> "Playlist download ended at " + doneCount + "/" + total
                    + ", saved: " + savedCount + ", failed: " + failedCount);

            if (done < total) {
                endWithMessage(str("morphe_music_downloads_playlist_download_stopped", done,
                        OfflineUi.trackCount(total)));
            } else if (failed == 0) {
                endWithMessage(str("morphe_music_downloads_playlist_download_complete",
                        OfflineUi.trackCount(saved)));
            } else {
                endWithMessage(str("morphe_music_downloads_playlist_download_complete_with_failures",
                        OfflineUi.trackCount(saved), OfflineUi.trackCount(failed)));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Playlist download failed", ex);
            endWithMessage(str("morphe_music_downloads_playlist_download_stopped", done,
                    OfflineUi.trackCount(total)));
        } finally {
            running.set(false);
        }
    }

    /**
     * @return If the download may go on: the network is connected, after waiting for it if it
     *         was lost, and no stop was requested.
     */
    private static boolean awaitNetwork(int done, int total) {
        if (stopRequested) return false;
        if (Utils.isNetworkConnected()) return true;

        LocalDownloadManager.awaitNotificationSlot();
        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                str("morphe_music_downloads_playlist_download_title"), done * 100 / total, true,
                str("morphe_music_downloads_playlist_download_waiting_network"));

        final long giveUpTime = System.currentTimeMillis() + MAX_NETWORK_WAIT_MILLISECONDS;
        while (!stopRequested && System.currentTimeMillis() < giveUpTime) {
            try {
                //noinspection BusyWait
                Thread.sleep(NETWORK_CHECK_INTERVAL_MILLISECONDS);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return false;
            }
            if (Utils.isNetworkConnected()) return !stopRequested;
        }
        return false;
    }

    /** Shown as a toast too, for devices where notifications are off. */
    private static void endWithMessage(String message) {
        LocalDownloadManager.awaitNotificationSlot();
        LocalDownloadManager.showDownloadNotification(NOTIFICATION_KEY,
                str("morphe_music_downloads_playlist_download_title"), 100, false, message);
        Utils.showToastShort(message);
    }
}
