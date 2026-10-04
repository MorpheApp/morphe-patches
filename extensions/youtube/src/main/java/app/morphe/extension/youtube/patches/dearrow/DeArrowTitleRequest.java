/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3447
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;

/**
 * Fetches the video titles submitted to DeArrow (<a href="https://dearrow.ajay.app">...</a>).
 * Used by {@link app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch}
 * to replace the video titles.
 */
public final class DeArrowTitleRequest {

    private static final String API_URL = "https://sponsor.ajay.app";

    /**
     * Videos are requested by the start of the SHA-256 hash of the video id,
     * so the server does not know which video is shown.
     */
    private static final Route GET_BRANDING = new Route(Route.Method.GET, "/api/branding/{hash_prefix}");

    private static final int HASH_PREFIX_LENGTH = 4;

    /**
     * The '>' at the start of each word of a title, separated by spaces.
     */
    private static final Pattern FORMATTER_OVERRIDE_PATTERN = Pattern.compile("(^| )>");

    /**
     * The title is shown as loading until DeArrow responds, so DeArrow is not waited for long.
     */
    private static final int CONNECTION_TIMEOUT_MILLISECONDS = 2 * 1000;

    /**
     * DeArrow titles that were fetched. Titles replaced before they are laid out
     * are only known by their text.
     */
    private static final Set<String> fetchedTitles = Collections.newSetFromMap(
            Collections.synchronizedMap(Utils.createSizeRestrictedMap(1000)));

    private DeArrowTitleRequest() {
    }

    /**
     * @return If the title is a DeArrow title that was fetched.
     */
    public static boolean isDeArrowTitle(String title) {
        return fetchedTitles.contains(title);
    }

    /**
     * @return The DeArrow title, or null if the video has no DeArrow title
     *         or DeArrow keeps the original title.
     * @throws IOException If DeArrow is not available or the title failed to fetch,
     *                     so it can be fetched again later.
     */
    @Nullable
    public static String fetchTitle(String videoId) throws IOException {
        if (!DeArrowPatch.canUseDeArrowAPI()) {
            throw new IOException("DeArrow is not available");
        }

        Route.CompiledRoute route;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(videoId.getBytes(StandardCharsets.UTF_8));
            StringBuilder hashPrefix = new StringBuilder(HASH_PREFIX_LENGTH);
            for (int i = 0; hashPrefix.length() < HASH_PREFIX_LENGTH; i++) {
                hashPrefix.append(String.format(Locale.US, "%02x", hash[i]));
            }
            route = GET_BRANDING.compile(hashPrefix.substring(0, HASH_PREFIX_LENGTH));
        } catch (Exception ex) {
            Logger.printException(() -> "fetchTitle failure", ex);
            return null;
        }

        final int responseCode;
        HttpURLConnection connection;
        try {
            connection = Requester.getConnectionFromCompiledRoute(API_URL, route);
            connection.setConnectTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(CONNECTION_TIMEOUT_MILLISECONDS);
            responseCode = connection.getResponseCode();
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch DeArrow title of: " + videoId, ex);
            DeArrowPatch.handleDeArrowError(API_URL + route.getCompiledRoute(), 0);
            throw ex;
        }

        // No video with the hash prefix has DeArrow data.
        if (responseCode == 404) {
            return null;
        }
        if (responseCode != Requester.HTTP_STATUS_CODE_SUCCESS) {
            DeArrowPatch.handleDeArrowError(API_URL + route.getCompiledRoute(), responseCode);
            throw new IOException("DeArrow response code: " + responseCode);
        }

        try {
            JSONObject branding = Requester.parseJSONObject(connection).optJSONObject(videoId);
            JSONArray titles = branding == null ? null : branding.optJSONArray("titles");
            if (titles == null) {
                return null;
            }

            // The server sorts the titles by votes. As done by the DeArrow extension, the first title
            // that is locked or not downvoted is used. If that title is the original title,
            // then DeArrow keeps the original title.
            for (int i = 0, length = titles.length(); i < length; i++) {
                JSONObject title = titles.optJSONObject(i);
                if (title == null || (!title.optBoolean("locked") && title.optInt("votes") < 0)) {
                    continue;
                }
                if (title.optBoolean("original")) {
                    return null;
                }

                // Words that are not auto formatted by the DeArrow extension start with '>'.
                String text = FORMATTER_OVERRIDE_PATTERN.matcher(title.optString("title")).replaceAll("$1").trim();
                if (text.isEmpty()) {
                    return null;
                }
                fetchedTitles.add(text);
                return text;
            }
            return null;
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not read DeArrow title of: " + videoId, ex);
            throw ex;
        } catch (Exception ex) {
            Logger.printException(() -> "fetchTitle failure", ex);
            return null;
        }
    }
}
