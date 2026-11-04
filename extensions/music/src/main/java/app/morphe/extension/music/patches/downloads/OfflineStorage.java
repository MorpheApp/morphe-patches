/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;

/**
 * File access shared by the offline catalogue.
 * <p>
 * Plain streams are used rather than {@code Files.readString} and {@code Files.writeString},
 * which Android only provides from API 33 while this extension supports API 26.
 */
final class OfflineStorage {

    private OfflineStorage() {
    }

    static String readText(File file) throws IOException {
        try (FileInputStream input = new FileInputStream(file)) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            int read;
            while ((read = input.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
            }
            //noinspection CharsetObjectCanBeUsed
            return buffer.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void writeText(File file, String value) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            output.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }

    static void writeJpeg(File file, Bitmap bitmap) throws IOException {
        try (FileOutputStream output = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, output);
        }
    }

    /**
     * Covers are stored large, mostly as 16:9 video thumbnails, and are shown cropped to a square,
     * so only a sample of the middle square is decoded. Decoding is slow, so this must not run on
     * the main thread.
     *
     * @param size Side of the square in pixels. The cover is not scaled up when it is smaller.
     */
    @Nullable
    static Bitmap decodeSquareArtwork(File file, int size) {
        if (!file.isFile()) return null;
        String path = file.getAbsolutePath();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);
        final int shortestEdge = Math.min(bounds.outWidth, bounds.outHeight);
        if (shortestEdge <= 0) return null;

        // The decoder only samples by powers of two. The largest one that stays at least as big
        // as asked is applied while reading the file, which is far cheaper than a full decode.
        int sampleSize = 1;
        while (shortestEdge / (sampleSize * 2) >= size) {
            sampleSize *= 2;
        }

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = sampleSize;
        Bitmap sampled = BitmapFactory.decodeFile(path, options);
        if (sampled == null) return null;

        final int side = Math.min(sampled.getWidth(), sampled.getHeight());
        final float scale = Math.min(1f, (float) size / side);
        Matrix matrix = new Matrix();
        matrix.setScale(scale, scale);
        Bitmap square = Bitmap.createBitmap(sampled, (sampled.getWidth() - side) / 2,
                (sampled.getHeight() - side) / 2, side, side, matrix, true);
        if (square != sampled) sampled.recycle();
        return square;
    }

    /** Best effort removal, so a stale file cannot silently keep a download alive. */
    static void delete(File file) {
        if (file.exists() && !file.delete()) {
            Logger.printException(() -> "Could not delete: " + file);
        }
    }
}
