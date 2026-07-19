/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.shared.innertube.utils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.apache.commons.collections4.MapUtils;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class AuthUtils {
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String PAGE_ID_HEADER = "X-Goog-PageId";
    private static final String VISITOR_ID_HEADER = "X-Goog-Visitor-Id";
    @NonNull
    private static String authorization = "";
    @NonNull
    private static String pageId = "";
    @NonNull
    private static String visitorId = "";
    private static boolean incognitoStatus = false;

    /**
     * Injection point.
     */
    public static void setRequestHeaders(String url, Map<String, String> requestHeaders) {
        if (!MapUtils.isEmpty(requestHeaders)) {
            String newlyLoadedAuthorization = requestHeaders.get(AUTHORIZATION_HEADER);
            String newlyLoadedVisitorId = requestHeaders.get(VISITOR_ID_HEADER);

            if (Utils.isNotEmpty(newlyLoadedAuthorization) &&
                    Utils.isNotEmpty(newlyLoadedVisitorId)) {
                authorization = newlyLoadedAuthorization;
                visitorId = newlyLoadedVisitorId;
            }
        }
    }

    /**
     * Injection point.
     */
    public static void setPageId(@Nullable String newlyPageIDHeaderValue) {
        if (newlyPageIDHeaderValue != null && !pageId.equals(newlyPageIDHeaderValue)) {
            pageId = newlyPageIDHeaderValue;

            Logger.printDebug(() -> "new PageID Header value loaded: " + newlyPageIDHeaderValue);
        }
    }

    /**
     * Injection point.
     */
    public static void setIncognitoStatus(boolean newlyLoadedIncognitoStatus) {
        incognitoStatus = newlyLoadedIncognitoStatus;
    }

    public static Map<String, String> getRequestHeader() {
        return Map.of(
            AUTHORIZATION_HEADER, authorization,
            VISITOR_ID_HEADER, visitorId,
            PAGE_ID_HEADER, pageId
        );
    }

    public static boolean isNotLoggedIn() {
        return authorization.isEmpty() || (pageId.isEmpty() && incognitoStatus);
    }
}
