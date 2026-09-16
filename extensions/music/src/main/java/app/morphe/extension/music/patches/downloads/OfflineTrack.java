/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2528
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.File;

import app.morphe.extension.shared.Logger;

/** Persistent metadata paired with one locally downloaded audio file. */
public record OfflineTrack(String videoId, String title, String artist, String album,
                           int durationSeconds, File audioFile, File artworkFile) {

    public static OfflineTrack load(File audioFile) {
        String videoId = stripExtension(audioFile.getName());
        File metadata = new File(audioFile.getParentFile(), videoId + ".json");
        File artwork = new File(audioFile.getParentFile(), videoId + ".jpg");

        try {
            if (metadata.isFile()) {
                JSONObject json = new JSONObject(OfflineStorage.readText(metadata));
                return new OfflineTrack(videoId, json.optString("title", videoId),
                        json.optString("artist", ""), json.optString("album", ""),
                        json.optInt("durationSeconds", 0), audioFile, artwork);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read offline metadata: " + metadata, ex);
        }
        return new OfflineTrack(videoId, videoId, "", "", 0, audioFile, artwork);
    }

    public static void save(File directory, String videoId, String title, String artist,
                            String album, int durationSeconds, @Nullable Bitmap artwork) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) {
                throw new IllegalStateException("Could not create " + directory);
            }

            JSONObject json = new JSONObject()
                    .put("videoId", videoId)
                    .put("title", emptyFallback(title, videoId))
                    .put("artist", emptyFallback(artist, ""))
                    .put("album", emptyFallback(album, ""))
                    .put("durationSeconds", durationSeconds);
            OfflineStorage.writeText(new File(directory, videoId + ".json"), json.toString());

            if (artwork != null) {
                OfflineStorage.writeJpeg(new File(directory, videoId + ".jpg"), artwork);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save offline metadata: " + videoId, ex);
        }
    }

    @Nullable
    public Bitmap artwork(int maxSize) {
        return OfflineStorage.decodeArtwork(artworkFile, maxSize);
    }

    public String displayTitle() {
        return title.isBlank() ? videoId : title;
    }

    public String displayArtist() {
        return artist.isBlank() ? "YouTube Music" : artist;
    }

    private static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String emptyFallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
