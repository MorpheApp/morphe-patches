/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches.downloads;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.database.MergeCursor;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.music.patches.DownloadsPatch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Virtualizes MediaStore Audio queries and file descriptors for downloaded tracks.
 * Implements a Dual Device Files Architecture that blends internal private storage
 * downloads seamlessly with native Android MediaStore tracks, routing them directly
 * to YouTube Music's native sideloaded tracks engine and ExoPlayer.
 */
public final class VirtualMediaProvider {

    public static final long BASE_VIRTUAL_ID = 900_000_000L;

    private static final String[] DEFAULT_PROJECTION = {
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.DISPLAY_NAME,
            "album_id",
            "artist_id",
            "date_added",
            "date_modified"
    };

    private static final Map<Long, File> ID_TO_AUDIO_FILE = new ConcurrentHashMap<>();
    private static final Map<Long, File> ID_TO_ARTWORK_FILE = new ConcurrentHashMap<>();
    private static final Map<String, Long> VIDEO_ID_TO_ID = new ConcurrentHashMap<>();
    private static final Map<Long, String> ID_TO_VIDEO_ID = new ConcurrentHashMap<>();

    private VirtualMediaProvider() {}

    /**
     * Intercepts ContentResolver.query calls.
     * When targeting MediaStore audio, packages all downloaded tracks from private storage
     * into a virtual MatrixCursor and merges with actual device MediaStore results if present.
     */
    public static Cursor query(
            ContentResolver resolver,
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder
    ) {
        if (!DownloadsPatch.isCustomDownloadsActive() || uri == null) {
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        }

        String uriStr = uri.toString();
        boolean isAudioUri = uriStr.contains("audio") || uri.equals(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI);
        if (!isAudioUri) {
            return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        }

        Logger.printDebug(() -> "VirtualMediaProvider: Intercepted MediaStore query for: " + uri);

        MatrixCursor virtualCursor = buildVirtualCursor(projection);

        // Dual Device Files Architecture: Attempt to query real device files as well
        Cursor realCursor = null;
        try {
            realCursor = resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        } catch (SecurityException se) {
            Logger.printDebug(() -> "VirtualMediaProvider: External storage permission denied, using virtual downloads only");
        } catch (Exception ex) {
            Logger.printException(() -> "VirtualMediaProvider: Real MediaStore query failed", ex);
        }

        if (realCursor != null && realCursor.getCount() > 0) {
            Logger.printDebug(() -> "VirtualMediaProvider: Merging " + virtualCursor.getCount()
                    + " virtual downloads with " + realCursor.getCount() + " device tracks");
            return new MergeCursor(new Cursor[]{ virtualCursor, realCursor });
        }

        if (realCursor != null) {
            realCursor.close();
        }

        return virtualCursor;
    }

    /**
     * 6-parameter query overload with CancellationSignal.
     */
    public static Cursor query(
            ContentResolver resolver,
            Uri uri,
            String[] projection,
            String selection,
            String[] selectionArgs,
            String sortOrder,
            CancellationSignal cancellationSignal
    ) {
        return query(resolver, uri, projection, selection, selectionArgs, sortOrder);
    }

