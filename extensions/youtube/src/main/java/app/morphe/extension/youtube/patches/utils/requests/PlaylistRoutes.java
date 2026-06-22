/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

public final class PlaylistRoutes {

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    private static final int CLIENT_ID = 3;
    private static final String CLIENT_NAME = "ANDROID";
    private static final String CLIENT_VERSION = "20.26.46";
    private static final String PACKAGE_NAME = "com.google.android.youtube";

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000;

    public static final Route.CompiledRoute CREATE_PLAYLIST = new Route(
            Route.Method.POST, "playlist/create?prettyPrint=false"
    ).compile();

    public static final Route.CompiledRoute GET_SET_VIDEO_ID = new Route(
            Route.Method.POST, "next?prettyPrint=false"
    ).compile();

    public static final Route.CompiledRoute EDIT_PLAYLIST = new Route(
            Route.Method.POST, "browse/edit_playlist?fields=status,playlistEditResults"
    ).compile();

    public static final Route.CompiledRoute GET_PLAYLISTS = new Route(
            Route.Method.POST, "playlist/get_add_to_playlist?prettyPrint=false"
    ).compile();

    public static final Route.CompiledRoute BROWSE_PLAYLIST = new Route(
            Route.Method.POST, "browse?prettyPrint=false"
    ).compile();

    public static final Route.CompiledRoute GET_MIX_PLAYLIST = new Route(
            Route.Method.POST,
            "next" +
                    "?fields=contents.singleColumnWatchNextResults." +
                    "playlist.playlist.contents.playlistPanelVideoRenderer." +
                    "navigationEndpoint.coWatchWatchEndpointWrapperCommand." +
                    "watchEndpoint.watchEndpoint.playerParams&prettyPrint=false"
    ).compile();

    private PlaylistRoutes() {
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
        Locale localeDefault = Locale.getDefault();
        client.put("hl", localeDefault.getLanguage());
        client.put("gl", localeDefault.getCountry());

        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
    }

    private static JSONObject getBaseContentJson() throws JSONException {
        JSONObject body = new JSONObject();
        body.put("context", androidContext());
        body.put("contentCheckOk", true);
        body.put("racyCheckOk", true);
        return body;
    }

    public static byte[] createPlaylistBody(String videoId, String title) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("params", "CAQ%3D");
            body.put("title", title);
            JSONArray videoIds = new JSONArray();
            videoIds.put(videoId);
            body.put("videoIds", videoIds);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createPlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] getSetVideoIdBody(String videoId, String playlistId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("videoId", videoId);
            body.put("playlistId", playlistId);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "getSetVideoIdBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] editPlaylistBody(String videoId, String playlistId, String setVideoId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("playlistId", playlistId);

            JSONObject action = new JSONObject();
            if (setVideoId != null && !setVideoId.isEmpty()) {
                action.put("action", "ACTION_REMOVE_VIDEO");
                action.put("setVideoId", setVideoId);
            } else {
                action.put("action", "ACTION_ADD_VIDEO");
                action.put("addedVideoId", videoId);
            }
            JSONArray actions = new JSONArray();
            actions.put(action);
            body.put("actions", actions);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "editPlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] getPlaylistsBody(String playlistId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("playlistId", playlistId);
            body.put("excludeWatchLater", false);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "getPlaylistsBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] getMixPlaylistBody(String videoId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("videoId", videoId);
            body.put("playlistId", "RD" + videoId);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "getMixPlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] browsePlaylistBody(String playlistId) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", androidContext());
            body.put("browseId", "VL" + playlistId);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "browsePlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] savePlaylistBody(String playlistId, String libraryId) {
        try {
            JSONObject body = getBaseContentJson();
            body.put("playlistId", playlistId);

            JSONObject action = new JSONObject();
            action.put("action", "ACTION_ADD_PLAYLIST");
            action.put("addedFullListId", libraryId);
            JSONArray actions = new JSONArray();
            actions.put(action);
            body.put("actions", actions);
            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "savePlaylistBody failed", ex);
        }
        return new byte[0];
    }

    public static HttpURLConnection getConnection(Route.CompiledRoute route, Map<String, String> authHeaders) throws IOException {
        String userAgent = String.format(Locale.US,
                "%s/%s (Linux; U; Android %s; %s; %s Build/%s)",
                PACKAGE_NAME, CLIENT_VERSION, Build.VERSION.RELEASE,
                Locale.getDefault(), Build.MODEL, Build.ID);

        HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, route);
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
}
