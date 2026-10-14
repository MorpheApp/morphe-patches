
package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import app.morphe.extension.shared.Logger;

final class OfflineStorage {

    private static final String PLAYLISTS_FILE = "playlists.json";

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

    @Nullable
    static Bitmap decodeArtwork(File file, int maxSize) {
        if (!file.isFile()) return null;
        String path = file.getAbsolutePath();

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, bounds);

        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, Math.max(bounds.outWidth, bounds.outHeight) / maxSize);
        return BitmapFactory.decodeFile(path, options);
    }

    static void delete(File file) {
        if (file.exists() && !file.delete()) {
            Logger.printException(() -> "Could not delete: " + file);
        }
    }

    private static JSONObject readPlaylistsJson(File musicRoot) {
        File file = new File(musicRoot, PLAYLISTS_FILE);
        if (!file.isFile()) return new JSONObject();
        try {
            return new JSONObject(readText(file));
        } catch (Exception ex) {
            Logger.printException(() -> "Could not read playlists", ex);
            return new JSONObject();
        }
    }

    private static void savePlaylistsJson(File musicRoot, JSONObject json) {
        try {
            if (!musicRoot.exists()) musicRoot.mkdirs();
            writeText(new File(musicRoot, PLAYLISTS_FILE), json.toString());
        } catch (Exception ex) {
            Logger.printException(() -> "Could not write playlists", ex);
        }
    }

    static List<String> getPlaylistNames(File musicRoot) {
        JSONObject json = readPlaylistsJson(musicRoot);
        List<String> names = new ArrayList<>();
        Iterator<String> keys = json.keys();
        while (keys.hasNext()) {
            names.add(keys.next());
        }
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    static List<String> getPlaylistTracks(File musicRoot, String playlistName) {
        JSONObject json = readPlaylistsJson(musicRoot);
        JSONArray array = json.optJSONArray(playlistName);
        List<String> tracks = new ArrayList<>();
        if (array == null) return tracks;
        for (int i = 0; i < array.length(); i++) {
            String item = array.optString(i, "");
            if (!item.isEmpty()) tracks.add(item);
        }
        return tracks;
    }

    static void createPlaylist(File musicRoot, String playlistName) {
        String clean = playlistName.trim();
        if (clean.isEmpty()) return;
        JSONObject json = readPlaylistsJson(musicRoot);
        if (!json.has(clean)) {
            try {
                json.put(clean, new JSONArray());
                savePlaylistsJson(musicRoot, json);
            } catch (Exception ignored) {
            }
        }
    }

    static void deletePlaylist(File musicRoot, String playlistName) {
        JSONObject json = readPlaylistsJson(musicRoot);
        if (json.remove(playlistName) != null) {
            savePlaylistsJson(musicRoot, json);
        }
    }

    static void addTrackToPlaylist(File musicRoot, String playlistName, String fileName) {
        JSONObject json = readPlaylistsJson(musicRoot);
        JSONArray array = json.optJSONArray(playlistName);
        if (array == null) array = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            if (fileName.equals(array.optString(i))) return;
        }
        array.put(fileName);
        try {
            json.put(playlistName, array);
            savePlaylistsJson(musicRoot, json);
        } catch (Exception ignored) {
        }
    }

    static void removeTrackFromPlaylist(File musicRoot, String playlistName, String fileName) {
        JSONObject json = readPlaylistsJson(musicRoot);
        JSONArray array = json.optJSONArray(playlistName);
        if (array == null) return;
        JSONArray updated = new JSONArray();
        for (int i = 0; i < array.length(); i++) {
            String item = array.optString(i);
            if (!fileName.equals(item)) updated.put(item);
        }
        try {
            json.put(playlistName, updated);
            savePlaylistsJson(musicRoot, json);
        } catch (Exception ignored) {
        }
    }
}
