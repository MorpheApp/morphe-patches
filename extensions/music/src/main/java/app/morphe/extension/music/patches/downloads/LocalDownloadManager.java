/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.os.Environment;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Comparator;
import java.util.Set;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicLong;

import app.morphe.extension.music.patches.scrobbling.ScrobbleManager;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/** Downloads the already-resolved InnerTube audio stream without leaving YouTube Music. */
public final class LocalDownloadManager {
    private static final String DOWNLOAD_CHANNEL = "morphe_audio_downloads";
    private static final Set<String> ACTIVE_DOWNLOADS = ConcurrentHashMap.newKeySet();

    private LocalDownloadManager() {}

    /** Must be called from the main thread. Resolution and enqueueing happen in background. */
    public static void enqueue(@NonNull String videoId) {
        if (videoId.isBlank()) {
            Utils.showToastShort("Download non disponibile: brano sconosciuto");
            return;
        }

        if (!ACTIVE_DOWNLOADS.add(videoId)) {
            Utils.showToastShort("Download già in corso");
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
                downloadWithResume(videoId, title, format.getUrl(), temporary, format.getContentLength());
                if (destination.exists() && !destination.delete()) throw new IllegalStateException("Could not replace audio file");
                if (!temporary.renameTo(destination)) throw new IllegalStateException("Could not finish audio file");
                OfflineTrack.save(directory, videoId, title, artist, album, duration, artwork);
                Logger.printDebug(() -> "Downloaded local audio: " + destination);
                Utils.showToastShort("Download completato");
            } catch (Exception ex) {
                Logger.printException(() -> "Local audio download failed: " + videoId, ex);
                showDownloadNotification(videoId, title, 0, false, "Download non riuscito");
                Utils.showToastShort("Download non riuscito");
            } finally {
                ACTIVE_DOWNLOADS.remove(videoId);
            }
            return null;
        });
    }

    private static void downloadWithResume(String videoId, String title, String url,
                                           File temporary, long expectedLength) throws Exception {
        Utils.showToastShort("Download avviato");
        showDownloadNotification(videoId, title, 0, true, "Avvio download…");
        if (expectedLength <= 0) expectedLength = probeContentLength(url);
        final long totalLength = expectedLength;
        if (expectedLength <= 0) {
            downloadUnknownLength(url, temporary);
        } else {
            final int segmentCount = 4;
            try (RandomAccessFile file = new RandomAccessFile(temporary, "rw")) {
                file.setLength(totalLength);
            }
            AtomicLong downloaded = new AtomicLong();
            AtomicLong lastNotified = new AtomicLong();
            ExecutorService executor = Executors.newFixedThreadPool(segmentCount);
            List<Future<?>> futures = new ArrayList<>();
            long segmentSize = (totalLength + segmentCount - 1) / segmentCount;
            try {
                for (int index = 0; index < segmentCount; index++) {
                    long start = index * segmentSize;
                    long end = Math.min(totalLength - 1, start + segmentSize - 1);
                    if (start > end) continue;
                    futures.add(executor.submit(() -> {
                        downloadRange(url, temporary, start, end, downloaded, bytes -> {
                            long previous = lastNotified.get();
                            if (bytes - previous >= 256 * 1024 && lastNotified.compareAndSet(previous, bytes)) {
                                int progress = (int) Math.min(99, bytes * 100 / totalLength);
                                showDownloadNotification(videoId, title, progress, true, "Download " + progress + "%");
                            }
                        });
                        return null;
                    }));
                }
                for (Future<?> future : futures) future.get();
            } finally {
                executor.shutdownNow();
            }
        }
        if (totalLength > 0 && temporary.length() != totalLength)
            throw new IllegalStateException("Incomplete audio file: " + temporary.length() + "/" + totalLength);
        showDownloadNotification(videoId, title, 100, false, "Download completato");
    }

    private interface ProgressCallback { void update(long bytes); }

    private static void downloadRange(String url, File destination, long start, long end,
                                      AtomicLong downloaded, ProgressCallback progress) throws Exception {
        long position = start;
        int failures = 0;
        while (position <= end) {
            long before = position;
            HttpURLConnection connection = openAudioConnection(url, position, end);
            try {
                if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL)
                    throw new IllegalStateException("Range request returned HTTP " + connection.getResponseCode());
                try (InputStream input = connection.getInputStream(); RandomAccessFile output = new RandomAccessFile(destination, "rw")) {
                    output.seek(position);
                    byte[] buffer = new byte[64 * 1024];
                    while (position <= end) {
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, end - position + 1));
                        if (read < 0) break;
                        output.write(buffer, 0, read);
                        position += read;
                        progress.update(downloaded.addAndGet(read));
                    }
                }
            } catch (java.io.IOException ex) {
                Logger.printDebug(() -> "Audio range interrupted; retrying", ex);
            } finally {
                connection.disconnect();
            }
            failures = position > before ? 0 : failures + 1;
            if (failures >= 4) throw new IllegalStateException("Download range stalled at " + position);
        }
    }

    private static long probeContentLength(String url) {
        HttpURLConnection connection = null;
        try {
            connection = openAudioConnection(url, 0, 0);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_PARTIAL) return -1;
            String contentRange = connection.getHeaderField("Content-Range");
            if (contentRange == null) return -1;
            int slash = contentRange.lastIndexOf('/');
            return slash < 0 ? -1 : Long.parseLong(contentRange.substring(slash + 1));
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not probe audio length", ex);
            return -1;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static HttpURLConnection openAudioConnection(String url, long start, long end) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setInstanceFollowRedirects(true);
        connection.setRequestProperty("User-Agent", "com.google.android.youtube/19.47.53 (Linux; U; Android 14) gzip");
        connection.setRequestProperty("Referer", "https://music.youtube.com/");
        connection.setRequestProperty("Range", "bytes=" + start + "-" + end);
        return connection;
    }

    private static void downloadUnknownLength(String url, File destination) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(15_000); connection.setReadTimeout(30_000);
        connection.setRequestProperty("User-Agent", "com.google.android.youtube/19.47.53 (Linux; U; Android 14) gzip");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK)
                throw new IllegalStateException("HTTP " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream(); FileOutputStream output = new FileOutputStream(destination)) {
                byte[] buffer = new byte[64 * 1024]; int read;
                while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
            }
        } finally { connection.disconnect(); }
    }

    private static void showDownloadNotification(String videoId, String title, int progress,
                                                 boolean ongoing, String text) {
        Context context = Utils.getContext();
        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(new NotificationChannel(DOWNLOAD_CHANNEL,
                    "Download audio", NotificationManager.IMPORTANCE_LOW));
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(context, DOWNLOAD_CHANNEL) : new Notification.Builder(context);
        builder.setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
                .setContentTitle(title == null || title.isBlank() ? "YouTube Music" : title)
                .setContentText(text).setOngoing(ongoing).setOnlyAlertOnce(true);
        if (ongoing) builder.setProgress(100, Math.max(0, progress), progress < 0);
        manager.notify(videoId.hashCode(), builder.build());
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
