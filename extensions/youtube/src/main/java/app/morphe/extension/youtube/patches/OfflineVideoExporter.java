/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4211
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.drawable.Icon;
import android.media.MediaCodec;
import android.media.MediaDataSource;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.util.Base64;

import androidx.annotation.Nullable;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerResponse;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Exports a video saved by the app to a video file in the shared Download folder.
 * <p>
 * The app keeps each saved stream in an ExoPlayer cache, encrypted with AES/CTR
 * using a key from the app preferences. The streams are decrypted while they are read,
 * and the video and audio streams are muxed into one file.
 */
public final class OfflineVideoExporter {

    private static final String KEY_PREFERENCES_NAME = "youtube";
    private static final String KEY_PREFERENCE = "downloads_encryption_key";

    private static final String DATABASE_PREFIX = "offline.";
    private static final String DATABASE_SUFFIX = ".db";
    private static final String OFFLINE_FOLDER = "offline";
    private static final String STREAMS_FOLDER = "streams";
    private static final String CACHE_INDEX_FILE = "cached_content_index.exi";
    private static final String SPAN_FILE_SUFFIX = ".v3.exo";

    private static final int CACHE_INDEX_VERSION = 2;
    private static final int CACHE_INDEX_FLAG_ENCRYPTED = 1;
    /**
     * Streams saved with another encryption, such as streams of paid content, have a different key type.
     */
    private static final int ENCRYPTION_KEY_TYPE_CACHE = 0;

    private static final String CACHE_CIPHER = "AES/CTR/NoPadding";
    private static final String INDEX_CIPHER = "AES/CBC/PKCS5Padding";
    private static final int AES_BLOCK_SIZE = 16;

    private static final String SAVE_FOLDER = "Morphe videos";
    private static final int MAX_FILE_NAME_LENGTH = 100;
    /**
     * How many numbered names are tried if a file with the name already exists.
     */
    private static final int MAX_FILE_NAME_ATTEMPTS = 100;
    private static final int SAMPLE_BUFFER_SIZE = 4 * 1024 * 1024;
    private static final int COPY_BUFFER_SIZE = 64 * 1024;

    private static final String NOTIFICATION_CHANNEL = "morphe_video_exports";
    /**
     * The system drops notification updates that are posted too often.
     */
    private static final long NOTIFICATION_INTERVAL_MILLISECONDS = 1000;
    private static final int PROGRESS_MAX = 100;
    private static final String CANCEL_ACTION_PREFIX = "app.morphe.extension.youtube.EXPORT_CANCEL.";

    /**
     * Same as MediaFormat.MIMETYPE_VIDEO_AV1, which is only available with Android 10 and newer.
     */
    private static final String MIMETYPE_VIDEO_AV1 = "video/av01";

    /**
     * MediaMuxer can write AV1 to MP4 files starting with Android 14.
     */
    private static final int AV1_MUXER_MIN_SDK = 34;

    private static final Set<String> WEBM_MIME_TYPES = Set.of(
            MediaFormat.MIMETYPE_VIDEO_VP8,
            MediaFormat.MIMETYPE_VIDEO_VP9,
            MediaFormat.MIMETYPE_AUDIO_OPUS,
            MediaFormat.MIMETYPE_AUDIO_VORBIS
    );

    private static final Set<String> MP4_MIME_TYPES = Set.of(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            MediaFormat.MIMETYPE_VIDEO_HEVC,
            MIMETYPE_VIDEO_AV1,
            MediaFormat.MIMETYPE_AUDIO_AAC
    );

    private record SavedStream(int itag, long length) {
    }

    private record SavedVideo(String title, List<SavedStream> streams) {
    }

    /**
     * @param uri The exported video file, or null if it can't be opened by other apps.
     */
    private record ExportResult(String location, @Nullable Uri uri, String mimeType) {
    }

    /**
     * Thrown to stop an export that is canceled with the notification.
     */
    private static final class ExportCanceledException extends IOException {
    }

