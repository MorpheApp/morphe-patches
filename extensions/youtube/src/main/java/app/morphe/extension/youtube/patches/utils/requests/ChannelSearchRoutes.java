/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2964
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

/**
 * Searching inside a channel is a browse request for the channel "Search" tab. The backend of
 * this app is not served that tab, so the request is sent unauthenticated as the mobile web
 * client, which is. The channel description is fetched the same way, as unauthenticated
 * requests are not auto-translated.
 */
public final class ChannelSearchRoutes {

    private static final String YT_API_URL = "https://youtubei.googleapis.com/youtubei/v1/";

    private static final String CLIENT_NAME = "MWEB";
    /**
     * Lists the uploads of a channel as a list of 100 uploads per page. The mobile web client lists
     * 20 per page, and lists only the 20 newest uploads if the newest one is a Short.
     */
    private static final String UPLOADS_CLIENT_NAME = "WEB";
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

    /**
     * The channel description in the default language of the channel, regardless of the language
     * of the request. The description of the channel metadata is instead in the language of
     * the request, if the uploader added a translation of the description.
     */
    public static final Route.CompiledRoute GET_CHANNEL_DESCRIPTION = new Route(
            Route.Method.POST,
            "browse" +
                    "?prettyPrint=false" +
                    "&fields=microformat.microformatDataRenderer.channelProfileMicroformatDetails.profilePage.description"
    ).compile();

    /**
     * The title of a video in the language of the request, if the uploader added a translation
     * of the title. Titles that are not translated by the uploader are the original title.
     */
    public static final Route.CompiledRoute GET_LOCALIZED_VIDEO_TITLE = new Route(
            Route.Method.POST,
            "next" +
                    "?prettyPrint=false" +
                    "&fields=playerOverlays.playerOverlayRenderer.videoDetails.playerOverlayVideoDetailsRenderer.title"
    ).compile();

    /**
     * The exact publish date and view count of a video, for search results that are not in
     * the listed uploads of the channel. The search only shows these rounded, such as
     * "3 years ago" or "1.2M views", which is not enough to sort by them. The length is shown
     * for every video but live streams and premieres.
     * The description and tags tell whether a video matches a search by more than its title.
     */
    public static final Route.CompiledRoute GET_VIDEO_DETAILS = new Route(
            Route.Method.POST,
            "player" +
                    "?prettyPrint=false" +
                    "&fields=microformat.playerMicroformatRenderer.publishDate" +
                    ",videoDetails.viewCount,videoDetails.lengthSeconds" +
                    ",videoDetails.shortDescription,videoDetails.keywords"
    ).compile();

    /**
     * If the video is live now or upcoming, for the body of {@link #createWebVideoBody(String, Locale)}.
     * The unauthenticated player response usually asks to sign in instead of telling it.
     * The response leaves out isLive if it is false, and has the video id if the server answered.
     */
    public static final Route.CompiledRoute GET_LIVE_STATUS = new Route(
            Route.Method.POST,
            "next" +
                    "?prettyPrint=false" +
                    "&fields=currentVideoEndpoint.watchEndpoint.videoId" +
                    ",contents.twoColumnWatchNextResults.results.results.contents" +
                    ".videoPrimaryInfoRenderer.viewCount.videoViewCountRenderer.isLive"
    ).compile();

    private static final String SEARCH_RESULT_VIDEO_FIELD =
            "contents.sectionListRenderer.contents.itemSectionRenderer.contents.videoWithContextRenderer";

    /**
     * The search results of a video id or of a title, which include the video with its title as shown
     * in the lists in the language of the request, or in the language of the searched title.
     * Titles that are not translated by the uploader can be auto-translated in the lists,
     * but not in the title of {@link #GET_LOCALIZED_VIDEO_TITLE}.
     */
    public static final Route.CompiledRoute GET_LIST_VIDEO_TITLE = new Route(
            Route.Method.POST,
            "search" +
                    "?prettyPrint=false" +
                    "&fields=" + SEARCH_RESULT_VIDEO_FIELD + ".videoId" +
                    "," + SEARCH_RESULT_VIDEO_FIELD + ".headline.runs"
    ).compile();

    private ChannelSearchRoutes() {
    }

