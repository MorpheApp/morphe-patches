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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    public static String pageId = "";
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

    public static Map<String, String> parseCookies(String cookie) {
        Map<String, String> cookies = new HashMap<>();
        String[] cookiePairs = cookie.split("; ");
        for (String pair : cookiePairs) {
            String[] keyValue = pair.split("=", 2);
            if (keyValue.length == 2) {
                cookies.put(keyValue[0], keyValue[1]);
            }
        }
        return cookies;
    }

    public static Map<String, String> parseCookieString(String cookies) {
        Map<String, String> cookieList = new HashMap<>();
        Pattern cookiePattern = Pattern.compile("([^=]+)=([^;]*);?\\s?");
        Matcher matcher = cookiePattern.matcher(cookies);
        while (matcher.find()) {
            String cookieKey = matcher.group(1);
            String cookieValue = matcher.group(2);
            cookieList.put(cookieKey, cookieValue);
        }
        return cookieList;
    }

    public static String sha1(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (Exception ex) {
            Logger.printException(() -> "sha1 failed", ex);
        }
        return "";
    }

    public static String getAuthorizationHeader(Map<String, String> cookies) {
        String ytURL = "https://www.youtube.com";
        try {
            String sapisid = cookies.get("SAPISID");

            if (sapisid == null) {
                sapisid = cookies.get("__Secure-3PAPISID");
                if (sapisid == null) {
                    return "";
                }
            }

            long currentTimestamp = System.currentTimeMillis() / 1000;
            String initialData = currentTimestamp + " " + sapisid + " " + ytURL;
            String hash = sha1(initialData);

            return "SAPISIDHASH " + currentTimestamp + "_" + hash;
        } catch (Exception ex) {
            Logger.printException(() -> "getAuthorizationHeader failed", ex);
        }
        return "";
    }
}
