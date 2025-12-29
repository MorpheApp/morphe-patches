package app.morphe.extension.shared.oauth2;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.oauth2.object.AccessTokenData;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.settings.BaseSettings;

public class OAuth2Helper {
    /**
     * The value of 'Authorization' in the header
     * Bearer token is used on mobile devices
     */
    @GuardedBy("OAuth2Helper.class")
    private static String authorization = "";

    public static void clearAll(boolean clearedByUser) {
        if (!clearedByUser) {
            Utils.showToastShort(str("morphe_oauth2_toast_invalid"));
        }

        synchronized (OAuth2Helper.class) {
            BaseSettings.OAUTH2_REFRESH_TOKEN.resetToDefault();
            OAuth2Requester.clearAll();
            authorization = "";
        }

        Utils.showToastShort(str("morphe_oauth2_toast_reset"));
    }

    public static void setAuthorization(AccessTokenData accessTokenData) {
        synchronized (OAuth2Helper.class) {
            // Bearer y29.xxx...
            authorization = accessTokenData.tokenType + " " + accessTokenData.accessToken;
        }
    }

    /**
     * Check the validity of the access token before the video starts.
     * Blocking call, and must be made off the main thread.
     */
    public static synchronized String getAndUpdateAccessTokenIfNeeded() {
        Utils.verifyOffMainThread();

        synchronized (OAuth2Helper.class) {
            String refreshToken = BaseSettings.OAUTH2_REFRESH_TOKEN.get();

            // Refresh token is empty, the user has not signed in to VR.
            if (refreshToken.isEmpty()) {
                return authorization;
            }

            // Access token has not expired, do nothing.
            if (OAuth2Requester.isAccessTokenDataAvailable()) {
                return authorization;
            }

            // Access token has expired, so reissue it.
            AccessTokenData accessTokenData = OAuth2Requester.getAccessTokenData(refreshToken);

            if (accessTokenData == null) {
                Logger.printException(() -> "Failed to update access token");
            } else {
                setAuthorization(accessTokenData);
            }

            return authorization;
        }
    }

    /**
     * Revoke token using OAuth2 API.
     * Safe to call from any thread.
     */
    public static void revokeToken() {
        Utils.runOnBackgroundThread(() -> {
            synchronized (OAuth2Helper.class) {
                String refreshToken = BaseSettings.OAUTH2_REFRESH_TOKEN.get();

                if (refreshToken.isEmpty()) {
                    clearAll(true);
                } else if (OAuth2Requester.revokeRefreshToken(refreshToken)) {
                    clearAll(true);
                    Logger.printException(() -> "Failed to revoke refresh token");
                }
            }
        });
    }
}
