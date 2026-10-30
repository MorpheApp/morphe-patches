package app.morphe.extension.music.patches.downloads;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public record OfflinePlaylist(String id, String name, List<String> videoIds) {

    public OfflinePlaylist withVideoIds(List<String> updatedIds) {
        return new OfflinePlaylist(id, name, updatedIds);
    }

    public OfflinePlaylist renamed(String newName) {
        return new OfflinePlaylist(id, newName, videoIds);
    }

    public void save(File file) throws Exception {
        JSONObject json = new JSONObject();
        json.put("id", id);
        json.put("name", name == null ? "" : name.trim());

        JSONArray ids = new JSONArray();
        for (String videoId : videoIds) {
            if (videoId != null && !videoId.isBlank()) {
                ids.put(videoId.trim());
            }
        }
        json.put("videoIds", ids);

        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) parent.mkdirs();

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(json.toString().getBytes(StandardCharsets.UTF_8));
        }
    }

    public static OfflinePlaylist fromFile(File file) throws Exception {
        String text = readFile(file);
        JSONObject json = new JSONObject(text);
        String id = json.optString("id", file.getName().replace(".json", ""));
        String name = json.optString("name", "Playlist");

        JSONArray arr = json.optJSONArray("videoIds");
        List<String> videoIds = new ArrayList<>();
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                String videoId = arr.optString(i, "");
                if (!videoId.isBlank()) {
                    videoIds.add(videoId.trim());
                }
            }
        }
        return new OfflinePlaylist(id, name, videoIds);
    }

    private static String readFile(File file) throws Exception {
        byte[] bytes = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < bytes.length) {
                int read = in.read(bytes, offset, bytes.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
