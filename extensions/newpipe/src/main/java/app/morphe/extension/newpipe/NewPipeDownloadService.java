/*
 * Copyright 2026 Morphe.
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */
package app.morphe.extension.newpipe;

import static app.morphe.extension.shared.StringRef.str;

import android.app.DownloadManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;

/** Uses Android's download manager, then joins adaptive MP4 streams and publishes the file. */
public final class NewPipeDownloadService extends Service {
    static final String TITLE = "title";
    static final String PRIMARY_URL = "primary";
    static final String SECONDARY_URL = "secondary";
    static final String MIME_TYPE = "mime";
    static final String EXTENSION = "extension";
    private static final String CHANNEL = "morphe_newpipe_downloads";
    private static final int NOTIFICATION_ID = 0x4e50;
    private static final int SAMPLE_BUFFER_SIZE = 16 * 1024 * 1024;
    private static final long POLL_INTERVAL_MS = 1000;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL,
                str("morphe_newpipe_notification_channel"), NotificationManager.IMPORTANCE_LOW));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIFICATION_ID, notification(str("morphe_newpipe_downloading"), true));
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        executor.execute(() -> {
            try {
                download(intent);
                updateNotification(str("morphe_newpipe_complete"), false);
            } catch (Exception ex) {
                Logger.printException(() -> "NewPipe download failed", ex);
                updateNotification(str("morphe_newpipe_failed"), false);
            } finally {
                stopSelfResult(startId);
            }
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification notification(CharSequence message, boolean ongoing) {
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(ongoing ? android.R.drawable.stat_sys_download : android.R.drawable.stat_sys_download_done)
                .setContentTitle(str("morphe_newpipe_notification_channel"))
                .setContentText(message)
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setProgress(0, 0, ongoing)
                .build();
    }

    private void updateNotification(CharSequence message, boolean ongoing) {
        getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, notification(message, ongoing));
    }

    private void download(Intent intent) throws Exception {
        String primaryUrl = Objects.requireNonNull(intent.getStringExtra(PRIMARY_URL));
        String secondaryUrl = intent.getStringExtra(SECONDARY_URL);
        String title = Objects.requireNonNull(intent.getStringExtra(TITLE));
        String mimeType = Objects.requireNonNull(intent.getStringExtra(MIME_TYPE));
        String extension = Objects.requireNonNull(intent.getStringExtra(EXTENSION));
        DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
        File downloadDirectory = Objects.requireNonNull(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS));
        String token = UUID.randomUUID().toString();
        File primary = new File(downloadDirectory, "newpipe-" + token + "-primary.tmp");
        File secondary = new File(downloadDirectory, "newpipe-" + token + "-secondary.tmp");
        File merged = null;
        long first = -1;
        long second = -1;
        try {
            first = enqueue(manager, primaryUrl, primary);
            if (secondaryUrl != null) second = enqueue(manager, secondaryUrl, secondary);
            await(manager, first);
            if (second != -1) await(manager, second);
            if (primary.length() == 0 || (second != -1 && secondary.length() == 0)) {
                throw new IOException("Downloaded stream is empty");
            }
            File output = primary;
            if (second != -1) {
                merged = File.createTempFile("newpipe-", ".mp4", getCacheDir());
                mux(primary, secondary, merged);
                output = merged;
            }
            publish(output, safeName(title) + "." + extension, mimeType);
        } finally {
            if (first != -1) manager.remove(first);
            if (second != -1) manager.remove(second);
            if (merged != null && merged.exists() && !merged.delete()) {
                Logger.printDebug(() -> "Could not remove NewPipe temporary output");
            }
        }
    }

    private static String safeName(String title) {
        String safe = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (safe.isEmpty()) return "YouTube";
        return safe.length() > 120 ? safe.substring(0, 120) : safe;
    }

    private static long enqueue(DownloadManager manager, String url, File destination) {
        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url))
                .setDestinationUri(Uri.fromFile(destination))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_HIDDEN);
        return manager.enqueue(request);
    }

    private static void await(DownloadManager manager, long id) throws Exception {
        DownloadManager.Query query = new DownloadManager.Query().setFilterById(id);
        while (!Thread.currentThread().isInterrupted()) {
            try (Cursor cursor = manager.query(query)) {
                if (cursor == null || !cursor.moveToFirst()) throw new IOException("Download disappeared");
                int status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
                if (status == DownloadManager.STATUS_SUCCESSFUL) return;
                if (status == DownloadManager.STATUS_FAILED) {
                    int reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON));
                    throw new IOException("Download failed: " + reason);
                }
            }
            Thread.sleep(POLL_INTERVAL_MS);
        }
        throw new InterruptedException("Download interrupted");
    }

    private static void mux(File video, File audio, File output) throws IOException {
        MediaExtractor videoReader = new MediaExtractor();
        MediaExtractor audioReader = new MediaExtractor();
        MediaMuxer muxer = null;
        boolean started = false;
        try {
            videoReader.setDataSource(video.getAbsolutePath());
            audioReader.setDataSource(audio.getAbsolutePath());
            int videoTrack = findTrack(videoReader, "video/");
            int audioTrack = findTrack(audioReader, "audio/");
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int outVideo = muxer.addTrack(videoReader.getTrackFormat(videoTrack));
            int outAudio = muxer.addTrack(audioReader.getTrackFormat(audioTrack));
            muxer.start();
            started = true;
            copyTrack(videoReader, videoTrack, muxer, outVideo);
            copyTrack(audioReader, audioTrack, muxer, outAudio);
            muxer.stop();
            started = false;
        } finally {
            videoReader.release();
            audioReader.release();
            if (muxer != null) {
                if (started) {
                    try { muxer.stop(); } catch (RuntimeException ex) {
                        Logger.printDebug(() -> "Could not stop failed muxer", ex);
                    }
                }
                muxer.release();
            }
        }
    }

    private static int findTrack(MediaExtractor extractor, String prefix) throws IOException {
        for (int index = 0; index < extractor.getTrackCount(); index++) {
            MediaFormat format = extractor.getTrackFormat(index);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return index;
        }
        throw new IOException("Missing " + prefix + " track");
    }

    private static void copyTrack(MediaExtractor reader, int track, MediaMuxer muxer, int outputTrack)
            throws IOException {
        reader.selectTrack(track);
        ByteBuffer buffer = ByteBuffer.allocateDirect(SAMPLE_BUFFER_SIZE);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            buffer.clear();
            if (Build.VERSION.SDK_INT >= 28 && reader.getSampleSize() > buffer.capacity()) {
                throw new IOException("Media sample is too large");
            }
            int size = reader.readSampleData(buffer, 0);
            if (size < 0) return;
            if (size >= buffer.capacity()) throw new IOException("Media sample is too large");
            int flags = reader.getSampleFlags();
            if ((flags & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) {
                throw new IOException("Encrypted tracks cannot be combined");
            }
            if ((flags & MediaExtractor.SAMPLE_FLAG_PARTIAL_FRAME) != 0) {
                throw new IOException("Partial media samples cannot be combined");
            }
            info.set(0, size, reader.getSampleTime(),
                    (flags & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(outputTrack, buffer, info);
            if (!reader.advance()) return;
        }
    }

    private void publish(File source, String name, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT < 29) {
            File directory = Objects.requireNonNull(getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS));
            File destination = new File(directory, UUID.randomUUID() + "-" + name);
            try (InputStream input = new FileInputStream(source); OutputStream out = new FileOutputStream(destination)) {
                copy(input, out);
            } catch (IOException ex) {
                if (!destination.delete()) Logger.printDebug(() -> "Could not remove incomplete file");
                throw ex;
            }
            return;
        }
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/NewPipe");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not create download");
        try {
            try (InputStream input = new FileInputStream(source);
                 OutputStream output = Objects.requireNonNull(getContentResolver().openOutputStream(uri))) {
                copy(input, output);
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            if (getContentResolver().update(uri, values, null, null) != 1) {
                throw new IOException("Could not publish download");
            }
        } catch (Exception ex) {
            getContentResolver().delete(uri, null, null);
            if (ex instanceof IOException io) throw io;
            throw new IOException("Could not save download", ex);
        }
    }

    private static void copy(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[64 * 1024];
        int read;
        while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
    }
}
