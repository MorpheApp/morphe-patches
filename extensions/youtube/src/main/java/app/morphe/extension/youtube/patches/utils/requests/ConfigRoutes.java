/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.youtube.patches.VersionCheckPatch;

public final class ConfigRoutes {

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    private static final int CLIENT_ID = 3;
    private static final String CLIENT_VERSION = VersionCheckPatch.IS_21_21_OR_GREATER
            // It seems that YT 21.21+ includes an experimental flag that is incompatible with the phone layout.
            // Temporarily restrict to YT 21.20.
            ? "21.20.402"
            : Utils.getAppVersionName();
    private static final String PACKAGE_NAME = "com.google.android.youtube";

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000;

    public static final Route.CompiledRoute GET_CONFIG = new Route(
            Route.Method.POST,
            "config" +
                    "?fields=responseContext.globalConfigGroup.bytesSerializedColdConfigGroup," +
                    "responseContext.globalConfigGroup.coldHashData" +
                    "&alt=proto"
    ).compile();

    private ConfigRoutes() {
    }

    public static byte[] configBody() {
        try {
            JSONObject client = new JSONObject();
            client.put("clientName", "ANDROID");
            client.put("clientVersion", CLIENT_VERSION);
            client.put("deviceMake", Build.MANUFACTURER);
            client.put("deviceModel", Build.MODEL);
            client.put("osName", "Android");
            client.put("osVersion", Build.VERSION.RELEASE);
            // The modern video action bar is not enabled in tablet / unknown cold config data.
            client.put("clientFormFactor", "UNKNOWN_FORM_FACTOR");
            client.put("androidSdkVersion", Build.VERSION.SDK_INT);
            client.put("screenWidthPoints", 1280);
            client.put("screenHeightPoints", 720);
            client.put("screenPixelDensity", 4);
            client.put("screenDensityFloat", 4);

            Locale localeDefault = Locale.getDefault();
            client.put("hl", localeDefault.getLanguage());
            client.put("gl", localeDefault.getCountry());

            JSONObject context = new JSONObject();
            context.put("client", client);

            JSONObject innerTubeBody = new JSONObject();
            innerTubeBody.put("context", context);
            return innerTubeBody.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "configBody failed", ex);
        }
        return new byte[0];
    }

    public static HttpURLConnection getConnection(Route.CompiledRoute route, Map<String, String> requestHeaders) throws IOException {
        String userAgent = String.format(Locale.US,
                "%s/%s(Linux; U; Android %s; %s; %s Build/%s)",
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

        if (requestHeaders != null) {
            for (Map.Entry<String, String> entry : requestHeaders.entrySet()) {
                String value = entry.getValue();
                if (value != null && !value.isEmpty()) {
                    connection.setRequestProperty(entry.getKey(), value);
                }
            }
        }

        return connection;
    }
}
