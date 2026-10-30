package app.morphe.extension.music.patches.downloads;

import android.os.Build;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

/** Creates a YouTube Music playlist from downloaded tracks. */
public final class LocalDownloadsPlaylistHelper {

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";
    private static final int CLIENT_ID = 3;
    private static final String CLIENT_NAME = "ANDROID";
    private static final String CLIENT_VERSION = "20.26.46";
    private static final String PACKAGE_NAME = "com.google.android.youtube";
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000;

    private static final Route.CompiledRoute CREATE_PLAYLIST = new Route(
            Route.Method.POST, "playlist/create?prettyPrint=false"
    ).compile();

    private LocalDownloadsPlaylistHelper() {
    }

    @Nullable
    public static String createPlaylist(String title, List<String> videoIds) {
        if (videoIds == null || videoIds.isEmpty()) {
            return null;
        }

        try {
            JSONObject body = new JSONObject();
            body.put("context", androidContext());
            body.put("contentCheckOk", true);
            body.put("racyCheckOk", true);
            body.put("params", "CAQ%3D");
            body.put("title", title == null || title.trim().isEmpty() ? "Downloaded songs" : title.trim());

            JSONArray ids = new JSONArray();
            for (String videoId : videoIds) {
                if (videoId != null && !videoId.trim().isEmpty()) {
                    ids.put(videoId.trim());
                }
            }
            if (ids.length() == 0) {
                return null;
            }
            body.put("videoIds", ids);

            byte[] requestBody = body.toString().getBytes(StandardCharsets.UTF_8);
            HttpURLConnection connection = getConnection(null);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                Logger.printInfo(() -> "Create playlist failed with code: " + responseCode);
                return null;
            }

            JSONObject json = Requester.parseJSONObject(connection);
            return json == null ? null : json.optString("playlistId", null);
        } catch (Exception ex) {
            Logger.printException(() -> "Could not create playlist from downloaded tracks", ex);
            return null;
        }
    }

    private static HttpURLConnection getConnection(@Nullable Map<String, String> authHeaders) throws IOException {
        String userAgent = String.format(Locale.US,
                "%s/%s (Linux; U; Android %s; %s; %s Build/%s)",
                PACKAGE_NAME, CLIENT_VERSION, Build.VERSION.RELEASE,
                Locale.getDefault(), Build.MODEL, Build.ID);

        HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, CREATE_PLAYLIST);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("X-YouTube-Client-Name", String.valueOf(CLIENT_ID));
        connection.setRequestProperty("X-YouTube-Client-Version", CLIENT_VERSION);
        connection.setRequestProperty("X-GOOG-API-FORMAT-VERSION", "2");
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

        if (authHeaders != null) {
            for (Map.Entry<String, String> entry : authHeaders.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    connection.setRequestProperty(entry.getKey(), value);
                }
            }
        }

        return connection;
    }

    private static JSONObject androidContext() throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", CLIENT_VERSION);
        client.put("deviceMake", Build.MANUFACTURER);
        client.put("deviceModel", Build.MODEL);
        client.put("osName", "Android");
        client.put("osVersion", Build.VERSION.RELEASE);
        client.put("androidSdkVersion", Build.VERSION.SDK_INT);

        Locale locale = Locale.getDefault();
        client.put("hl", locale.getLanguage());
        client.put("gl", locale.getCountry());

        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
    }
}

