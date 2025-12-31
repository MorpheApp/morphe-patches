package app.morphe.extension.shared.oauth2.requests;

import static app.morphe.extension.shared.requests.Route.Method.POST;

import java.io.IOException;
import java.net.HttpURLConnection;

import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

@SuppressWarnings("unused")
final class OAuth2Routes {
    private static final String OAUTH2_GOOGLE_API_URL = "https://oauth2.googleapis.com/";
    private static final String OAUTH2_YOUTUBE_API_URL = "https://www.youtube.com/o/oauth2/";
    private static final String USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.47.48(Linux; U; Android 10; en_US; Quest Build/QQ3A.200805.001) gzip";

    /**
     * TCP connection and HTTP read timeout.
     */
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000; // 10 Seconds.

    static final Route ACCESS_TOKEN = new Route(POST, "token");
    static final Route DEVICE_CODE = new Route(POST, "device/code");
    static final Route REVOKE_TOKEN = new Route(POST, "revoke");

    private OAuth2Routes() {
    }

    private static void applyCommonPostRequestSettings(HttpURLConnection connection) {
        connection.setRequestProperty("Pragma", "no-cache");
        connection.setRequestProperty("Cache-Control", "no-cache");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
    }

    static HttpURLConnection getJsonConnectionFromRoute(Route route, String... params) throws IOException {
        HttpURLConnection connection = Requester.getConnectionFromRoute(OAUTH2_YOUTUBE_API_URL, route, params);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("Accept", "application/json");
        applyCommonPostRequestSettings(connection);
        return connection;
    }

    static HttpURLConnection getUrlConnectionFromRoute(Route route, String... params) throws IOException {
        HttpURLConnection connection = Requester.getConnectionFromRoute(OAUTH2_GOOGLE_API_URL, route, params);
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        applyCommonPostRequestSettings(connection);
        return connection;
    }
}