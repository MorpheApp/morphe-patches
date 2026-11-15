/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.dearrow;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.Route;
import app.morphe.extension.youtube.settings.Settings;

/**
 * DeArrow account details of the user.
 * <p>
 * DeArrow uses the same user id as SponsorBlock, and the username is shared by both.
 * The DeArrow server is always used, even if SponsorBlock uses another API server.
 */
public final class DeArrowAccountRequest {

    private static final Route GET_USER_INFO = new Route(Route.Method.GET,
            "/api/userInfo?userID={user_id}&values=[\"userID\",\"userName\",\"titleSubmissionCount\",\"thumbnailSubmissionCount\"]");

    private static final Route SET_USERNAME = new Route(Route.Method.POST,
            "/api/setUsername?userID={user_id}&username={username}");

    private static final int TIMEOUT_MILLISECONDS = 10_000;

    /**
     * @param publicUserId         The hashed user id, shown on websites such as sb.ltn.fi.
     * @param userName             The username, or the public user id if none was set.
     * @param titleSubmissions     Number of titles the user submitted.
     * @param thumbnailSubmissions Number of thumbnails the user submitted.
     */
    public record AccountInfo(String publicUserId, String userName,
                              int titleSubmissions, int thumbnailSubmissions) {
        /**
         * @return If the user set a username, which is otherwise the same as the public user id.
         */
        public boolean hasUserName() {
            return !userName.equals(publicUserId);
        }
    }

    private DeArrowAccountRequest() {
    }

    /**
     * @return If the user has a private user id, which is created on the first vote or submission
     *         to DeArrow or SponsorBlock.
     */
    public static boolean userHasPrivateUserId() {
        return !Settings.SB_PRIVATE_USER_ID.get().isEmpty();
    }

    /**
     * @return The account details, or null if the fetch failed.
     */
    @Nullable
    public static AccountInfo fetchAccountInfo() {
        Utils.verifyOffMainThread();
        try {
            HttpURLConnection connection = getConnection(GET_USER_INFO, Settings.SB_PRIVATE_USER_ID.get());
            JSONObject json = Requester.parseJSONObjectAndDisconnect(connection);
            String publicUserId = json.getString("userID");
            AccountInfo info = new AccountInfo(
                    publicUserId,
                    json.optString("userName", publicUserId),
                    json.optInt("titleSubmissionCount"),
                    json.optInt("thumbnailSubmissionCount")
            );
            Logger.printDebug(() -> "DeArrow account: " + info);
            return info;
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not fetch DeArrow account", ex);
        } catch (Exception ex) {
            Logger.printException(() -> "fetchAccountInfo failure", ex);
        }
        return null;
    }

    /**
     * @return The error to show, or null if the username was changed.
     */
    @Nullable
    public static String setUsername(String userName) {
        Utils.verifyOffMainThread();
        try {
            HttpURLConnection connection = getConnection(SET_USERNAME, Settings.SB_PRIVATE_USER_ID.get(),
                    URLEncoder.encode(userName, StandardCharsets.UTF_8.name()));
            final int responseCode = connection.getResponseCode();
            String responseMessage = connection.getResponseMessage();
            connection.disconnect();
            if (responseCode == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return null;
            }
            return str("morphe_dearrow_account_username_change_error", responseCode, responseMessage);
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not change DeArrow username", ex);
            return str("morphe_dearrow_account_username_change_error", 0, ex.getMessage());
        } catch (Exception ex) {
            Logger.printException(() -> "setUsername failure", ex);
            return str("morphe_dearrow_account_username_change_error", 0, ex.getMessage());
        }
    }

    private static HttpURLConnection getConnection(Route route, String... params) throws IOException {
        HttpURLConnection connection = Requester.getConnectionFromRoute(
                DeArrowBrandingRequest.API_URL, route, params);
        connection.setConnectTimeout(TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(TIMEOUT_MILLISECONDS);
        return connection;
    }
}
