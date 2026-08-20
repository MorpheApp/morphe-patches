package app.morphe.extension.music.patches.downloads;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/** Resolves and queues every track exposed by a YouTube Music album or playlist. */
public final class CollectionDownloadManager {
    private static final String BROWSE_URL =
            "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String CLIENT_VERSION = "1.20250811.03.00";
    private static final Set<String> ACTIVE_COLLECTIONS = ConcurrentHashMap.newKeySet();

    private CollectionDownloadManager() {}

    public static void enqueue(String playlistId) {
        if (!ACTIVE_COLLECTIONS.add(playlistId)) { Utils.showToastShort("Collection download already in progress"); return; }
        Utils.showToastShort("Preparing collection…");
        Utils.submitOnBackgroundThread(() -> {
            try {
                Logger.printInfo(() -> "Resolving collection: " + playlistId);
                CollectionData data = fetchCollection(playlistId);
                List<Item> items = data.items();
                Logger.printInfo(() -> "Resolved collection items: " + items.size());
                if (items.isEmpty()) {
                    Utils.showToastShort("No tracks found");
                    return null;
                }
                Context context = Utils.getContext();
                File directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
                Item first = items.get(0);
                String type = playlistId.startsWith("OLAK5uy_") ? "album" : "playlist";
                String collectionTitle = !data.title().isBlank() ? data.title()
                        : type.equals("album") && !first.album().isBlank() ? first.album() : "Playlist offline";
                List<String> completedIds = new ArrayList<>(items.size());
                Utils.showToastShort("Downloading " + items.size() + " tracks");
                int completed = 0;
                for (Item item : items) {
                    Logger.printInfo(() -> "Resolving collection track: " + item.videoId);
                    if (enqueueItem(item)) completedIds.add(item.videoId());
                    completed++;
                    Utils.showToastShort(completed + "/" + items.size() + " • " + collectionTitle);
                }
                String collectionSubtitle = !data.subtitle().isBlank() ? data.subtitle() : first.artist();
                if (!completedIds.isEmpty()) OfflineCollection.save(directory, playlistId, type, collectionTitle,
                        collectionSubtitle, completedIds, fetchArtwork(first.thumbnailUrl()));
                int result = completedIds.size();
                Utils.showToastShort(result == items.size() ? "Collection downloaded" : result + "/" + items.size() + " tracks downloaded");
            } catch (Exception ex) {
                Logger.printException(() -> "Collection download failed: " + playlistId, ex);
                Utils.showToastShort("Collection download failed");
            } finally { ACTIVE_COLLECTIONS.remove(playlistId); }
            return null;
        });
    }

    private static boolean enqueueItem(Item item) {
        Bitmap artwork = fetchArtwork(item.thumbnailUrl);
        return LocalDownloadManager.downloadBlocking(item.videoId, item.title, item.artist,
                item.album, item.durationSeconds, artwork, false);
    }