    /**
     * Builds an in-memory MatrixCursor containing all locally downloaded tracks.
     */
    private static MatrixCursor buildVirtualCursor(@Nullable String[] projection) {
        String[] cols = (projection != null && projection.length > 0) ? projection : DEFAULT_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(cols);

        List<OfflineTrack> tracks = scanDownloadedTracks();
        ID_TO_AUDIO_FILE.clear();
        ID_TO_ARTWORK_FILE.clear();
        VIDEO_ID_TO_ID.clear();
        ID_TO_VIDEO_ID.clear();

        long currentId = BASE_VIRTUAL_ID;
        for (OfflineTrack track : tracks) {
            long virtualId = currentId++;
            ID_TO_AUDIO_FILE.put(virtualId, track.audioFile());
            if (track.artworkFile() != null && track.artworkFile().isFile()) {
                ID_TO_ARTWORK_FILE.put(virtualId, track.artworkFile());
            }
            VIDEO_ID_TO_ID.put(track.videoId(), virtualId);
            ID_TO_VIDEO_ID.put(virtualId, track.videoId());

            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String col : cols) {
                switch (col) {
                    case MediaStore.Audio.Media._ID:
                        row.add(virtualId);
                        break;
                    case MediaStore.Audio.Media.TITLE:
                        row.add(track.displayTitle());
                        break;
                    case MediaStore.Audio.Media.ARTIST:
                        row.add(track.displayArtist());
                        break;
                    case MediaStore.Audio.Media.ALBUM:
                        row.add(track.album().isEmpty() ? "Downloads" : track.album());
                        break;
                    case MediaStore.Audio.Media.DURATION:
                        row.add((long) track.durationSeconds() * 1000L);
                        break;
                    case MediaStore.Audio.Media.DATA:
                        row.add(track.audioFile().getAbsolutePath());
                        break;
                    case MediaStore.Audio.Media.SIZE:
                        row.add(track.sizeBytes());
                        break;
                    case MediaStore.Audio.Media.MIME_TYPE:
                        row.add(getMimeType(track.audioFile().getName()));
                        break;
                    case MediaStore.Audio.Media.IS_MUSIC:
                        row.add(1);
                        break;
                    case MediaStore.Audio.Media.DISPLAY_NAME:
                        row.add(track.audioFile().getName());
                        break;
                    case "album_id":
                    case "artist_id":
                        row.add(virtualId);
                        break;
                    case "date_added":
                    case "date_modified":
                        row.add(track.audioFile().lastModified() / 1000L);
                        break;
                    default:
                        row.add(null);
                        break;
                }
            }
        }

