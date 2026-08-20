/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
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

        Utils.submitOnBackgroundThread(() -> {
            try {
                Format format = resolveBestAudioFormat(videoId);
                if (format == null || format.getUrl().isBlank()) {
                    Utils.showToastShort("Impossibile recuperare il flusso audio");
                    return null;
                }

                Context context = Utils.getContext();
                String extension = format.getMimeType().contains("mp4") ? ".m4a" : ".webm";
                String fileName = sanitizeFileName(videoId) + extension;
                File directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
                if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Could not create download directory");
                File destination = new File(directory, fileName);
                File temporary = new File(directory, fileName + ".part");

                if (temporary.exists() && !temporary.delete()) throw new IllegalStateException("Could not reset partial audio file");
                downloadWithResume(format.getUrl(), temporary, format.getContentLength());
                if (destination.exists() && !destination.delete()) throw new IllegalStateException("Could not replace audio file");
                if (!temporary.renameTo(destination)) throw new IllegalStateException("Could not finish audio file");
                OfflineTrack.save(directory, videoId, title, artist, album, duration, artwork);
                Logger.printDebug(() -> "Downloaded local audio: " + destination);
                Utils.showToastShort("Download completato");
            } catch (Exception ex) {
                Logger.printException(() -> "Local audio download failed: " + videoId, ex);
                Utils.showToastShort("Download non riuscito");
            }
            return null;
        });
    }

    private static void downloadWithResume(String url, File temporary, long expectedLength) throws Exception {
        long offset = 0;
        int attemptsWithoutProgress = 0;
        Utils.showToastShort("Download avviato");
        while (expectedLength <= 0 || offset < expectedLength) {
            long before = offset;
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(15_000);
            connection.setReadTimeout(30_000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "com.google.android.youtube/19.47.53 (Linux; U; Android 14) gzip");
            connection.setRequestProperty("Referer", "https://music.youtube.com/");
            if (offset > 0) connection.setRequestProperty("Range", "bytes=" + offset + "-");
            try {
                int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_PARTIAL)
                    throw new IllegalStateException("HTTP " + responseCode);
                if (offset > 0 && responseCode == HttpURLConnection.HTTP_OK)
                    throw new IllegalStateException("Server ignored range request");
                if (expectedLength <= 0) {
                    long responseLength = connection.getContentLengthLong();
                    if (responseLength > 0) expectedLength = offset + responseLength;
                }
                try (InputStream input = connection.getInputStream();
                     FileOutputStream output = new FileOutputStream(temporary, offset > 0)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        output.write(buffer, 0, read);
                        offset += read;
                    }
                }
            } catch (java.io.IOException ex) {
                Logger.printDebug(() -> "Audio connection interrupted; resuming at " + temporary.length(), ex);
                offset = temporary.length();
            } finally {
                connection.disconnect();
            }
            offset = temporary.length();
            if (expectedLength > 0 && offset >= expectedLength) break;
            attemptsWithoutProgress = offset > before ? 0 : attemptsWithoutProgress + 1;
            if (attemptsWithoutProgress >= 3) throw new IllegalStateException("Download stalled at " + offset);
        }
        if (expectedLength > 0 && temporary.length() != expectedLength)
            throw new IllegalStateException("Incomplete audio file: " + temporary.length() + "/" + expectedLength);
    }

    @Nullable
    private static Format resolveBestAudioFormat(@NonNull String videoId) {
        StreamingDataRequest request = StreamingDataRequest.getRequestForVideoId(videoId);
        if (request == null) {
            Logger.printDebug(() -> "No cached streaming request for download, resolving: " + videoId);
            request = StreamingDataRequest.fetchRequestForDownload(videoId);
        }
        if (request == null) return null;

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