    private OfflineVideoExporter() {
    }

    /**
     * @return If the video is fully saved and can be exported.
     */
    public static boolean isExportable(String videoId) {
        return getSavedVideo(videoId) != null;
    }

    public static void exportVideo(String videoId) {
        Utils.showToastShort(str("morphe_export_download_started"));

        Utils.runOnBackgroundThread(() -> {
            ExportNotification notification = null;
            try {
                SavedVideo video = getSavedVideo(videoId);
                if (video == null) {
                    throw new IllegalStateException("Video is not saved: " + videoId);
                }

                notification = new ExportNotification(videoId, video.title());
                ExportResult result = export(videoId, video, notification);
                String text = str("morphe_export_download_saved", result.location());
                notification.showResult(text, result.uri(), result.mimeType());
                Utils.showToastLong(text);
            } catch (ExportCanceledException ex) {
                Logger.printDebug(() -> "Export canceled: " + videoId);
                notification.dismiss();
                Utils.showToastShort(str("morphe_export_download_canceled"));
            } catch (Exception ex) {
                Logger.printException(() -> "exportVideo failure", ex);
                String text = str("morphe_export_download_failed");
                if (notification != null) {
                    notification.showResult(text, null, null);
                }
                Utils.showToastLong(text);
            }
        });
    }

    /**
     * @return The folder videos are exported to, as shown to the user.
     */
    public static String getFolderName() {
        String folder = Settings.EXPORT_DOWNLOAD_FOLDER.get();
        if (folder.isEmpty()) {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    ? getLegacyFolder().getPath()
                    : Environment.DIRECTORY_DOWNLOADS + File.separator + SAVE_FOLDER;
        }

        // Folders of the device storage have the volume and the path as id, such as "primary:Movies".
        String documentId = DocumentsContract.getTreeDocumentId(Uri.parse(folder));
        final int separator = documentId.indexOf(':');
        return separator < 0 || separator == documentId.length() - 1
                ? documentId
                : documentId.substring(separator + 1);
    }

