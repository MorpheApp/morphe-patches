/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import android.util.Base64;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

/**
 * Searching inside a channel is a browse request for the channel "Search" tab. The backend of
 * this app is not served that tab, so the request is sent unauthenticated as the mobile web
 * client, which is.
 */
public final class ChannelSearchRoutes {

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    private static final String CLIENT_NAME = "MWEB";
    private static final String CLIENT_VERSION = "2.20250101.00.00";
    private static final String USER_AGENT = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    // The server rejects the request if either of these is sent empty.
    private static final String DEFAULT_LANGUAGE = "en";
    private static final String DEFAULT_COUNTRY = "US";

    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 10 * 1000;

    public static final Route.CompiledRoute CHANNEL_SEARCH = new Route(
            Route.Method.POST,
            "browse?prettyPrint=false"
    ).compile();

    private ChannelSearchRoutes() {
    }

    public static byte[] createBody(String channelId, String query, Locale locale) {
        try {
            String hl = orDefault(locale.getLanguage(), DEFAULT_LANGUAGE);
            String gl = orDefault(locale.getCountry(), DEFAULT_COUNTRY);

            JSONObject client = new JSONObject();
            client.put("clientName", CLIENT_NAME);
            client.put("clientVersion", CLIENT_VERSION);
            client.put("hl", hl);
            client.put("gl", gl);

            JSONObject context = new JSONObject();
            context.put("client", client);

            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("browseId", channelId);
            body.put("params", createSearchTabParams(query));
            body.put("query", query);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] createContinuationBody(String continuationToken, Locale locale) {
        try {
            String hl = orDefault(locale.getLanguage(), DEFAULT_LANGUAGE);
            String gl = orDefault(locale.getCountry(), DEFAULT_COUNTRY);

            JSONObject client = new JSONObject();
            client.put("clientName", CLIENT_NAME);
            client.put("clientVersion", CLIENT_VERSION);
            client.put("hl", hl);
            client.put("gl", gl);

            JSONObject context = new JSONObject();
            context.put("client", client);

            JSONObject body = new JSONObject();
            body.put("context", context);
            body.put("continuation", continuationToken);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createContinuationBody failed", ex);
        }
        return new byte[0];
    }

    private static String orDefault(String value, String fallback) {
        return value.isEmpty() ? fallback : value;
    }

    /**
     * Browse params of the channel "Search" tab, byte for byte what the server itself returns
     * when it resolves a channel search url for a mobile client:
     * {2: "search", 5: query, 23: 0, 50: "", 110: {1: {11: {}}}}.
     * The web shape of these params is a different one, which this backend ignores.
     */
    @NonNull
    private static String createSearchTabParams(String query) {
        byte[] queryBytes = query.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream params = new ByteArrayOutputStream();

        // Tab name.
        params.write(0x12);
        params.write(0x06);
        params.write('s'); params.write('e'); params.write('a');
        params.write('r'); params.write('c'); params.write('h');

        // Query.
        params.write(0x2A);
        writeVarInt(params, queryBytes.length);
        params.write(queryBytes, 0, queryBytes.length);

        // Empty fields the mobile shape carries.
        params.write(0xB8); params.write(0x01); params.write(0x00);
        params.write(0x92); params.write(0x03); params.write(0x00);

        // Tab selector.
        params.write(0xF2); params.write(0x06); params.write(0x04);
        params.write(0x0A); params.write(0x02); params.write(0x5A); params.write(0x00);

        return Base64.encodeToString(params.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP);
    }

    private static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    public static HttpURLConnection getConnection(Route.CompiledRoute route) throws IOException {
        HttpURLConnection connection = Requester.getConnectionFromCompiledRoute(YT_API_URL, route);
        connection.setRequestProperty("Content-Type", "application/json");
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setUseCaches(false);
        connection.setDoOutput(true);
        connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);

        return connection;
    }
}
