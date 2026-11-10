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
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.music.patches.DownloadsPatch;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Virtualizes MediaStore Audio queries, file descriptors, and image streams for downloaded tracks.
 * Implements a Dual Device Files Architecture that blends internal private storage
 * downloads seamlessly with native Android MediaStore tracks, routing them directly
 * to YouTube Music's native sideloaded tracks engine, Glide artwork loader, and ExoPlayer.
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
            "audio_id",
            "album_art",
            "track",
            "date_added",
            "date_modified"
    };

    private static final Map<Long, File> ID_TO_AUDIO_FILE = new ConcurrentHashMap<>();
    private static final Map<Long, File> ID_TO_ARTWORK_FILE = new ConcurrentHashMap<>();
    private static final Map<String, Long> VIDEO_ID_TO_ID = new ConcurrentHashMap<>();
    private static final Map<Long, String> ID_TO_VIDEO_ID = new ConcurrentHashMap<>();

    private VirtualMediaProvider() {}

    /**
     * Computes a deterministic positive virtual ID for a downloaded video ID.
     */
    public static long getVirtualId(String videoId) {
        long hash = Math.abs((long) videoId.hashCode());
        return BASE_VIRTUAL_ID + (hash % 80_000_000L);
    }

    /**
     * Ensures the internal download catalogue mapping is loaded in memory.
     */
    public static synchronized void ensureCatalogueLoaded() {
        if (!ID_TO_AUDIO_FILE.isEmpty()) {
            return;
        }
        refreshCatalogue();
    }

    /**
     * Scans and rebuilds the virtual ID to file mappings.
     */
    public static synchronized void refreshCatalogue() {
        List<OfflineTrack> tracks = scanDownloadedTracks();
        ID_TO_AUDIO_FILE.clear();
        ID_TO_ARTWORK_FILE.clear();
        VIDEO_ID_TO_ID.clear();
        ID_TO_VIDEO_ID.clear();

        for (OfflineTrack track : tracks) {
            long virtualId = getVirtualId(track.videoId());
            ID_TO_AUDIO_FILE.put(virtualId, track.audioFile());
            if (track.artworkFile() != null && track.artworkFile().isFile()) {
                ID_TO_ARTWORK_FILE.put(virtualId, track.artworkFile());
            }
            VIDEO_ID_TO_ID.put(track.videoId(), virtualId);
            ID_TO_VIDEO_ID.put(virtualId, track.videoId());
        }
        Logger.printDebug(() -> "VirtualMediaProvider: Catalogue loaded with " + tracks.size() + " tracks");
    }

    /**
     * Intercepts ContentResolver.query calls (5-parameter overload).
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

        ensureCatalogueLoaded();

        Long targetId = extractTargetId(uri, selection, selectionArgs);
        Logger.printDebug(() -> "VirtualMediaProvider: Intercepted MediaStore query for: " + uri
                + " (targetId=" + targetId + ", sel=" + selection + ")");

        MatrixCursor virtualCursor = buildVirtualCursor(projection, targetId);

        // If queried for a specific virtual ID (>= BASE_VIRTUAL_ID), return virtual result directly
        if (targetId != null && targetId >= BASE_VIRTUAL_ID) {
            return virtualCursor;
        }

        DeviceFilesSource source = Settings.SIDELOADED_SOURCE.get();

        // 1. DEVICE_ONLY mode: only query real MediaStore
        if (source == DeviceFilesSource.DEVICE_ONLY) {
            virtualCursor.close();
            try {
                return resolver.query(uri, projection, selection, selectionArgs, sortOrder);
            } catch (Exception ex) {
                Logger.printException(() -> "VirtualMediaProvider: Real MediaStore query failed in DEVICE_ONLY mode", ex);
                return buildVirtualCursor(projection, -1L);
            }
        }

        // 2. DOWNLOADS_ONLY mode: return virtual downloads only
        if (source == DeviceFilesSource.DOWNLOADS_ONLY) {
            Logger.printDebug(() -> "VirtualMediaProvider: Serving " + virtualCursor.getCount()
                    + " virtual downloads only (DOWNLOADS_ONLY mode)");
            return virtualCursor;
        }

        // 3. BOTH mode (Dual Device Files Architecture): Attempt to query real device files as well
        Cursor realCursor = null;
        try {
            realCursor = resolver.query(uri, projection, selection, selectionArgs, sortOrder);
        } catch (SecurityException se) {
            Logger.printDebug(() -> "VirtualMediaProvider: External storage permission denied, using virtual downloads only");
        } catch (Exception ex) {
            Logger.printException(() -> "VirtualMediaProvider: Real MediaStore query failed", ex);
        }

        final Cursor finalRealCursor = realCursor;
        if (finalRealCursor != null && finalRealCursor.getCount() > 0) {
            if (virtualCursor.getCount() > 0) {
                Logger.printDebug(() -> "VirtualMediaProvider: Merging " + virtualCursor.getCount()
                        + " virtual downloads with " + finalRealCursor.getCount() + " device tracks");
                return new MergeCursor(new Cursor[]{ virtualCursor, finalRealCursor });
            } else {
                virtualCursor.close();
                return finalRealCursor;
            }
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
     * 4-parameter query overload with Bundle (Android O+ / API 26+).
     */
    public static Cursor query(
            ContentResolver resolver,
            Uri uri,
            String[] projection,
            Bundle queryArgs,
            CancellationSignal cancellationSignal
    ) {
        if (queryArgs != null) {
            String selection = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SELECTION);
            String[] selectionArgs = queryArgs.getStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS);
            String sortOrder = queryArgs.getString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER);
            return query(resolver, uri, projection, selection, selectionArgs, sortOrder);
        }
        return query(resolver, uri, projection, (String) null, (String[]) null, (String) null);
    }

    /**
     * Extracts a target track ID if the query is for a single item (via URI path or selectionArgs).
     */
    @Nullable
    private static Long extractTargetId(Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        // 1. Try parsing ID directly from URI
        try {
            long id = ContentUris.parseId(uri);
            if (id > 0) {
                return id;
            }
        } catch (Exception ignored) {
            List<String> segments = uri.getPathSegments();
            if (segments != null) {
                for (String seg : segments) {
                    try {
                        long id = Long.parseLong(seg);
                        if (id >= BASE_VIRTUAL_ID) {
                            return id;
                        }
                    } catch (NumberFormatException ignored2) {}
                }
            }
        }

        // 2. Try parsing from selectionArgs when querying by ID
        if (selection != null && selectionArgs != null && selectionArgs.length > 0) {
            String selLower = selection.toLowerCase(Locale.ROOT);
            if (selLower.contains("_id") || selLower.contains("audio_id")
                    || selLower.contains("artist_id") || selLower.contains("album_id")) {
                for (String arg : selectionArgs) {
                    if (arg != null) {
                        try {
                            long id = Long.parseLong(arg.trim());
                            if (id >= BASE_VIRTUAL_ID) {
                                return id;
                            }
                        } catch (NumberFormatException ignored3) {}
                    }
                }
            }
        }

        return null;
    }

    /**
     * Builds an in-memory MatrixCursor containing locally downloaded tracks,
     * optionally filtered to a single target ID.
     */
    private static MatrixCursor buildVirtualCursor(@Nullable String[] projection, @Nullable Long targetId) {
        String[] cols = (projection != null && projection.length > 0) ? projection : DEFAULT_PROJECTION;
        MatrixCursor cursor = new MatrixCursor(cols);

        ensureCatalogueLoaded();
        List<OfflineTrack> tracks = scanDownloadedTracks();

        for (OfflineTrack track : tracks) {
            Long virtualId = VIDEO_ID_TO_ID.get(track.videoId());
            if (virtualId == null) {
                virtualId = getVirtualId(track.videoId());
            }

            if (targetId != null && !targetId.equals(virtualId)) {
                continue;
            }

            MatrixCursor.RowBuilder row = cursor.newRow();
            for (String col : cols) {
                switch (col) {
                    case MediaStore.Audio.Media._ID:
                    case "audio_id":
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
                    case "album_art":
                        row.add(track.artworkFile() != null && track.artworkFile().isFile()
                                ? track.artworkFile().getAbsolutePath()
                                : null);
                        break;
                    case "track":
                        row.add(1);
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

        Logger.printDebug(() -> "VirtualMediaProvider: Built MatrixCursor with " + cursor.getCount() + " virtual rows");
        return cursor;
    }

    /**
     * Intercepts ContentResolver.getType calls for MIME type resolution (ExoPlayer media sniff & Glide).
     */
    @Nullable
    public static String getType(ContentResolver resolver, Uri uri) {
        if (uri == null) return null;

        File targetFile = resolveVirtualFile(uri);
        if (targetFile != null && targetFile.isFile()) {
            String mime = getMimeType(targetFile.getName());
            Logger.printDebug(() -> "VirtualMediaProvider: Serving virtual MIME type " + mime + " for: " + uri);
            return mime;
        }

        return resolver.getType(uri);
    }

    /**
     * Intercepts ContentResolver.openInputStream calls for Glide artwork loading.
     */
    @Nullable
    public static InputStream openInputStream(
            ContentResolver resolver,
            Uri uri
    ) throws FileNotFoundException {
        if (uri == null) return null;

        File targetFile = resolveVirtualFile(uri);
        if (targetFile != null && targetFile.isFile()) {
            Logger.printDebug(() -> "VirtualMediaProvider: Serving virtual InputStream for: " + uri);
            return new FileInputStream(targetFile);
        }

        return resolver.openInputStream(uri);
    }

    /**
     * Intercepts ContentResolver.openTypedAssetFileDescriptor calls for ExoPlayer ContentDataSource playback.
     */
    @Nullable
    public static AssetFileDescriptor openTypedAssetFileDescriptor(
            ContentResolver resolver,
            Uri uri,
            String mimeTypeFilter,
            Bundle opts
    ) throws FileNotFoundException {
        if (uri == null) return null;

        File targetFile = resolveVirtualFile(uri);
        if (targetFile != null && targetFile.isFile()) {
            Logger.printDebug(() -> "VirtualMediaProvider: Serving virtual typed AFD for: " + uri);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY);
            return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
        }

        return resolver.openTypedAssetFileDescriptor(uri, mimeTypeFilter, opts);
    }

    /**
     * 5-parameter overload with CancellationSignal.
     */
    @Nullable
    public static AssetFileDescriptor openTypedAssetFileDescriptor(
            ContentResolver resolver,
            Uri uri,
            String mimeTypeFilter,
            Bundle opts,
            CancellationSignal cancellationSignal
    ) throws FileNotFoundException {
        return openTypedAssetFileDescriptor(resolver, uri, mimeTypeFilter, opts);
    }

    /**
     * Intercepts ContentResolver.openAssetFileDescriptor calls.
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
            Logger.printDebug(() -> "VirtualMediaProvider: Serving virtual AFD for: " + uri);
            ParcelFileDescriptor pfd = ParcelFileDescriptor.open(targetFile, ParcelFileDescriptor.MODE_READ_ONLY);
            return new AssetFileDescriptor(pfd, 0, AssetFileDescriptor.UNKNOWN_LENGTH);
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
     * 4-parameter overload with CancellationSignal.
     */
    @Nullable
    public static ParcelFileDescriptor openFileDescriptor(
            ContentResolver resolver,
            Uri uri,
            String mode,
            CancellationSignal cancellationSignal
    ) throws FileNotFoundException {
        return openFileDescriptor(resolver, uri, mode);
    }

    /**
     * Resolves a Content/File URI to the underlying downloaded audio or artwork file.
     */
    @Nullable
    public static File resolveVirtualFile(Uri uri) {
        if (uri == null) return null;
        ensureCatalogueLoaded();

        String uriStr = uri.toString();
        boolean isArtwork = uriStr.contains("albumart") || uriStr.contains("artwork") || uriStr.contains("thumbnail");

        // 1. Try parsing numeric ID from URI segments
        List<String> segments = uri.getPathSegments();
        if (segments != null) {
            for (String seg : segments) {
                try {
                    long id = Long.parseLong(seg);
                    if (id >= BASE_VIRTUAL_ID) {
                        if (isArtwork) {
                            File art = ID_TO_ARTWORK_FILE.get(id);
                            if (art != null && art.isFile()) return art;
                        }
                        File audio = ID_TO_AUDIO_FILE.get(id);
                        if (audio != null && audio.isFile()) return audio;

                        File fallbackArt = ID_TO_ARTWORK_FILE.get(id);
                        if (fallbackArt != null && fallbackArt.isFile()) return fallbackArt;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 2. Check path directly
        String path = uri.getPath();
        if (path != null) {
            File directFile = new File(path);
            if (directFile.isFile()) return directFile;

            // Search by videoId in path
            for (Map.Entry<String, Long> entry : VIDEO_ID_TO_ID.entrySet()) {
                if (path.contains(entry.getKey())) {
                    if (isArtwork) {
                        File art = ID_TO_ARTWORK_FILE.get(entry.getValue());
                        if (art != null && art.isFile()) return art;
                    }
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
            if (virtualId == null) {
                virtualId = getVirtualId(videoId);
            }
            return ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, virtualId);
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
        } catch (Exception ignored) {}

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
        if (name.endsWith(".flac")) return "audio/flac";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".ogg")) return "audio/ogg";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".webp")) return "image/webp";
        return "audio/*";
    }
}