    /**
     * Before Android 10, the shared folders need a storage permission, so the folder of the app is used.
     */
    private static File getLegacyFolder() {
        return new File(Utils.getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), SAVE_FOLDER);
    }

    // region Saved video data

    /**
     * @return The saved streams of the video, or null if the video is not fully saved.
     */
    @Nullable
    private static SavedVideo getSavedVideo(String videoId) {
        for (File database : getDatabases()) {
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(
                    database.getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
                List<SavedStream> streams = getSavedStreams(db, videoId);
                if (streams != null) {
                    return new SavedVideo(getTitle(db, videoId), streams);
                }
            }
        }

        return null;
    }

    /**
     * Each signed in account has its own database.
     */
    private static List<File> getDatabases() {
        List<File> databases = new ArrayList<>();
        File folder = Utils.getContext().getDatabasePath(DATABASE_PREFIX).getParentFile();
        File[] files = folder == null ? null : folder.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith(DATABASE_PREFIX) && name.endsWith(DATABASE_SUFFIX)) {
                    databases.add(file);
                }
            }
        }
        return databases;
    }

    @Nullable
    private static List<SavedStream> getSavedStreams(SQLiteDatabase db, String videoId) {
        List<SavedStream> streams = new ArrayList<>();

        try (Cursor cursor = db.query(
                "streams",
                new String[]{"itag", "bytes_total", "bytes_transferred", "encryption_key_type"},
                "video_id = ?",
                new String[]{videoId},
                null, null, null)) {
            while (cursor.moveToNext()) {
                final int itag = cursor.getInt(0);
                final long length = cursor.getLong(1);
                if (length <= 0 || cursor.getLong(2) != length) {
                    Logger.printDebug(() -> "Stream is not fully saved: " + videoId + " itag: " + itag);
                    return null;
                }
                final int keyType = cursor.getInt(3);
                if (keyType != ENCRYPTION_KEY_TYPE_CACHE) {
                    Logger.printDebug(() -> "Unsupported encryption: " + keyType + " video: " + videoId);
                    return null;
                }
                streams.add(new SavedStream(itag, length));
            }
        }

        return streams.isEmpty() ? null : streams;
    }

    private static String getTitle(SQLiteDatabase db, String videoId) {
        try (Cursor cursor = db.query(
                "videosV2",
                new String[]{"player_response_proto"},
                "id = ?",
                new String[]{videoId},
                null, null, null)) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                String title = OfflinePlayerResponse.parseFrom(cursor.getBlob(0))
                        .getVideoDetails().getTitle();
                if (!title.isEmpty()) {
                    return title;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read the title of: " + videoId, ex);
        }

        return videoId;
    }

    // endregion

    // region Export

    private static ExportResult export(String videoId, SavedVideo video,
                                       ExportNotification notification) throws Exception {
        SecretKeySpec key = getKey();
        List<StreamSource> sources = new ArrayList<>();
        List<MediaExtractor> extractors = new ArrayList<>();
        List<MediaFormat> formats = new ArrayList<>();

        try {
            for (SavedStream stream : video.streams()) {
                StreamSource source = openStream(key, videoId, stream);
                sources.add(source);

                MediaExtractor extractor = new MediaExtractor();
                extractors.add(extractor);
                extractor.setDataSource(source);
                if (extractor.getTrackCount() == 0) {
                    throw new IOException("No track in stream: " + stream.itag());
                }
                extractor.selectTrack(0);
                formats.add(extractor.getTrackFormat(0));
            }
            notification.setSources(sources);

            String fileName = getFileName(video.title());
            Integer muxerFormat = getMuxerFormat(formats);
            if (muxerFormat != null) {
                final boolean webm = muxerFormat == MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
                String mimeType = webm ? "video/webm" : "video/mp4";
                OutputFile output = OutputFile.create(fileName, webm ? ".webm" : ".mp4", mimeType);
                try {
                    mux(extractors, formats, output.descriptor, muxerFormat, notification);
                    return new ExportResult(output.finish(), output.uri, mimeType);
                } catch (Exception ex) {
                    output.delete();
                    throw ex;
                }
            }

            // The streams can't be muxed together on this device, so each stream is saved as its own file.
            // The streams are complete media files, so they play by themselves.
            ExportResult result = null;
            // If a stream can't be saved, the other streams are deleted too, so no partial export is left.
            List<OutputFile> finished = new ArrayList<>();
            for (int i = 0, size = sources.size(); i < size; i++) {
                String mimeType = getMimeType(formats.get(i));
                final boolean audio = mimeType.startsWith("audio/");
                final boolean webm = WEBM_MIME_TYPES.contains(formats.get(i).getString(MediaFormat.KEY_MIME));
                String extension = webm ? ".webm" : (audio ? ".m4a" : ".mp4");
                OutputFile output = OutputFile.create(
                        fileName + (audio ? " (audio)" : ""),
                        extension,
                        mimeType
                );
                try {
                    copy(sources.get(i), output.descriptor, notification);
                    String location = output.finish();
                    finished.add(output);
                    // The notification opens the video file.
                    if (result == null || !audio) {
                        result = new ExportResult(location, output.uri, mimeType);
                    }
                } catch (Exception ex) {
                    output.delete();
                    for (OutputFile file : finished) {
                        file.delete();
                    }
                    throw ex;
                }
            }
            return result;
        } finally {
            for (MediaExtractor extractor : extractors) {
                extractor.release();
            }
            for (StreamSource source : sources) {
                source.close();
            }
        }
    }

    private static SecretKeySpec getKey() {
        String key = Utils.getContext()
                .getSharedPreferences(KEY_PREFERENCES_NAME, Context.MODE_PRIVATE)
                .getString(KEY_PREFERENCE, null);
        if (key == null) {
            throw new IllegalStateException("No downloads encryption key");
        }
        return new SecretKeySpec(Base64.decode(key, Base64.DEFAULT), "AES");
    }

    /**
     * @return The MediaMuxer output format for the streams, or null if the streams can't be muxed together.
     */
    @Nullable
    private static Integer getMuxerFormat(List<MediaFormat> formats) {
        boolean webm = false;
        boolean mp4 = false;
        for (MediaFormat format : formats) {
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (WEBM_MIME_TYPES.contains(mime)) {
                webm = true;
            } else if (MP4_MIME_TYPES.contains(mime)) {
                if (MIMETYPE_VIDEO_AV1.equals(mime) && Build.VERSION.SDK_INT < AV1_MUXER_MIN_SDK) {
                    return null;
                }
                mp4 = true;
            } else {
                return null;
            }
        }

        if (webm == mp4) {
            return null;
        }
        return webm
                ? MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
                : MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
    }

    private static String getMimeType(MediaFormat format) {
        String mime = format.getString(MediaFormat.KEY_MIME);
        final boolean audio = mime != null && mime.startsWith("audio/");
        if (WEBM_MIME_TYPES.contains(mime)) {
            return audio ? "audio/webm" : "video/webm";
        }
        return audio ? "audio/mp4" : "video/mp4";
    }

    private static String getFileName(String title) {
        String name = title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        return name.length() > MAX_FILE_NAME_LENGTH
                ? name.substring(0, MAX_FILE_NAME_LENGTH).trim()
                : name;
    }

    /**
     * Writes the samples of all streams in the order of their time, so the file is interleaved.
     */
    private static void mux(List<MediaExtractor> extractors, List<MediaFormat> formats,
                            ParcelFileDescriptor descriptor, int outputFormat,
                            ExportNotification notification) throws IOException {
        MediaMuxer muxer = new MediaMuxer(descriptor.getFileDescriptor(), outputFormat);
        try {
            final int count = extractors.size();
            int[] tracks = new int[count];
            for (int i = 0; i < count; i++) {
                tracks[i] = muxer.addTrack(formats.get(i));
            }
            muxer.start();

            ByteBuffer buffer = ByteBuffer.allocate(SAMPLE_BUFFER_SIZE);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                int next = -1;
                long nextTime = Long.MAX_VALUE;
                for (int i = 0; i < count; i++) {
                    final long time = extractors.get(i).getSampleTime();
                    if (time >= 0 && time < nextTime) {
                        next = i;
                        nextTime = time;
                    }
                }
                if (next < 0) {
                    break;
                }

                MediaExtractor extractor = extractors.get(next);
                final int size = extractor.readSampleData(buffer, 0);
                if (size < 0) {
                    break;
                }
                final boolean keyFrame = (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0;
                info.set(0, size, nextTime, keyFrame ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
                muxer.writeSampleData(tracks[next], buffer, info);
                extractor.advance();
                notification.showProgress();
            }

            muxer.stop();
        } finally {
            muxer.release();
        }
    }

    private static void copy(StreamSource source, ParcelFileDescriptor descriptor,
                             ExportNotification notification) throws IOException {
        try (OutputStream output = new FileOutputStream(descriptor.getFileDescriptor())) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            long position = 0;
            int read;
            while ((read = source.readAt(position, buffer, 0, buffer.length)) > 0) {
                output.write(buffer, 0, read);
                position += read;
                notification.showProgress();
            }
        }
    }

    /**
     * A file in the folder the user picked, or by default in the shared Download folder.
     */
    private static final class OutputFile {
        @Nullable
        private final Uri uri;
        /**
         * If the uri is of a document in the folder the user picked, and not of the media store.
         */
        private final boolean document;
        @Nullable
        private final File file;
        private final ParcelFileDescriptor descriptor;
        private final String location;

        private OutputFile(@Nullable Uri uri, boolean document, @Nullable File file,
                           ParcelFileDescriptor descriptor, String location) {
            this.uri = uri;
            this.document = document;
            this.file = file;
            this.descriptor = descriptor;
            this.location = location;
        }

        /**
         * Files are never replaced. If the name is taken, a number is added to it.
         */
        static OutputFile create(String name, String extension, String mimeType) throws IOException {
            ContentResolver resolver = Utils.getContext().getContentResolver();

            String pickedFolder = Settings.EXPORT_DOWNLOAD_FOLDER.get();
            if (!pickedFolder.isEmpty()) {
                Uri tree = Uri.parse(pickedFolder);
                Uri folder = DocumentsContract.buildDocumentUriUsingTree(
                        tree, DocumentsContract.getTreeDocumentId(tree));
                // The documents provider adds a number to a name that is taken.
                Uri uri = DocumentsContract.createDocument(resolver, folder, mimeType, name + extension);
                if (uri == null) {
                    throw new IOException("Could not create file: " + name + extension + " in: " + tree);
                }
                ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw");
                if (descriptor == null) {
                    DocumentsContract.deleteDocument(resolver, uri);
                    throw new IOException("No file descriptor for: " + uri);
                }
                return new OutputFile(uri, true, null, descriptor, getFolderName());
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                File folder = getLegacyFolder();
                if (!folder.exists() && !folder.mkdirs()) {
                    throw new IOException("Could not create folder: " + folder);
                }
                for (int attempt = 0; attempt < MAX_FILE_NAME_ATTEMPTS; attempt++) {
                    File file = new File(folder, getNumberedName(name, extension, attempt));
                    if (file.createNewFile()) {
                        ParcelFileDescriptor descriptor = ParcelFileDescriptor.open(file,
                                ParcelFileDescriptor.MODE_READ_WRITE);
                        return new OutputFile(null, false, file, descriptor, folder.getPath());
                    }
                }
                throw new IOException("Could not create file: " + name + extension);
            }

            String folder = Environment.DIRECTORY_DOWNLOADS + File.separator + SAVE_FOLDER;
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, folder);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);

            // The media store renames a file whose name is taken, but not if the file was deleted
            // without the media store knowing, such as by a file manager. Then the insert fails.
            Uri uri = null;
            for (int attempt = 0; uri == null && attempt < MAX_FILE_NAME_ATTEMPTS; attempt++) {
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, getNumberedName(name, extension, attempt));
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            }
            if (uri == null) {
                throw new IOException("Could not create file: " + name + extension);
            }
            ParcelFileDescriptor descriptor = resolver.openFileDescriptor(uri, "rw");
            if (descriptor == null) {
                resolver.delete(uri, null, null);
                throw new IOException("No file descriptor for: " + uri);
            }
            return new OutputFile(uri, false, null, descriptor, folder);
        }

        private static String getNumberedName(String name, String extension, int number) {
            return (number == 0 ? name : name + " (" + number + ")") + extension;
        }

        /**
         * @return Where the file is saved, as shown to the user.
         */
        String finish() throws IOException {
            descriptor.close();
            if (uri != null && !document) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                Utils.getContext().getContentResolver().update(uri, values, null, null);
            }
            return location;
        }

        void delete() throws IOException {
            descriptor.close();
            ContentResolver resolver = Utils.getContext().getContentResolver();
            if (uri != null && document) {
                DocumentsContract.deleteDocument(resolver, uri);
            } else if (uri != null) {
                resolver.delete(uri, null, null);
            } else if (file != null && !file.delete()) {
                Logger.printInfo(() -> "Could not delete: " + file);
            }
        }
    }

    /**
     * Shows the progress of an export, which takes a while for long videos.
     */
    private static final class ExportNotification {
        private final int id;
        private final String title;
        private final PendingIntent cancelIntent;
        private final BroadcastReceiver cancelReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                canceled = true;
            }
        };
        private volatile boolean canceled;
        private List<StreamSource> sources = List.of();
        private long nextUpdateTime;

        @SuppressLint("UnspecifiedRegisterReceiverFlag") // The flags only exist with Android 13 and newer.
        ExportNotification(String videoId, String title) {
            this.id = videoId.hashCode();
            this.title = title;

            Context context = Utils.getContext();
            String action = CANCEL_ACTION_PREFIX + id;
            IntentFilter filter = new IntentFilter(action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(cancelReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(cancelReceiver, filter);
            }
            cancelIntent = PendingIntent.getBroadcast(context, id,
                    new Intent(action).setPackage(context.getPackageName()),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

            postProgress();
        }

        void setSources(List<StreamSource> sources) {
            this.sources = sources;
        }

        /**
         * Called while the export is written.
         *
         * @throws ExportCanceledException If the export was canceled with the notification.
         */
        void showProgress() throws ExportCanceledException {
            if (canceled) {
                throw new ExportCanceledException();
            }
            postProgress();
        }

        private void postProgress() {
            final long now = System.currentTimeMillis();
            if (now < nextUpdateTime) {
                return;
            }
            nextUpdateTime = now + NOTIFICATION_INTERVAL_MILLISECONDS;

            long read = 0;
            long total = 0;
            for (StreamSource source : sources) {
                read += source.getReadEnd();
                total += source.getSize();
            }
            final int progress = total == 0 ? 0 : (int) (read * PROGRESS_MAX / total);

            Notification.Builder builder = createBuilder()
                    .setSmallIcon(android.R.drawable.stat_sys_download)
                    .setContentText(str("morphe_export_download_progress", progress))
                    .setProgress(PROGRESS_MAX, progress, total == 0)
                    .setOngoing(true)
                    .addAction(new Notification.Action.Builder(
                            Icon.createWithResource(Utils.getContext(), android.R.drawable.ic_menu_close_clear_cancel),
                            Utils.getContext().getString(android.R.string.cancel),
                            cancelIntent).build());
            post(builder);
        }

        /**
         * @param uri The file the notification opens, or null if there is none.
         */
        void showResult(String text, @Nullable Uri uri, @Nullable String mimeType) {
            stopCancelReceiver();
            Notification.Builder builder = createBuilder()
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentText(text)
                    .setAutoCancel(true);
            if (uri != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW)
                        .setDataAndType(uri, mimeType)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                builder.setContentIntent(PendingIntent.getActivity(Utils.getContext(), id, intent,
                        PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT));
            }
            post(builder);
        }

        /**
         * Removes the notification of a canceled export.
         */
        void dismiss() {
            stopCancelReceiver();
            getManager().cancel(id);
        }

        private void stopCancelReceiver() {
            Utils.getContext().unregisterReceiver(cancelReceiver);
            cancelIntent.cancel();
        }

        private static NotificationManager getManager() {
            return (NotificationManager) Utils.getContext().getSystemService(Context.NOTIFICATION_SERVICE);
        }

        private Notification.Builder createBuilder() {
            return new Notification.Builder(Utils.getContext(), NOTIFICATION_CHANNEL)
                    .setContentTitle(title)
                    .setOnlyAlertOnce(true);
        }

        @SuppressLint("NotificationPermission") // The app declares POST_NOTIFICATIONS.
        private void post(Notification.Builder builder) {
            NotificationManager manager = getManager();
            manager.createNotificationChannel(new NotificationChannel(NOTIFICATION_CHANNEL,
                    str("morphe_export_download_notification_channel"),
                    NotificationManager.IMPORTANCE_LOW));
            manager.notify(id, builder.build());
        }
    }

    // endregion

    // region Cache

    private static StreamSource openStream(SecretKeySpec key, String videoId, SavedStream stream) throws Exception {
        // Cache keys are the video id, the itag and the last modified time of the stream,
        // such as "videoId.140.1758733303850123". If the video has several audio tracks,
        // the audio track is after the itag: "videoId.140:audioTrack.1758733303850123".
        final String keyPrefix = videoId + "." + stream.itag();

        for (File streamsFolder : getStreamsFolders()) {
            Map<Integer, String> index = readCacheIndex(new File(streamsFolder, CACHE_INDEX_FILE), key);

            int id = -1;
            String cacheKey = null;
            long newestTime = -1;
            for (Map.Entry<Integer, String> entry : index.entrySet()) {
                String entryKey = entry.getValue();
                if (entryKey.length() > keyPrefix.length() && entryKey.startsWith(keyPrefix)
                        && (entryKey.charAt(keyPrefix.length()) == '.' || entryKey.charAt(keyPrefix.length()) == ':')) {
                    final long time = Long.parseLong(entryKey.substring(entryKey.lastIndexOf('.') + 1));
                    if (time > newestTime) {
                        newestTime = time;
                        id = entry.getKey();
                        cacheKey = entryKey;
                    }
                }
            }
            if (cacheKey == null) {
                continue;
            }

            TreeMap<Long, File> spans = getSpans(streamsFolder, id);
            long end = 0;
            for (Map.Entry<Long, File> span : spans.entrySet()) {
                if (span.getKey() != end) {
                    break;
                }
                end += span.getValue().length();
            }
            if (end != stream.length()) {
                throw new IOException("Stream is incomplete: " + cacheKey
                        + " saved: " + end + " length: " + stream.length());
            }

            return new StreamSource(key, cacheKey, spans, stream.length());
        }

        throw new IOException("Stream not found: " + keyPrefix);
    }

    /**
     * Each account has its own folder, on the internal storage or on an SD card.
     */
    private static List<File> getStreamsFolders() {
        Context context = Utils.getContext();
        List<File> roots = new ArrayList<>();
        for (File root : context.getExternalFilesDirs(null)) {
            if (root != null) {
                roots.add(root);
            }
        }
        roots.add(context.getFilesDir());

        List<File> folders = new ArrayList<>();
        for (File root : roots) {
            File[] accounts = new File(root, OFFLINE_FOLDER).listFiles();
            if (accounts == null) {
                continue;
            }
            for (File account : accounts) {
                File streams = new File(account, STREAMS_FOLDER);
                if (new File(streams, CACHE_INDEX_FILE).isFile()) {
                    folders.add(streams);
                }
            }
        }
        return folders;
    }

    /**
     * Reads the ExoPlayer cache index, which maps the ids in the span file names to the cache keys.
     */
    private static Map<Integer, String> readCacheIndex(File file, SecretKeySpec key) throws Exception {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            final int version = input.readInt();
            if (version != CACHE_INDEX_VERSION) {
                throw new IOException("Unsupported cache index version: " + version);
            }

            DataInputStream data = input;
            if ((input.readInt() & CACHE_INDEX_FLAG_ENCRYPTED) != 0) {
                byte[] iv = new byte[AES_BLOCK_SIZE];
                input.readFully(iv);
                Cipher cipher = Cipher.getInstance(INDEX_CIPHER);
                cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
                data = new DataInputStream(new CipherInputStream(input, cipher));
            }

            Map<Integer, String> index = new HashMap<>();
            final int count = data.readInt();
            for (int i = 0; i < count; i++) {
                final int id = data.readInt();
                index.put(id, data.readUTF());
                // Metadata, such as the redirected url.
                final int metadataCount = data.readInt();
                for (int m = 0; m < metadataCount; m++) {
                    data.readUTF();
                    data.readFully(new byte[data.readInt()]);
                }
            }
            return index;
        }
    }

    /**
     * Span files are named "id.position.time.v3.exo" and are in numbered sub folders.
     */
    private static TreeMap<Long, File> getSpans(File streamsFolder, int id) {
        TreeMap<Long, File> spans = new TreeMap<>();
        String prefix = id + ".";
        File[] folders = streamsFolder.listFiles(File::isDirectory);
        if (folders == null) {
            return spans;
        }
        for (File folder : folders) {
            File[] files = folder.listFiles();
            if (files == null) {
                continue;
            }
            for (File file : files) {
                String name = file.getName();
                if (name.startsWith(prefix) && name.endsWith(SPAN_FILE_SUFFIX)) {
                    String position = name.substring(prefix.length(), name.indexOf('.', prefix.length()));
                    spans.put(Long.parseLong(position), file);
                }
            }
        }
        return spans;
    }

    /**
     * Reads a saved stream and decrypts it, the same as the ExoPlayer AesCipherDataSource of the app.
     */
    private static final class StreamSource extends MediaDataSource {
        private final SecretKeySpec key;
        private final long nonce;
        private final TreeMap<Long, File> spans;
        private final long length;
        private final Map<Long, RandomAccessFile> openFiles = new HashMap<>();
        /**
         * The end of the furthest read, for the progress of the export.
         */
        private volatile long readEnd;

        /**
         * The cipher is reused for sequential reads, and set up again when reading another position.
         */
        @Nullable
        private Cipher cipher;
        private long cipherPosition = -1;

        StreamSource(SecretKeySpec key, String cacheKey, TreeMap<Long, File> spans, long length) {
            this.key = key;
            this.nonce = getNonce(cacheKey);
            this.spans = spans;
            this.length = length;
        }

        /**
         * The FNV-1 hash ExoPlayer uses as the nonce of a cache key.
         */
        private static long getNonce(String cacheKey) {
            long hash = 0;
            for (int i = 0, length = cacheKey.length(); i < length; i++) {
                hash ^= cacheKey.charAt(i);
                hash += (hash << 1) + (hash << 4) + (hash << 5) + (hash << 7) + (hash << 8) + (hash << 40);
            }
            return hash;
        }

        @Override
        public synchronized int readAt(long position, byte[] buffer, int offset, int size) throws IOException {
            if (position >= length) {
                return -1;
            }

            Map.Entry<Long, File> span = spans.floorEntry(position);
            if (span == null) {
                throw new IOException("No span at: " + position);
            }
            final long spanStart = span.getKey();
            final int count = (int) Math.min(size, spanStart + span.getValue().length() - position);

            RandomAccessFile file = openFiles.get(spanStart);
            if (file == null) {
                file = new RandomAccessFile(span.getValue(), "r");
                openFiles.put(spanStart, file);
            }
            file.seek(position - spanStart);
            file.readFully(buffer, offset, count);

            decrypt(position, buffer, offset, count);
            readEnd = Math.max(readEnd, position + count);
            return count;
        }

        private void decrypt(long position, byte[] buffer, int offset, int count) throws IOException {
            try {
                if (cipher == null || position != cipherPosition) {
                    cipher = Cipher.getInstance(CACHE_CIPHER);
                    byte[] iv = ByteBuffer.allocate(AES_BLOCK_SIZE)
                            .putLong(nonce)
                            .putLong(position / AES_BLOCK_SIZE)
                            .array();
                    cipher.init(Cipher.DECRYPT_MODE, key, new IvParameterSpec(iv));
                    final int skip = (int) (position % AES_BLOCK_SIZE);
                    if (skip > 0) {
                        cipher.update(new byte[skip]);
                    }
                }

                final int decrypted = cipher.update(buffer, offset, count, buffer, offset);
                if (decrypted != count) {
                    throw new IOException("Decrypted " + decrypted + " of " + count + " bytes");
                }
                cipherPosition = position + count;
            } catch (GeneralSecurityException ex) {
                throw new IOException(ex);
            }
        }

        @Override
        public long getSize() {
            return length;
        }

        long getReadEnd() {
            return readEnd;
        }

        @Override
        public synchronized void close() throws IOException {
            for (RandomAccessFile file : openFiles.values()) {
                file.close();
            }
            openFiles.clear();
        }
    }

    // endregion
}
