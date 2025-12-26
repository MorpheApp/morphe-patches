package app.morphe.extension.shared.oauth2;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.oauth2.object.AccessTokenData;
import app.morphe.extension.shared.oauth2.requests.OAuth2Requester;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.Utils;

public class OAuth2Helper {
    /**
     * The value of 'Authorization' in the header
     * Bearer token is used on mobile devices
     */
    private static volatile String authorization = "";

    public static void clearAll(boolean clearedByUser) {
        Utils.runOnMainThreadDelayed(() -> {
            if (!clearedByUser) {
                Utils.showToastShort(str("morphe_oauth2_toast_invalid"));
            }

            BaseSettings.OAUTH2_REFRESH_TOKEN.resetToDefault();
            OAuth2Requester.clearAll();
            authorization = "";

            Utils.showToastShort(str("morphe_oauth2_toast_reset"));
        }, 100L);
    }

    public static String getAuthorization() {
        return authorization;
    }

    public static void setAuthorization(@NonNull AccessTokenData accessTokenData) {
        StringBuilder sb = new StringBuilder();
        // 'Bearer'
        sb.append(accessTokenData.tokenType);
        sb.append(" ");
        // 'y29.xxx...'
        sb.append(accessTokenData.accessToken);

        // Bearer y29.xxx...
        authorization = sb.toString();
    }

    /**
     * Check the validity of the access token before the video starts
     */
    public static void updateAccessToken() {
        String refreshToken = BaseSettings.OAUTH2_REFRESH_TOKEN.get();

        // Refresh token is empty, the user has not signed in to VR
        if (refreshToken.isEmpty()) {
            return;
        }

        // Access token has not expired, do nothing
        if (OAuth2Requester.isAccessTokenDataAvailable()) {
            return;
        }

        // Access token has expired, so reissue it
        Utils.runOnBackgroundThread(() -> {
            AccessTokenData accessTokenData = OAuth2Requester.getAccessTokenData(refreshToken);

            if (accessTokenData == null) {
                Logger.printException(() -> "Failed to update access token");
            } else {
                setAuthorization(accessTokenData);
            }
        });
    }

    /**
     * Revoke token using OAuth2 API
     */
    public static void revokeToken() {
        String refreshToken = BaseSettings.OAUTH2_REFRESH_TOKEN.get();

        // Refresh token is empty, the user has not signed in to VR
        if (refreshToken.isEmpty()) {
            clearAll(true);
        } else {
            Utils.runOnBackgroundThread(() -> {
                boolean success = OAuth2Requester.revokeRefreshToken(refreshToken);
                if (success) {
                    clearAll(true);
                } else {
                    Logger.printException(() -> "Failed to reset refresh token");
                }
            });
        }
    }
}
