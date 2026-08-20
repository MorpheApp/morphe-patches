package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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
                JSONObject json = new JSONObject(new String(Files.readAllBytes(metadata.toPath()), StandardCharsets.UTF_8));
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
                            String album, int durationSeconds, Bitmap artwork) {
        try {
            directory.mkdirs();
            JSONObject json = new JSONObject()
                    .put("videoId", videoId)
                    .put("title", emptyFallback(title, videoId))
                    .put("artist", emptyFallback(artist, ""))
                    .put("album", emptyFallback(album, ""))
                    .put("durationSeconds", durationSeconds);
            Files.write(new File(directory, videoId + ".json").toPath(),
                    json.toString().getBytes(StandardCharsets.UTF_8));
            if (artwork != null) {
                try (FileOutputStream output = new FileOutputStream(new File(directory, videoId + ".jpg"))) {
                    artwork.compress(Bitmap.CompressFormat.JPEG, 90, output);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save offline metadata: " + videoId, ex);
        }
    }

    public Bitmap artwork() {
        return artworkFile.isFile() ? BitmapFactory.decodeFile(artworkFile.getAbsolutePath()) : null;
    }

    public String displayTitle() { return title.isBlank() ? videoId : title; }
    public String displayArtist() { return artist.isBlank() ? "YouTube Music" : artist; }
    private static String stripExtension(String name) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) : name; }
    private static String emptyFallback(String value, String fallback) { return value == null || value.isBlank() ? fallback : value; }
}
