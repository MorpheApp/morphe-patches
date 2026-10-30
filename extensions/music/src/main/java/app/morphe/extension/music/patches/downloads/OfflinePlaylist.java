package app.morphe.extension.music.patches.downloads;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public record OfflinePlaylist(String id, String name, List<String> videoIds) {

    public void save(File file) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name);

        JSONArray ids = new JSONArray();
        for (String videoId : videoIds) {
            if (videoId != null && !videoId.isBlank()) {
                ids.put(videoId);
            }
        }
        json.put("videoIds", ids);

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        java.nio.file.Files.write(file.toPath(), json.toString().getBytes(StandardCharsets.UTF_8));
    }

    public static OfflinePlaylist fromFile(File file) throws Exception {
        JSONObject json = new JSONObject(new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        String id = json.optString("id", file.getName().replace(".json", ""));
        String name = json.optString("name", "Playlist");

        JSONArray arr = json.optJSONArray("videoIds");
        List<String> videoIds = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String videoId = arr.optString(i, "");
                if (!videoId.isBlank()) {
                    videoIds.add(videoId);
                }
            }
        }
        return new OfflinePlaylist(id, name, videoIds);
    }
}
