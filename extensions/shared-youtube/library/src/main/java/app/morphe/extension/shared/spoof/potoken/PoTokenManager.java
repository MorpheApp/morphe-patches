/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2533
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.potoken;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.requests.VisitorIdRequester;

public final class PoTokenManager {
    private static volatile PoTokenResult poTokenResult;

    private PoTokenManager() {
    }

    public static void reset() {
        poTokenResult = null;
    }

    public static PoTokenResult getAndUpdatePoTokenIfNeeded(ClientType clientType, String videoId) {
        if (poTokenResult != null && !poTokenResult.isExpired()) {
            return poTokenResult;
        }

        String visitorId = VisitorIdRequester.getVisitorId(clientType);

        try {
            PoTokenGenerator poTokenGenerator = new PoTokenGenerator();
            return poTokenResult = poTokenGenerator.getWebClientPoToken(videoId, visitorId);
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to generate PoToken", ex);
        }

        return null;
    }

    public static String getPlayerPoToken(ClientType clientType, String videoId) {
        PoTokenResult result = getAndUpdatePoTokenIfNeeded(clientType, videoId);
        if (result != null) {
            return result.playerRequestPoToken;
        }
        return null;
    }

    public static String getStreamingPoToken(ClientType clientType, String videoId) {
        PoTokenResult result = getAndUpdatePoTokenIfNeeded(clientType, videoId);
        if (result != null) {
            return result.streamingDataPoToken;
        }
        return null;
    }
}