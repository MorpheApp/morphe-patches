package app.morphe.extension.music.patches.downloads;

import android.app.DownloadManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.Format;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerResponse;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/** Resolves and queues every track exposed by a YouTube Music album or playlist. */
public final class CollectionDownloadManager {
    private static final String BROWSE_URL =
            "https://music.youtube.com/youtubei/v1/browse?prettyPrint=false";
    private static final String CLIENT_VERSION = "1.20250811.03.00";

    private CollectionDownloadManager() {}

    public static void enqueue(String playlistId) {
        Utils.showToastShort("Preparazione raccolta…");
        Utils.submitOnBackgroundThread(() -> {
            try {
                Logger.printInfo(() -> "Resolving collection: " + playlistId);
                List<Item> items = fetchItems(playlistId);
                Logger.printInfo(() -> "Resolved collection items: " + items.size());
                if (items.isEmpty()) {
                    Utils.showToastShort("Nessun brano trovato");
                    return null;
                }
                Context context = Utils.getContext();
                File directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
                Item first = items.get(0);
                String type = playlistId.startsWith("OLAK5uy_") ? "album" : "playlist";
                String collectionTitle = type.equals("album") && !first.album().isBlank()
                        ? first.album() : "Playlist offline";
                List<String> orderedIds = new ArrayList<>(items.size());
                for (Item item : items) orderedIds.add(item.videoId());
                OfflineCollection.save(directory, playlistId, type, collectionTitle, first.artist(),
                        orderedIds, fetchArtwork(first.thumbnailUrl()));

                Utils.showToastShort("Download di " + items.size() + " brani avviato");
                int queued = 0;
                for (Item item : items) {
                    Logger.printInfo(() -> "Resolving collection track: " + item.videoId);
                    if (enqueueItem(item)) queued++;
                }
                int result = queued;
                Utils.showToastShort(result == 0
                        ? "Brani già scaricati o non disponibili"
                        : result + " brani aggiunti ai download");
            } catch (Exception ex) {
                Logger.printException(() -> "Collection download failed: " + playlistId, ex);
                Utils.showToastShort("Download della raccolta non riuscito");
            }
            return null;
        });
    }

    private static boolean enqueueItem(Item item) {
        try {
            Context context = Utils.getContext();
            File directory = new File(context.getExternalFilesDir(Environment.DIRECTORY_MUSIC), "Morphe");
            Bitmap artwork = fetchArtwork(item.thumbnailUrl);
            OfflineTrack.save(directory, item.videoId, item.title, item.artist,
                    item.album, item.durationSeconds, artwork);
            if (new File(directory, item.videoId + ".webm").isFile() ||
                    new File(directory, item.videoId + ".m4a").isFile()) return false;

            StreamingDataRequest request = StreamingDataRequest.fetchRequestForDownload(item.videoId);
            StreamingDataRequest.StreamData stream = request.getStream();
            if (stream == null) return false;
            Format format = PlayerResponse.parseFrom(stream.streamingData()).getStreamingData()
                    .getAdaptiveFormatsList().stream()
                    .filter(f -> f.getMimeType().startsWith("audio/") && !f.getUrl().isBlank())
                    .max(Comparator.comparingInt(Format::getBitrate)).orElse(null);
            if (format == null) return false;

            String extension = format.getMimeType().contains("mp4") ? ".m4a" : ".webm";
            DownloadManager manager = (DownloadManager) context.getSystemService(Context.DOWNLOAD_SERVICE);
            if (manager == null) return false;
            DownloadManager.Request download = new DownloadManager.Request(Uri.parse(format.getUrl()))
                    .setTitle(item.title)
                    .setDescription(item.artist)
                    .setMimeType(format.getMimeType().isBlank() ? "audio/*" : format.getMimeType())
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setAllowedOverMetered(true).setAllowedOverRoaming(false)
                    .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_MUSIC,
                            "Morphe/" + item.videoId + extension);
            manager.enqueue(download);
            return true;
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not queue collection item " + item.videoId, ex);
            return false;
        }
    }

    private static List<Item> fetchItems(String playlistId) throws Exception {
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
        return new ArrayList<>(unique.values());
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

    private record Item(String videoId, String title, String artist, String album,
                        int durationSeconds, String thumbnailUrl) {}
}
