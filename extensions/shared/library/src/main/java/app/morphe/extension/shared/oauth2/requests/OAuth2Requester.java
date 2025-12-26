package app.morphe.extension.shared.oauth2.requests;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.oauth2.requests.OAuth2Routes.getJsonConnectionFromRoute;
import static app.morphe.extension.shared.oauth2.requests.OAuth2Routes.getUrlConnectionFromRoute;

import android.net.Uri;

import androidx.annotation.Nullable;

import org.json.JSONObject;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.oauth2.OAuth2Helper;
import app.morphe.extension.shared.oauth2.object.AccessTokenData;
import app.morphe.extension.shared.oauth2.object.ActivationCodeData;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;

public class OAuth2Requester {
    /**
     * Response code of a successful API call
     */
    private static final int HTTP_STATUS_CODE_SUCCESS = 200;

    /**
     * Response code of a failed API call
     */
    private static final int HTTP_STATUS_CODE_FAILED = 400;

    /**
     * The client id of the Android YouTube VR client
     * This value is unique and does not change
     */
    private static final String CLIENT_ID =
            "652469312169-4lvs9bnhr9lpns9v451j5oivd81vjvu1.apps.googleusercontent.com";

    /**
     * The client secret of the Android YouTube VR client
     * This value is unique and does not change
     */
    private static final String CLIENT_SECRET = "3fTWrBJI5Uojm1TK7_iJCW5Z";

    /**
     * Device model enum name for the Android YouTube VR app
     * <p>
     * Available values are [UNKNOWN], [QUEST1], [QUEST2], [QUEST_PRO],
     * [MOOHAN], [PICO4], [QUEST3], [QUEST3S], [PICO4_ULTRA], and [ANDROID_XR].
     */
    private static final String DEVICE_MODEL = "QUEST1";

    /**
     * Access token scope
     * Permissions are granted only for YouTube
     */
    private static final String OAUTH2_SCOPE =
            "https://www.googleapis.com/auth/youtube";

    /**
     * Used when issuing an access token without a refresh token
     * An unexpired device code is required
     */
    private static final String GRANT_TYPE_DEFAULT =
            "http://oauth.net/grant_type/device/1.0";

    /**
     * Used when issuing an access token with a refresh token
     */
    private static final String GRANT_TYPE_REFRESH = "refresh_token";

    @Nullable
    private static volatile ActivationCodeData lastFetchedActivationCodeData;

    @Nullable
    private static volatile AccessTokenData lastFetchedAccessTokenData;

    private OAuth2Requester() {
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        if (BaseSettings.DEBUG_TOAST_ON_ERROR.get()) {
            Utils.showToastShort(toastMessage);
        }
        if (ex != null) {
            Logger.printInfo(() -> toastMessage, ex);
        }
    }

    public static void clearAll() {
        lastFetchedActivationCodeData = null;
        lastFetchedAccessTokenData = null;
    }

    public static boolean isActivationCodeDataAvailable() {
        return isActivationCodeDataAvailable(lastFetchedActivationCodeData);
    }

    private static boolean isActivationCodeDataAvailable(ActivationCodeData activationCodeData) {
        return activationCodeData != null && !activationCodeData.isExpired();
    }

    public static boolean isAccessTokenDataAvailable() {
        AccessTokenData accessTokenDataData = lastFetchedAccessTokenData;
        return accessTokenDataData != null && !accessTokenDataData.isExpired();
    }

    @Nullable
    public static ActivationCodeData getActivationCodeData() {
        Utils.verifyOffMainThread();

        try {
            HttpURLConnection connection = getJsonConnectionFromRoute(OAuth2Routes.DEVICE_CODE);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("client_id", CLIENT_ID);
            jsonObject.put("scope", OAUTH2_SCOPE);
            // Android YouTube VR app also uses random UUIDs
            jsonObject.put("device_id", UUID.randomUUID().toString());
            jsonObject.put("device_model", DEVICE_MODEL);

            byte[] body = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            int responseCode = connection.getResponseCode();

            if (responseCode == HTTP_STATUS_CODE_SUCCESS) {
                ActivationCodeData fetchedActivationCodeData = new ActivationCodeData(Requester.parseJSONObjectAndDisconnect(connection));
                Logger.printDebug(() -> "deviceCode: " + fetchedActivationCodeData);
                lastFetchedActivationCodeData = fetchedActivationCodeData;
                return fetchedActivationCodeData;
            } else {
                handleConnectionError(str("morphe_oauth2_connection_failure_status", responseCode), null);
            }
        } catch (SocketTimeoutException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_timeout"), ex);
        } catch (IOException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_generic"), ex);
        } catch (Exception ex) {
            Logger.printException(() -> "getActivationCodeData failure", ex);
        }
        return null;
    }