    private static CollectionData fetchCollection(String playlistId) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(BROWSE_URL).openConnection();
        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", "curl/8.0");
        connection.setRequestProperty("Origin", "https://music.youtube.com");
        connection.setRequestProperty("X-Origin", "https://music.youtube.com");
        connection.setRequestProperty("X-YouTube-Client-Name", "67");
        connection.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
        JSONObject client = new JSONObject().put("clientName", "WEB_REMIX")
                .put("clientVersion", CLIENT_VERSION)
                .put("hl", Locale.getDefault().getLanguage()).put("gl", Locale.getDefault().getCountry());
        JSONObject body = new JSONObject().put("context", new JSONObject().put("client", client))
                .put("browseId", playlistId.startsWith("VL") ? playlistId : "VL" + playlistId);
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        connection.setFixedLengthStreamingMode(bytes.length);
        connection.getOutputStream().write(bytes);
        if (connection.getResponseCode() != 200) throw new IllegalStateException("Browse HTTP " + connection.getResponseCode());
        JSONObject response = Requester.parseJSONObject(connection);
        Map<String, Item> unique = new LinkedHashMap<>();
        collect(response, unique);
        return new CollectionData(findCollectionTitle(response), findCollectionSubtitle(response),
                new ArrayList<>(unique.values()));
    }

    private static String findCollectionTitle(Object node) {
        if (node instanceof JSONObject object) {
            for (String key : new String[]{"musicDetailHeaderRenderer", "musicResponsiveHeaderRenderer",
                    "musicEditablePlaylistDetailHeaderRenderer"}) {
                JSONObject header = object.optJSONObject(key);
                if (header != null) {
                    String title = textRuns(header.optJSONObject("title"));
                    if (!title.isBlank()) return title;
                    JSONObject nested = header.optJSONObject("header");
                    if (nested != null) {
                        title = findCollectionTitle(nested);
                        if (!title.isBlank()) return title;
                    }
                }
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String title = findCollectionTitle(object.opt(keys.next()));
                if (!title.isBlank()) return title;
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                String title = findCollectionTitle(array.opt(i));
                if (!title.isBlank()) return title;
            }
        }
        return "";
    }

    private static String findCollectionSubtitle(Object node) {
        if (node instanceof JSONObject object) {
            JSONObject header = object.optJSONObject("musicResponsiveHeaderRenderer");
            if (header != null) {
                try {
                    String owner = header.getJSONObject("facepile").getJSONObject("avatarStackViewModel")
                            .getJSONObject("text").optString("content");
                    if (!owner.isBlank()) return owner;
                } catch (Exception ignored) {}
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String subtitle = findCollectionSubtitle(object.opt(keys.next()));
                if (!subtitle.isBlank()) return subtitle;
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                String subtitle = findCollectionSubtitle(array.opt(i));
                if (!subtitle.isBlank()) return subtitle;
            }
        }
        return "";
    }

    private static void collect(Object node, Map<String, Item> result) {
        if (node instanceof JSONObject object) {
            JSONObject renderer = object.optJSONObject("musicResponsiveListItemRenderer");
            if (renderer != null) {
                JSONObject data = renderer.optJSONObject("playlistItemData");
                String videoId = data == null ? "" : data.optString("videoId");
                if (!videoId.isBlank()) result.putIfAbsent(videoId, parseItem(videoId, renderer));
            }
            JSONObject twoColumn = object.optJSONObject("musicTwoColumnItemRenderer");
            if (twoColumn != null) {
                JSONObject navigation = twoColumn.optJSONObject("navigationEndpoint");
                JSONObject watch = navigation == null ? null : navigation.optJSONObject("watchEndpoint");
                String videoId = watch == null ? "" : watch.optString("videoId");
                if (!videoId.isBlank()) result.putIfAbsent(videoId, parseTwoColumnItem(videoId, twoColumn));
            }
            java.util.Iterator<String> keys = object.keys();
            while (keys.hasNext()) collect(object.opt(keys.next()), result);
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) collect(array.opt(i), result);
        }
    }

    private static Item parseTwoColumnItem(String videoId, JSONObject renderer) {
        String title = textRuns(renderer.optJSONObject("title"));
        String subtitle = textRuns(renderer.optJSONObject("subtitle"));
        String artist = subtitle;
        String duration = "";
        int separator = subtitle.lastIndexOf(" • ");
        if (separator >= 0) {
            artist = subtitle.substring(0, separator);
            duration = subtitle.substring(separator + 3);
        }
        String thumbnail = thumbnailUrl(renderer.optJSONObject("thumbnail"));
        return new Item(videoId, title.isBlank() ? videoId : title,
                artist.isBlank() ? "YouTube Music" : artist, "", parseDuration(duration), thumbnail);
    }

    private static Item parseItem(String videoId, JSONObject renderer) {
        JSONArray columns = renderer.optJSONArray("flexColumns");
        String title = columnText(columns, 0, videoId);
        String artist = columnText(columns, 1, "YouTube Music");
        String album = "";
        String duration = "";
        if (columns != null) {
            for (int i = 2; i < columns.length(); i++) {
                String value = columnText(columns, i, "").trim();
                if (value.matches("\\d{1,2}:\\d{2}")) duration = value;
                else if (!value.isBlank() && album.isBlank()) album = value;
            }
        }
        JSONArray fixed = renderer.optJSONArray("fixedColumns");
        if (fixed != null && fixed.length() > 0) {
            String fixedDuration = runsText(fixed.optJSONObject(0));
            if (!fixedDuration.isBlank()) duration = fixedDuration;
        }
        String thumbnail = thumbnailUrl(renderer.optJSONObject("thumbnail"));
        return new Item(videoId, title, artist, album, parseDuration(duration), thumbnail);
    }

    private static String thumbnailUrl(JSONObject thumbnailContainer) {
        try {
            JSONArray thumbnails = thumbnailContainer.getJSONObject("musicThumbnailRenderer")
                    .getJSONObject("thumbnail").getJSONArray("thumbnails");
            return thumbnails.getJSONObject(thumbnails.length() - 1).optString("url");
        } catch (Exception ignored) { return ""; }
    }

    private static String textRuns(JSONObject text) {
        if (text == null) return "";
        JSONArray runs = text.optJSONArray("runs");
        if (runs == null) return "";
        StringBuilder value = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            JSONObject run = runs.optJSONObject(i);
            if (run != null) value.append(run.optString("text"));
        }
        return value.toString();
    }

    private static String columnText(JSONArray columns, int index, String fallback) {
        if (columns == null || index >= columns.length()) return fallback;
        String value = runsText(columns.optJSONObject(index));
        return value.isBlank() ? fallback : value;
    }

    private static String runsText(JSONObject column) {
        try {
            JSONArray runs = column.getJSONObject("musicResponsiveListItemFlexColumnRenderer")
                    .getJSONObject("text").getJSONArray("runs");
            StringBuilder value = new StringBuilder();
            for (int i = 0; i < runs.length(); i++) value.append(runs.getJSONObject(i).optString("text"));
            return value.toString();
        } catch (Exception ignored) {
            try {
                JSONArray runs = column.getJSONObject("musicResponsiveListItemFixedColumnRenderer")
                        .getJSONObject("text").getJSONArray("runs");
                return runs.getJSONObject(0).optString("text");
            } catch (Exception ignoredAgain) { return ""; }
        }
    }

    private static int parseDuration(String value) {
        try {
            String[] parts = value.trim().split(":");
            int seconds = 0;
            for (String part : parts) seconds = seconds * 60 + Integer.parseInt(part);
            return seconds;
        } catch (Exception ignored) { return 0; }
    }

    private static Bitmap fetchArtwork(String url) {
        if (url == null || url.isBlank()) return null;
        String highResolution = url.replaceFirst("=w\\d+-h\\d+", "=w1200-h1200");
        try { return BitmapFactory.decodeStream(new URL(highResolution).openStream()); }
        catch (Exception ignored) { return null; }
    }

    private record CollectionData(String title, String subtitle, List<Item> items) {}
    private record Item(String videoId, String title, String artist, String album,
                        int durationSeconds, String thumbnailUrl) {}
}