        Logger.printDebug(() -> "VirtualMediaProvider: Built MatrixCursor with " + tracks.size() + " virtual tracks");
        return cursor;
    }

    /**
     * Intercepts ContentResolver.openAssetFileDescriptor calls.
     * When ExoPlayer or YTM requests a virtual track, opens the internal private file directly
     * via ParcelFileDescriptor without requiring MediaStore provider permissions.
     */
    @Nullable
    public static AssetFileDescriptor openAssetFileDescriptor(
            ContentResolver resolver,
            Uri uri,
            String mode
    ) throws FileNotFoundException {
        if (uri == null) {
            return resolver.openAssetFileDescriptor(uri, mode);
        }

        File targetFile = resolveVirtualFile(uri);
        if (targetFile != null && targetFile.isFile()) {
            Logger.printDebug(() -> "VirtualMediaProvider: Serving virtual file for: " + uri);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY);
            return new AssetFileDescriptor(pfd, 0, targetFile.length());
        }

        return resolver.openAssetFileDescriptor(uri, mode);
    }

    /**
     * 4-parameter overload with CancellationSignal.
     */
    @Nullable
    public static AssetFileDescriptor openAssetFileDescriptor(
            ContentResolver resolver,
            Uri uri,
            String mode,
            CancellationSignal cancellationSignal
    ) throws FileNotFoundException {
        return openAssetFileDescriptor(resolver, uri, mode);
    }

    /**
     * Intercepts ContentResolver.openFileDescriptor calls.
     */
    @Nullable
    public static ParcelFileDescriptor openFileDescriptor(
            ContentResolver resolver,
            Uri uri,
            String mode
    ) throws FileNotFoundException {
        if (uri == null) {
            return resolver.openFileDescriptor(uri, mode);
        }

        File targetFile = resolveVirtualFile(uri);
        if (targetFile != null && targetFile.isFile()) {
            Logger.printDebug(() -> "VirtualMediaProvider: Serving virtual PFD for: " + uri);
            return ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY);
        }

        return resolver.openFileDescriptor(uri, mode);
    }

    /**
     * Resolves a Content/File URI to the underlying downloaded audio or artwork file.
     */
    @Nullable
    public static File resolveVirtualFile(Uri uri) {
        if (uri == null) return null;

        // 1. Try parsing virtual ID from URI
        try {
            long id = ContentUris.parseId(uri);
            if (id >= BASE_VIRTUAL_ID) {
                String uriStr = uri.toString();
                if (uriStr.contains("albumart")) {
                    File art = ID_TO_ARTWORK_FILE.get(id);
                    if (art != null && art.isFile()) return art;
                }
                File audio = ID_TO_AUDIO_FILE.get(id);
                if (audio != null && audio.isFile()) return audio;
            }
        } catch (Exception ignored) {
        }

        // 2. Check path directly
        String path = uri.getPath();
        if (path != null) {
            File directFile = new File(path);
            if (directFile.isFile()) return directFile;

            // Search by videoId in path
            for (Map.Entry<String, Long> entry : VIDEO_ID_TO_ID.entrySet()) {
                if (path.contains(entry.getKey())) {
                    File audio = ID_TO_AUDIO_FILE.get(entry.getValue());
                    if (audio != null && audio.isFile()) return audio;
                }
            }
        }

        return null;
    }

    /**
     * Bypasses Android storage permission checks for virtual sideloaded tracks.
     */
    public static int checkPermission(Context context, String permission) {
        if (DownloadsPatch.isCustomDownloadsActive()) {
            if ("android.permission.READ_EXTERNAL_STORAGE".equals(permission)
                    || "android.permission.READ_MEDIA_AUDIO".equals(permission)
                    || "android.permission.WRITE_EXTERNAL_STORAGE".equals(permission)) {
                Logger.printDebug(() -> "VirtualMediaProvider: Granted virtual permission for " + permission);
                return PackageManager.PERMISSION_GRANTED; // 0
            }
        }
        return context != null ? context.checkSelfPermission(permission) : PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Redirects stock Downloads tab browseId ("FEmusic_offline") to the native
     * sideloaded tracks engine ("FEmusic_library_sideloaded_tracks").
     */
    public static String redirectBrowseId(String browseId) {
        if (DownloadsPatch.isCustomDownloadsActive() && "FEmusic_offline".equals(browseId)) {
            Logger.printDebug(() -> "VirtualMediaProvider: Redirected FEmusic_offline -> FEmusic_library_sideloaded_tracks");
            return "FEmusic_library_sideloaded_tracks";
        }
        return browseId;
    }

    /**
     * Returns a content URI or file URI for a downloaded video ID.
     */
    @Nullable
    public static Uri getVirtualMediaUri(String videoId) {
        File file = LocalDownloadManager.getSongFile(videoId);
        if (file != null && file.isFile()) {
            Long virtualId = VIDEO_ID_TO_ID.get(videoId);
            if (virtualId != null) {
                return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, virtualId);
            }
            return Uri.fromFile(file);
        }
        return null;
    }

    private static List<OfflineTrack> scanDownloadedTracks() {
        List<OfflineTrack> list = new ArrayList<>();
        File internalDir = LocalDownloadManager.getDownloadDirectory();
        scanDirectory(internalDir, list);

        try {
            Context context = Utils.getContext();
            if (context != null) {
                File legacyDir = new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
                if (legacyDir.isDirectory() && !legacyDir.equals(internalDir)) {
                    scanDirectory(legacyDir, list);
                }
            }
        } catch (Exception ignored) {
        }

        return list;
    }

    private static void scanDirectory(File directory, List<OfflineTrack> list) {
        if (directory == null || !directory.isDirectory()) return;
        File[] files = directory.listFiles();
        if (files == null) return;

        for (File f : files) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            if (name.endsWith(".m4a") || name.endsWith(".webm") || name.endsWith(".opus") || name.endsWith(".mp3")) {
                if (f.isFile() && f.length() > 0) {
                    list.add(OfflineTrack.load(f));
                }
            }
        }
    }

    private static String getMimeType(String fileName) {
        String name = fileName.toLowerCase(Locale.ROOT);
        if (name.endsWith(".m4a")) return "audio/mp4";
        if (name.endsWith(".webm") || name.endsWith(".opus")) return "audio/webm";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        return "audio/*";
    }
}
