/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * Serves rendered lyrics share PNG cards from {@code cacheDir/lyrics_share/} as read-only
 * {@code content://} URIs for {@link android.content.Intent#ACTION_SEND} without external
 * AndroidX FileProvider dependencies.
 */
public final class LyricsShareImageProvider extends ContentProvider {

    public static final String AUTHORITY_SUFFIX = ".morphe.lyricsshare";
    private static final String SHARE_DIR_NAME = "lyrics_share";

    @NonNull
    public static Uri uriForFile(@NonNull Context context, @NonNull File file) {
        return new Uri.Builder()
                .scheme("content")
                .authority(context.getPackageName() + AUTHORITY_SUFFIX)
                .appendPath(file.getName())
                .build();
    }

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    private File resolveShareFile(@NonNull Uri uri) throws FileNotFoundException {
        Context context = getContext();
        if (context == null) {
            throw new FileNotFoundException("Provider context unavailable");
        }
        String name = uri.getLastPathSegment();
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("..") || !name.endsWith(".png")) {
            throw new FileNotFoundException("Invalid share image path: " + uri);
        }
        File baseDir = new File(context.getCacheDir(), SHARE_DIR_NAME);
        File target = new File(baseDir, name);
        try {
            String baseCanonical = baseDir.getCanonicalPath();
            String targetCanonical = target.getCanonicalPath();
            if (!targetCanonical.startsWith(baseCanonical + File.separator)) {
                throw new SecurityException("Path traversal blocked");
            }
        } catch (IOException ex) {
            throw new FileNotFoundException("Canonical path check failed");
        }
        if (!target.isFile()) {
            throw new FileNotFoundException("Share image not found: " + name);
        }
        return target;
    }

    @Nullable
    @Override
    public Cursor query(
            @NonNull Uri uri,
            @Nullable String[] projection,
            @Nullable String selection,
            @Nullable String[] selectionArgs,
            @Nullable String sortOrder) {
        File file;
        try {
            file = resolveShareFile(uri);
        } catch (FileNotFoundException ex) {
            return null;
        }
        if (file == null) {
            return null;
        }
        String[] cols = projection != null
                ? projection
                : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        String[] names = new String[cols.length];
        Object[] values = new Object[cols.length];
        int count = 0;
        for (String col : cols) {
            if (OpenableColumns.DISPLAY_NAME.equals(col)) {
                names[count] = OpenableColumns.DISPLAY_NAME;
                values[count++] = file.getName();
            } else if (OpenableColumns.SIZE.equals(col)) {
                names[count] = OpenableColumns.SIZE;
                values[count++] = file.length();
            }
        }
        String[] trimmedNames = new String[count];
        Object[] trimmedValues = new Object[count];
        System.arraycopy(names, 0, trimmedNames, 0, count);
        System.arraycopy(values, 0, trimmedValues, 0, count);

        MatrixCursor cursor = new MatrixCursor(trimmedNames, 1);
        cursor.addRow(trimmedValues);
        return cursor;
    }

    @Nullable
    @Override
    public String getType(@NonNull Uri uri) {
        return "image/png";
    }

    @Nullable
    @Override
    public ParcelFileDescriptor openFile(@NonNull Uri uri, @NonNull String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) {
            throw new SecurityException("LyricsShareImageProvider is read-only");
        }
        File file = resolveShareFile(uri);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(
            @NonNull Uri uri,
            @Nullable ContentValues values,
            @Nullable String selection,
            @Nullable String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }
}
