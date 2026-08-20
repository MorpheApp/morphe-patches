/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.util.Comparator;

import app.morphe.extension.music.patches.scrobbling.ScrobbleManager;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/** Downloads the already-resolved InnerTube audio stream without leaving YouTube Music. */
public final class LocalDownloadManager {
    private LocalDownloadManager() {}

    /** Must be called from the main thread. Resolution and enqueueing happen in background. */
    public static void enqueue(@NonNull String videoId) {
        if (videoId.isBlank()) {
            Utils.showToastShort("Download non disponibile: brano sconosciuto");
            return;
        }

        ScrobbleManager metadata = ScrobbleManager.getInstance();
        String title = metadata.getCurrentTitle();
        String artist = metadata.getCurrentArtist();
        String album = metadata.getCurrentAlbum();
        int duration = metadata.getCurrentDurationSeconds();
        Bitmap artwork = metadata.getCurrentArtwork();

        Utils.showToastShort("Download avviato");
        Utils.submitOnBackgroundThread(() -> {
            try {
                Format format = resolveBestAudioFormat(videoId);
                if (format == null || format.getUrl().isBlank()) {
                    Utils.showToastShort("Impossibile recuperare il flusso audio");
                    return null;
                }

                Context context = Utils.getContext();
                DownloadManager manager = (DownloadManager)
                        context.getSystemService(Context.DOWNLOAD_SERVICE);
                if (manager == null) {
                    Utils.showToastShort("Servizio download non disponibile");
                    return null;
                }

                String extension = format.getMimeType().contains("mp4") ? ".m4a" : ".webm";
                String fileName = sanitizeFileName(videoId) + extension;
                File directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
                OfflineTrack.save(directory, videoId, title, artist, album, duration, artwork);
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(format.getUrl()))
                        .setTitle("YouTube Music")
                        .setDescription(videoId)
                        .setMimeType(format.getMimeType().isBlank() ? "audio/*" : format.getMimeType())
                        .setNotificationVisibility(
                                DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                        .setAllowedOverMetered(true)
                        .setAllowedOverRoaming(false)
                        .setDestinationInExternalFilesDir(
                                context, Environment.DIRECTORY_MUSIC, "Morphe/" + fileName);

                long downloadId = manager.enqueue(request);
                context.getSharedPreferences("morphe_local_downloads", Context.MODE_PRIVATE)
                        .edit()
                        .putLong(videoId, downloadId)
                        .apply();
                Logger.printDebug(() -> "Queued local audio download " + downloadId + ": " + videoId);
            } catch (Exception ex) {
                Logger.printException(() -> "Local audio download failed: " + videoId, ex);
                Utils.showToastShort("Download non riuscito");
            }
            return null;
        });
    }

    @Nullable
    private static Format resolveBestAudioFormat(@NonNull String videoId) {
        StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
        if (request == null) {
            Logger.printDebug(() -> "No cached streaming request for download: " + videoId);
            return null;
        }

        StreamingDataRequest.StreamData stream = request.getStream();
        if (stream == null) return null;

        try {
            return PlayerResponse.parseFrom(stream.streamingData())
                    .getStreamingData()
                    .getAdaptiveFormatsList()
                    .stream()
                    .filter(format -> format.getMimeType().startsWith("audio/") && !format.getUrl().isBlank())
                    .max(Comparator.comparingInt(Format::getBitrate))
                    .orElse(null);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not parse audio formats: " + videoId, ex);
            return null;
        }
    }

    @NonNull
    private static String sanitizeFileName(@NonNull String value) {
        return value.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
