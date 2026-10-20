/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.utils;

import static app.morphe.extension.shared.StringRef.str;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * The download manager can only save to shared folders, so videos for a picked folder are downloaded to app storage,
 * and copied to the folder when the download completes.
 * <p>
 * Pending videos are stored, because the app can be closed before a download completes.
 */
public final class PickedFolderVideos {

    private static final String PREFERENCES_NAME = "morphe_download_location";
    private static final String PENDING_VIDEOS_KEY = "pending_videos";

    private static final String FILE_KEY = "file";
    private static final String TREE_KEY = "tree";
    private static final String SUBFOLDER_KEY = "subfolder";
    private static final String FILE_NAME_KEY = "fileName";

    private record Video(File file, String tree, @Nullable String subfolder, String fileName) {
    }

    /**
     * Set when the destination is set, and stored when the download is enqueued.
     */
    @Nullable
    private static volatile Video preparedVideo;

    @Nullable
    private static BroadcastReceiver downloadCompleteReceiver;

    private static final AtomicBoolean pendingVideosResumed = new AtomicBoolean();

    private PickedFolderVideos() {
    }

    /**
     * @param subfolder Subfolders of the picked folder separated by '/', or null.
     */
    public static DownloadManager.Request setDestination(DownloadManager.Request request,
                                                         String tree, @Nullable String subfolder, String fileName) {
        // Unique, so videos with the same name can be downloaded at the same time.
        File file = new File(Utils.getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS),
                UUID.randomUUID() + "_" + fileName);
        DownloadManager.Request newRequest = request.setDestinationUri(Uri.fromFile(file));
        preparedVideo = new Video(file, tree, subfolder, fileName);
        return newRequest;
    }

    public static long enqueue(DownloadManager downloadManager, DownloadManager.Request request) {
        Video video = preparedVideo;
        preparedVideo = null;
        if (video != null) {
            // The completed notification would open the file in app storage, which is deleted after it's copied.
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE);
        }

        final long id = downloadManager.enqueue(request);
        if (video != null) {
            try {
                storePendingVideo(id, video);
                registerDownloadCompleteReceiver();
            } catch (Exception ex) {
                Logger.printException(() -> "enqueue failure", ex);
                Utils.showToastLong(str("morphe_download_location_video_failed"));
            }
        }
        return id;
    }

    /**
     * Copies videos whose download completed while the app was closed, and waits for the others.
     * Only runs once per app start.
     */
    public static void resumePendingVideos() {
        if (pendingVideosResumed.getAndSet(true)) return;

        Utils.runOnBackgroundThread(() -> {
            try {
                JSONObject pendingVideos = getPendingVideos();
                Iterator<String> ids = pendingVideos.keys();
                boolean downloading = false;
                while (ids.hasNext()) {
                    final long id = Long.parseLong(ids.next());
                    final int status = getDownloadStatus(id);
                    if (status == DownloadManager.STATUS_SUCCESSFUL || status == DownloadManager.STATUS_FAILED
                            || status == -1) {
                        Video video = removePendingVideo(id);
                        if (video != null) onDownloadComplete(id, video);
                    } else {
                        downloading = true;
                    }
                }
                if (downloading) registerDownloadCompleteReceiver();
            } catch (Exception ex) {
                Logger.printException(() -> "resumePendingVideos failure", ex);
            }
        });
    }

    // The exported flag is only required on Android 13 and newer.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private static synchronized void registerDownloadCompleteReceiver() {
        if (downloadCompleteReceiver != null) return;

        downloadCompleteReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                final long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                Video video = removePendingVideo(id);
                if (video != null) {
                    Utils.runOnBackgroundThread(() -> onDownloadComplete(id, video));
                }
            }
        };

        Context context = Utils.getContext();
        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The download manager is another app.
            context.registerReceiver(downloadCompleteReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            context.registerReceiver(downloadCompleteReceiver, filter);
        }
    }

    private static void onDownloadComplete(long id, Video video) {
        try {
            if (getDownloadStatus(id) != DownloadManager.STATUS_SUCCESSFUL) return;

            try {
                DocumentTree.copy(video.file, DocumentTree.createFile(video.tree, video.subfolder, video.fileName, null));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not save video to picked folder", ex);
                saveToDownloadsFolder(video);
                Utils.showToastLong(str("morphe_download_location_folder_unavailable"));
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onDownloadComplete failure", ex);
            Utils.showToastLong(str("morphe_download_location_video_failed"));
        } finally {
            //noinspection ResultOfMethodCallIgnored
            video.file.delete();
        }
    }

    /**
     * Saves to the downloads folder like Reddit does, if the picked folder was deleted or its permission was removed.
     */
    private static void saveToDownloadsFolder(Video video) throws IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            // Reddit asks for the storage permission before it downloads a video on Android 9.
            File folder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            Files.copy(video.file.toPath(), new File(folder, video.fileName).toPath());
            return;
        }

        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, video.fileName);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
        ContentResolver resolver = Utils.getContext().getContentResolver();
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("Could not save to the downloads folder");
        DocumentTree.copy(video.file, uri);
    }

    /**
     * @return The status, or -1 if the download no longer exists.
     */
    private static int getDownloadStatus(long id) {
        DownloadManager downloadManager = Utils.getContext().getSystemService(DownloadManager.class);
        try (Cursor cursor = downloadManager.query(new DownloadManager.Query().setFilterById(id))) {
            return cursor != null && cursor.moveToFirst()
                    ? cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    : -1;
        }
    }

    private static SharedPreferences getPreferences() {
        return Utils.getContext().getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static synchronized JSONObject getPendingVideos() {
        try {
            return new JSONObject(getPreferences().getString(PENDING_VIDEOS_KEY, "{}"));
        } catch (Exception ex) {
            Logger.printException(() -> "Invalid pending videos", ex);
            return new JSONObject();
        }
    }

    private static synchronized void storePendingVideo(long id, Video video) throws Exception {
        JSONObject json = new JSONObject();
        json.put(FILE_KEY, video.file.getAbsolutePath());
        json.put(TREE_KEY, video.tree);
        if (video.subfolder != null) json.put(SUBFOLDER_KEY, video.subfolder);
        json.put(FILE_NAME_KEY, video.fileName);

        JSONObject pendingVideos = getPendingVideos();
        pendingVideos.put(String.valueOf(id), json);
        getPreferences().edit().putString(PENDING_VIDEOS_KEY, pendingVideos.toString()).apply();
    }

    @Nullable
    private static synchronized Video removePendingVideo(long id) {
        JSONObject pendingVideos = getPendingVideos();
        JSONObject json = (JSONObject) pendingVideos.remove(String.valueOf(id));
        if (json == null) return null;

        getPreferences().edit().putString(PENDING_VIDEOS_KEY, pendingVideos.toString()).apply();
        return new Video(
                new File(json.optString(FILE_KEY)),
                json.optString(TREE_KEY),
                json.has(SUBFOLDER_KEY) ? json.optString(SUBFOLDER_KEY) : null,
                json.optString(FILE_NAME_KEY)
        );
    }
}
