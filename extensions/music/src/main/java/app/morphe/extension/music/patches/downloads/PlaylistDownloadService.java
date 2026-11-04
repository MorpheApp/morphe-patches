/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import static app.morphe.extension.shared.StringRef.str;

import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.IBinder;

import androidx.annotation.Nullable;

import java.util.List;

import app.morphe.extension.shared.Utils;

/**
 * Keeps the app alive while the tracks of a playlist are downloaded, which can take hours.
 * On a plain background thread the download would end with the process, once the app is left
 * and nothing plays.
 */
public final class PlaylistDownloadService extends Service {

    /** Handed over in memory, since the tracks of Liked music are thousands of ids. */
    @Nullable
    private static List<String> pendingVideoIds;

    /** Must be called on the main thread while the app is in the foreground. */
    static void start(Context context, List<String> videoIds) {
        pendingVideoIds = videoIds;
        context.startForegroundService(new Intent(context, PlaylistDownloadService.class));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        List<String> videoIds = pendingVideoIds;
        pendingVideoIds = null;

        // Started with startForegroundService, so the service has to reach the foreground before
        // anything can stop it. The notification is the one the download then reports to.
        final int trackCount = videoIds == null ? 0 : videoIds.size();
        startForeground(PlaylistDownloader.NOTIFICATION_KEY.hashCode(),
                LocalDownloadManager.downloadNotification(getSystemService(NotificationManager.class),
                        str("morphe_music_downloads_playlist_download_title"), -1, true,
                        str("morphe_music_downloads_playlist_download_started",
                                OfflineUi.trackCount(trackCount))));
        if (videoIds == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        Utils.runOnBackgroundThread(() -> {
            try {
                PlaylistDownloader.downloadAll(videoIds);
            } finally {
                // The notification stays, with the result of the download.
                stopForeground(STOP_FOREGROUND_DETACH);
                stopSelf();
            }
        });
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