    @Nullable
    public static AccessTokenData getRefreshTokenData() {
        Utils.verifyOffMainThread();

        try {
            ActivationCodeData activationCodeData = lastFetchedActivationCodeData;
            if (!isActivationCodeDataAvailable(activationCodeData)) {
                Logger.printDebug(() -> "Activation code has expired");
                clearAll();
                return null;
            }

            HttpURLConnection connection = getJsonConnectionFromRoute(OAuth2Routes.ACCESS_TOKEN);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("client_id", CLIENT_ID);
            jsonObject.put("client_secret", CLIENT_SECRET);
            jsonObject.put("code", activationCodeData.deviceCode);
            jsonObject.put("grant_type", GRANT_TYPE_DEFAULT);

            byte[] body = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            int responseCode = connection.getResponseCode();

            if (responseCode == HTTP_STATUS_CODE_SUCCESS) {
                AccessTokenData fetchedAccessTokenData = new AccessTokenData(Requester.parseJSONObjectAndDisconnect(connection));
                Logger.printDebug(() -> "accessToken: " + fetchedAccessTokenData);
                lastFetchedAccessTokenData = fetchedAccessTokenData;
                return fetchedAccessTokenData;
            } else {
                handleConnectionError(str("morphe_oauth2_connection_failure_status", responseCode), null);
            }
        } catch (SocketTimeoutException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_timeout"), ex);
        } catch (IOException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_generic"), ex);
        } catch (Exception ex) {
            Logger.printException(() -> "getRefreshTokenData failure", ex);
        }
        return null;
    }

    @Nullable
    public static AccessTokenData getAccessTokenData(String refreshToken) {
        Utils.verifyOffMainThread();

        try {
            HttpURLConnection connection = getJsonConnectionFromRoute(OAuth2Routes.ACCESS_TOKEN);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("client_id", CLIENT_ID);
            jsonObject.put("client_secret", CLIENT_SECRET);
            jsonObject.put("refresh_token", refreshToken);
            jsonObject.put("grant_type", GRANT_TYPE_REFRESH);

            byte[] body = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            int responseCode = connection.getResponseCode();

            if (responseCode == HTTP_STATUS_CODE_SUCCESS) {
                AccessTokenData fetchedAccessTokenData = new AccessTokenData(refreshToken,
                        Requester.parseJSONObjectAndDisconnect(connection));
                Logger.printDebug(() -> "new accessToken: " + fetchedAccessTokenData);
                lastFetchedAccessTokenData = fetchedAccessTokenData;
                return fetchedAccessTokenData;
            } else if (responseCode == HTTP_STATUS_CODE_FAILED) {
                // Tokens are revoked for the following reasons:
                // 1. The user changes their password
                // 2. The user logs out of the session in their Google Account settings
                // 3. The refresh token has not been used for 6 months
                //
                // In this case, a response code of 400 is returned
                // Since the refresh token is no longer valid, all locally stored tokens are removed
                Logger.printDebug(() -> "Invalid token, clear all");
                OAuth2Helper.clearAll(false);
            } else {
                handleConnectionError(str("morphe_oauth2_connection_failure_status", responseCode), null);
            }
        } catch (SocketTimeoutException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_timeout"), ex);
        } catch (IOException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_generic"), ex);
        } catch (Exception ex) {
            Logger.printException(() -> "getAccessTokenData failure", ex);
        }
        return null;
    }

    public static boolean revokeRefreshToken(String refreshToken) {
        Utils.verifyOffMainThread();

        try {
            HttpURLConnection connection = getUrlConnectionFromRoute(OAuth2Routes.REVOKE_TOKEN);

            Uri bodyUri = new Uri.Builder()
                    .appendQueryParameter("token", refreshToken)
                    .build();
            String query = Objects.toString(bodyUri.getEncodedQuery());
            byte[] body = query.getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            int responseCode = connection.getResponseCode();

            if (responseCode == HTTP_STATUS_CODE_SUCCESS) {
                return true;
            } else {
                handleConnectionError(str("morphe_oauth2_connection_failure_status", responseCode), null);
            }
        } catch (SocketTimeoutException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_timeout"), ex);
        } catch (IOException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_generic"), ex);
        } catch (Exception ex) {
            Logger.printException(() -> "revokeAccessToken failure", ex);
        }
        return false;
    }
}
