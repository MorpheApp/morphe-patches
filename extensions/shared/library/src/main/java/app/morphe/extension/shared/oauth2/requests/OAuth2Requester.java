package app.morphe.extension.shared.oauth2.requests;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.oauth2.requests.OAuth2Routes.getJsonConnectionFromRoute;
import static app.morphe.extension.shared.oauth2.requests.OAuth2Routes.getUrlConnectionFromRoute;

import android.net.Uri;

import androidx.annotation.GuardedBy;
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
import app.morphe.extension.shared.oauth2.object.AccessTokenData;
import app.morphe.extension.shared.oauth2.object.ActivationCodeData;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.BaseSettings;

public class OAuth2Requester {
    /**
     * Response code of a successful API call.
     */
    private static final int HTTP_STATUS_CODE_SUCCESS = 200;

    /**
     * Response code of a failed API call.
     */
    private static final int HTTP_STATUS_CODE_FAILED = 400;

    /**
     * The client id of the Android YouTube VR client.
     * This value is unique and does not change.
     */
    private static final String CLIENT_ID =
            "652469312169-4lvs9bnhr9lpns9v451j5oivd81vjvu1.apps.googleusercontent.com";

    /**
     * The client secret of the Android YouTube VR client.
     * This value is unique and does not change.
     */
    private static final String CLIENT_SECRET = "3fTWrBJI5Uojm1TK7_iJCW5Z";

    /**
     * Device model enum name for the Android YouTube VR app.
     * <p>
     * Available values are [UNKNOWN], [QUEST1], [QUEST2], [QUEST_PRO],
     * [MOOHAN], [PICO4], [QUEST3], [QUEST3S], [PICO4_ULTRA], and [ANDROID_XR].
     */
    private static final String DEVICE_MODEL = "QUEST1";

    /**
     * Access token scope.
     * Permissions are granted only for YouTube.
     */
    private static final String OAUTH2_SCOPE =
            "https://www.googleapis.com/auth/youtube";

    /**
     * Used when issuing an access token without a refresh token.
     * An unexpired device code is required.
     */
    private static final String GRANT_TYPE_DEFAULT =
            "http://oauth.net/grant_type/device/1.0";

    /**
     * Used when issuing an access token with a refresh token.
     */
    private static final String GRANT_TYPE_REFRESH = "refresh_token";

    @Nullable
    @GuardedBy("OAuth2Requester.class")
    private static ActivationCodeData lastFetchedActivationCodeData;

    @Nullable
    @GuardedBy("OAuth2Requester.class")
    private static AccessTokenData lastFetchedAccessTokenData;

    /**
     * The value of 'Authorization' in the header.
     * Bearer token is used on mobile devices.
     */
    @GuardedBy("OAuth2Requester.class")
    private static String authorization = "";

    private OAuth2Requester() {
    }

    public static void setAuthorization(AccessTokenData accessTokenData) {
        synchronized (OAuth2Requester.class) {
            // Bearer y29.xxx...
            authorization = accessTokenData.tokenType + " " + accessTokenData.accessToken;
        }
    }

    private static void handleConnectionError(String toastMessage, @Nullable Exception ex) {
        if (BaseSettings.DEBUG_TOAST_ON_ERROR.get()) {
            Utils.showToastShort(toastMessage);
        }
        if (ex != null) {
            Logger.printInfo(() -> toastMessage, ex);
        }
    }

    private static void clearAll(boolean clearedByUser) {
        if (!clearedByUser) {
            Utils.showToastShort(str("morphe_oauth2_toast_invalid"));
        }

        synchronized (OAuth2Requester.class) {
            BaseSettings.OAUTH2_REFRESH_TOKEN.resetToDefault();
            lastFetchedActivationCodeData = null;
            lastFetchedAccessTokenData = null;
            authorization = "";
        }

        Utils.showToastShort(str("morphe_oauth2_toast_reset"));
    }

    private static boolean isActivationCodeDataAvailable(ActivationCodeData activationCodeData) {
        return activationCodeData != null && !activationCodeData.isExpired();
    }

    public static boolean isActivationCodeDataAvailable() {
        synchronized (OAuth2Requester.class) {
            return isActivationCodeDataAvailable(lastFetchedActivationCodeData);
        }
    }

    private static boolean isAccessTokenDataAvailable() {
        synchronized (OAuth2Requester.class) {
            AccessTokenData accessTokenDataData = lastFetchedAccessTokenData;
            return accessTokenDataData != null && !accessTokenDataData.isExpired();
        }
    }

    /**
     * Check the validity of the access token before the video starts.
     * Blocking call, and must be made off the main thread.
     */
    public static synchronized String getAndUpdateAccessTokenIfNeeded() {
        Utils.verifyOffMainThread();

        synchronized (OAuth2Requester.class) {
            String refreshToken = BaseSettings.OAUTH2_REFRESH_TOKEN.get();

            // Refresh token is empty, the user has not signed in to VR.
            if (refreshToken.isEmpty()) {
                return authorization;
            }

            // Access token has not expired, do nothing.
            if (isAccessTokenDataAvailable()) {
                return authorization;
            }

            // Access token has expired, so reissue it.
            updateAccessTokenData(refreshToken);

            return authorization;
        }
    }

