package app.morphe.extension.music.patches.downloads;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

/** Persistent album/playlist descriptor preserving the original track order. */
public record OfflineCollection(String id, String type, String title, String subtitle,
                                List<String> videoIds, File artworkFile, File metadataFile) {
    private static final String PREFIX = "collection_";

    public static void save(File directory, String id, String type, String title,
                            String subtitle, List<String> videoIds, Bitmap artwork) {
        try {
            String key = Integer.toHexString(id.hashCode());
            JSONArray ids = new JSONArray();
            for (String videoId : videoIds) ids.put(videoId);
            JSONObject json = new JSONObject().put("id", id).put("type", type)
                    .put("title", title).put("subtitle", subtitle).put("videoIds", ids);
            Files.write(new File(directory, PREFIX + key + ".json").toPath(),
                    json.toString().getBytes(StandardCharsets.UTF_8));
            if (artwork != null) {
                try (java.io.FileOutputStream output = new java.io.FileOutputStream(
                        new File(directory, PREFIX + key + ".jpg"))) {
                    artwork.compress(Bitmap.CompressFormat.JPEG, 90, output);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Could not save offline collection: " + id, ex);
        }
    }

    public static List<OfflineCollection> loadAll(File directory) {
        List<OfflineCollection> result = new ArrayList<>();
        File[] files = directory.listFiles(file -> file.getName().startsWith(PREFIX) && file.getName().endsWith(".json"));
        if (files == null) return result;
        for (File file : files) {
            try {
                JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                List<String> ids = new ArrayList<>();
                JSONArray array = json.optJSONArray("videoIds");
                if (array != null) for (int i = 0; i < array.length(); i++) ids.add(array.optString(i));
                String key = file.getName().substring(PREFIX.length(), file.getName().length() - 5);
                result.add(new OfflineCollection(json.optString("id"), json.optString("type", "playlist"),
                        json.optString("title", "Collection"), json.optString("subtitle", ""), ids,
                        new File(directory, PREFIX + key + ".jpg"), file));
            } catch (Exception ex) {
                Logger.printException(() -> "Could not load offline collection: " + file, ex);
            }
        }
        result.sort(java.util.Comparator.comparing(OfflineCollection::title, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    public static boolean referencedByOtherCollection(File directory, String videoId, String excludedId) {
        for (OfflineCollection collection : loadAll(directory)) {
            if (!collection.id.equals(excludedId) && collection.videoIds.contains(videoId)) return true;
        }
        return false;
    }

    public static void removeTrackFromAll(File directory, String videoId) {
        for (OfflineCollection collection : loadAll(directory)) {
            if (!collection.videoIds.contains(videoId)) continue;
            List<String> remaining = new ArrayList<>(collection.videoIds);
            remaining.removeIf(videoId::equals);
            if (remaining.isEmpty()) {
                collection.metadataFile.delete();
                collection.artworkFile.delete();
            } else {
                save(directory, collection.id, collection.type, collection.title,
                        collection.subtitle, remaining, collection.artwork());
            }
        }
    }

    public Bitmap artwork() {
        return artworkFile.isFile() ? BitmapFactory.decodeFile(artworkFile.getAbsolutePath()) : null;
    }
}
