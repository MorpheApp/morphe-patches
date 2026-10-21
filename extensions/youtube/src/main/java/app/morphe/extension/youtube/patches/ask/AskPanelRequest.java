/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3897
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.ask;

import android.os.Build;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.innertube.utils.AuthUtils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

/**
 * Fetches the Ask panel of a video from its watch page, as the app gets it,
 * for when no Ask panel was saved yet to build the Ask panel of other videos.
 * The watch page has the Ask panel only for a signed-in account in a country with Ask,
 * and only for some videos.
 * The watch page also supplies the Elements templates of the Ask chat, which are loaded when the summarize
 * item is tapped in an app session that has not loaded them, see {@code AskFlyoutMenuPatch.openAskPanel}.
 */
final class AskPanelRequest {
    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    /**
     * The response is protobuf, as the app parses it, so the panel can be shown as is.
     */
    private static final Route.CompiledRoute GET_WATCH_PAGE = new Route(
            Route.Method.POST,
            "next?prettyPrint=false&alt=proto"
    ).compile();

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000;

    private AskPanelRequest() {
    }

    @Nullable
    static byte[] fetchWatchPage(String videoId) {
        Utils.verifyOffMainThread();

        if (AuthUtils.isNotLoggedIn()) {
            Logger.printDebug(() -> "Not fetching the watch page, not signed in");
            return null;
        }

        final long startTime = System.currentTimeMillis();
        try {
            byte[] requestBody = createBody(videoId);
            HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, GET_WATCH_PAGE);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("User-Agent", getUserAgent());
            for (Map.Entry<String, String> header : AuthUtils.getRequestHeader().entrySet()) {
                if (!header.getValue().isEmpty()) {
                    connection.setRequestProperty(header.getKey(), header.getValue());
                }
            }
            connection.setUseCaches(false);
            connection.setDoOutput(true);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                Logger.printDebug(() -> "Watch page request failed with code: " + responseCode);
                connection.disconnect();
                return null;
            }

            byte[] watchPage = Requester.parseBytes(connection);
            Logger.printDebug(() -> "Fetched watch page of video: " + videoId + " size: " + watchPage.length
                    + " took: " + (System.currentTimeMillis() - startTime) + "ms");
            return watchPage;
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Watch page request timed out", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Watch page request network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "Watch page request failed", ex);
        }
        return null;
    }

    private static byte[] createBody(String videoId) throws Exception {
        Locale locale = Requester.getAppLocale();
        JSONObject client = new JSONObject();
        client.put("clientName", "ANDROID");
        client.put("clientVersion", Utils.getAppVersionName());
        client.put("osName", "Android");
        client.put("osVersion", Build.VERSION.RELEASE);
        client.put("androidSdkVersion", Build.VERSION.SDK_INT);
        client.put("hl", locale.getLanguage());
        client.put("gl", locale.getCountry());

        JSONObject context = new JSONObject();
        context.put("client", client);

        JSONObject body = new JSONObject();
        body.put("context", context);
        body.put("videoId", videoId);
        return body.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String getUserAgent() {
        return String.format(Locale.US, "com.google.android.youtube/%s (Linux; U; Android %s; %s) gzip",
                Utils.getAppVersionName(), Build.VERSION.RELEASE, Build.MODEL);
    }
}