    /**
     * Revoke token using OAuth2 API.
     * Safe to call from any thread.
     */
    public static void revokeToken() {
        Utils.runOnBackgroundThread(() -> {
            synchronized (OAuth2Requester.class) {
                String refreshToken = BaseSettings.OAUTH2_REFRESH_TOKEN.get();

                if (!refreshToken.isEmpty()) {
                    if (!OAuth2Requester.revokeRefreshToken(refreshToken)) {
                        Logger.printException(() -> "Failed to revoke refresh token");
                    }
                }
                clearAll(true);
            }
        });
    }

    @Nullable
    public static ActivationCodeData getActivationCodeData() {
        Utils.verifyOffMainThread();

        try {
            HttpURLConnection connection = getJsonConnectionFromRoute(OAuth2Routes.DEVICE_CODE);

            JSONObject jsonObject = new JSONObject();
            jsonObject.put("client_id", CLIENT_ID);
            jsonObject.put("scope", OAUTH2_SCOPE);
            // Android YouTube VR app also uses random UUIDs.
            jsonObject.put("device_id", UUID.randomUUID().toString());
            jsonObject.put("device_model", DEVICE_MODEL);

            byte[] body = jsonObject.toString().getBytes(StandardCharsets.UTF_8);
            connection.setFixedLengthStreamingMode(body.length);
            connection.getOutputStream().write(body);

            final int responseCode = connection.getResponseCode();

            if (responseCode == HTTP_STATUS_CODE_SUCCESS) {
                synchronized (OAuth2Requester.class) {
                    ActivationCodeData fetchedActivationCodeData = new ActivationCodeData(Requester.parseJSONObjectAndDisconnect(connection));
                    Logger.printDebug(() -> "deviceCode: " + fetchedActivationCodeData);
                    lastFetchedActivationCodeData = fetchedActivationCodeData;
                    return fetchedActivationCodeData;
                }
            }
            handleConnectionError(str("morphe_oauth2_connection_failure_status", responseCode), null);
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

        synchronized (OAuth2Requester.class) {
            try {
                ActivationCodeData activationCodeData = lastFetchedActivationCodeData;
                if (!isActivationCodeDataAvailable(activationCodeData)) {
                    Logger.printDebug(() -> "Activation code has expired");
                    clearAll(false);
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

                final int responseCode = connection.getResponseCode();

                if (responseCode == HTTP_STATUS_CODE_SUCCESS) {
                    AccessTokenData fetchedAccessTokenData = new AccessTokenData(Requester.parseJSONObjectAndDisconnect(connection));
                    Logger.printDebug(() -> "getRefreshTokenData updated lastFetchedAccessTokenData");
                    lastFetchedAccessTokenData = fetchedAccessTokenData;
                    return fetchedAccessTokenData;
                }
                handleConnectionError(str("morphe_oauth2_connection_failure_status", responseCode), null);
            } catch (SocketTimeoutException ex) {
                handleConnectionError(str("morphe_oauth2_connection_failure_timeout"), ex);
            } catch (IOException ex) {
                handleConnectionError(str("morphe_oauth2_connection_failure_generic"), ex);
            } catch (Exception ex) {
                Logger.printException(() -> "getRefreshTokenData failure", ex);
            }
            return null;
        }
    }

    private static void updateAccessTokenData(String refreshToken) {
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

            final int responseCode = connection.getResponseCode();

            if (responseCode == HTTP_STATUS_CODE_SUCCESS) {
                synchronized (OAuth2Requester.class) {
                    AccessTokenData fetchedAccessTokenData = new AccessTokenData(refreshToken,
                            Requester.parseJSONObjectAndDisconnect(connection));
                    // Access token expires after 1 hour. Value is mostly safe to potentially
                    // disclose in a log file, but it's still not logged.
                    Logger.printDebug(() -> "updateAccessTokenData updated lastFetchedAccessTokenData");

                    lastFetchedAccessTokenData = fetchedAccessTokenData;
                    setAuthorization(fetchedAccessTokenData);
                }
            } else if (responseCode == HTTP_STATUS_CODE_FAILED) {
                // Tokens are revoked for the following reasons:
                // 1. The user changes their password
                // 2. The user logs out of the session in their Google Account settings
                // 3. The refresh token has not been used for 6 months
                //
                // In this case, a response code of 400 is returned
                // Since the refresh token is no longer valid, all locally stored tokens are removed
                Logger.printDebug(() -> "Invalid token, clear all");
                handleConnectionError(str("morphe_oauth2_connection_failure_status_400", responseCode), null);
                clearAll(false);
            } else {
                handleConnectionError(str("morphe_oauth2_connection_failure_status", responseCode), null);
            }
        } catch (SocketTimeoutException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_timeout"), ex);
        } catch (IOException ex) {
            handleConnectionError(str("morphe_oauth2_connection_failure_generic"), ex);
        } catch (Exception ex) {
            Logger.printException(() -> "updateAccessTokenData failure", ex);
        }
    }

    private static boolean revokeRefreshToken(String refreshToken) {
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

            final int responseCode = connection.getResponseCode();

            if (responseCode == HTTP_STATUS_CODE_SUCCESS) {
                return true;
            }
            handleConnectionError(str("morphe_oauth2_connection_failure_status", responseCode), null);
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