    /**
     * The language of the request includes the country, as titles can be translated
     * to a regional variant of the language, such as zh-CN and zh-TW.
     * The country of the request is the default country, as the server rejects some countries.
     */
    public static byte[] createVideoBody(String videoId, Locale locale) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createVideoContext(locale));
            body.put("videoId", videoId);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createVideoBody failed", ex);
        }
        return new byte[0];
    }

    /**
     * Same as {@link #createVideoBody(String, Locale)}, for the search of a video id or of a title.
     */
    public static byte[] createVideoSearchBody(String query, Locale locale) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createVideoContext(locale));
            body.put("query", query);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createVideoSearchBody failed", ex);
        }
        return new byte[0];
    }

    /**
     * Like {@link #createVideoBody(String, Locale)}, but as the web client, whose watch page response
     * has the layout of {@link #GET_LIVE_STATUS}, with the language without the country, and with
     * the country of the locale instead of the default country.
     */
    public static byte[] createWebVideoBody(String videoId, Locale locale) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createContext(UPLOADS_CLIENT_NAME, locale));
            body.put("videoId", videoId);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createWebVideoBody failed", ex);
        }
        return new byte[0];
    }

    private static JSONObject createVideoContext(Locale locale) throws JSONException {
        String language = orDefault(locale.getLanguage(), DEFAULT_LANGUAGE);
        if (!locale.getCountry().isEmpty()) {
            language += "-" + locale.getCountry();
        }
        JSONObject client = new JSONObject();
        client.put("clientName", CLIENT_NAME);
        client.put("clientVersion", CLIENT_VERSION);
        client.put("hl", language);
        client.put("gl", DEFAULT_COUNTRY);

        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
    }

    public static byte[] createChannelBody(String channelId, Locale locale) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createContext(locale));
            body.put("browseId", channelId);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createChannelBody failed", ex);
        }
        return new byte[0];
    }

    /**
     * The uploads playlist of a channel is its id with the "UC" prefix replaced,
     * and is browsed like a channel.
     */
    public static byte[] createUploadsBody(String channelId, Locale locale) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createContext(UPLOADS_CLIENT_NAME, locale));
            body.put("browseId", "VLUU" + channelId.substring(2));

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createUploadsBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] createUploadsContinuationBody(String continuationToken, Locale locale) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createContext(UPLOADS_CLIENT_NAME, locale));
            body.put("continuation", continuationToken);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createUploadsContinuationBody failed", ex);
        }
        return new byte[0];
    }

    /**
     * Browse params of the channel "Posts" tab for the web client: {2: "community", 110: {1: {9: {}}}}.
     * If the server does not take these, the params of the tab are read from the tabs of the response.
     */
    public static final String POSTS_TAB_PARAMS = "Egljb21tdW5pdHnyBgQKAkoA";

    /**
     * The posts tab of a channel, as the web client, which is served it without signing in.
     *
     * @param params Params of the posts tab.
     */
    public static byte[] createPostsTabBody(String channelId, String params, Locale locale) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createContext(UPLOADS_CLIENT_NAME, locale));
            body.put("browseId", channelId);
            body.put("params", params);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createPostsTabBody failed", ex);
        }
        return new byte[0];
    }

    public static byte[] createBody(String channelId, String query, Locale locale) {
        try {
            JSONObject body = new JSONObject();
            body.put("context", createContext(locale));
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
            JSONObject body = new JSONObject();
            body.put("context", createContext(locale));
            body.put("continuation", continuationToken);

            return body.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException ex) {
            Logger.printException(() -> "createContinuationBody failed", ex);
        }
        return new byte[0];
    }

    private static JSONObject createContext(Locale locale) throws JSONException {
        return createContext(CLIENT_NAME, locale);
    }

    private static JSONObject createContext(String clientName, Locale locale) throws JSONException {
        JSONObject client = new JSONObject();
        client.put("clientName", clientName);
        client.put("clientVersion", CLIENT_VERSION);
        client.put("hl", orDefault(locale.getLanguage(), DEFAULT_LANGUAGE));
        client.put("gl", orDefault(locale.getCountry(), DEFAULT_COUNTRY));

        JSONObject context = new JSONObject();
        context.put("client", client);
        return context;
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

    /**
     * @return The response, or null if the request failed.
     */
    @Nullable
    public static JSONObject fetch(Route.CompiledRoute route, byte[] requestBody) {
        Utils.verifyOffMainThread();

        try {
            HttpURLConnection connection = getConnection(route);
            connection.setFixedLengthStreamingMode(requestBody.length);
            connection.getOutputStream().write(requestBody);

            final int responseCode = connection.getResponseCode();
            if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
                String error = Requester.parseErrorStringAndDisconnect(connection);
                Logger.printDebug(() -> route.getCompiledRoute() + " failed with code: "
                        + responseCode + " error: " + error);
                return null;
            }

            return Requester.parseJSONObject(connection);
        } catch (SocketTimeoutException ex) {
            Logger.printInfo(() -> "Connection timeout", ex);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Network error", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "fetch failure", ex);
        }
        return null;
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
