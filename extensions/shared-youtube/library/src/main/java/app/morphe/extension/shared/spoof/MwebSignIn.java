/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4764
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import static app.morphe.extension.shared.StringRef.str;

import android.net.Uri;
import android.webkit.CookieManager;
import android.webkit.WebStorage;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;

/**
 * Sign in of the Mobile Web client.
 * <p>
 * Web clients accept only the cookies of a web sign in, and not the access token of the app.
 * The user signs in to m.youtube.com in a WebView, and the cookies stay only in the cookie store
 * of the WebView. They are never saved in the settings, so they are never exported,
 * and the cookies, the authorization made from them and the DataSyncId are never logged.
 */
public final class MwebSignIn {
    public static final String ORIGIN = "https://m.youtube.com";

    /**
     * Signs in to YouTube, and then goes to m.youtube.com.
     */
    public static final String SIGN_IN_URL = "https://accounts.google.com/ServiceLogin"
            + "?service=youtube&uilel=3&passive=true"
            + "&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26next%3D%252F";

    private static final String SIGN_OUT_URL = "https://www.youtube.com/logout";

    /**
     * Sites of the sign in, whose cookies are removed when signing out.
     */
    private static final String[] SIGN_IN_URLS = {
            "https://m.youtube.com",
            "https://www.youtube.com",
            "https://accounts.youtube.com",
            "https://accounts.google.com",
            "https://www.google.com",
    };

    /**
     * Cookie whose hash is the authorization of the web clients.
     */
    private static final String SAPISID_COOKIE = "SAPISID";

    /**
     * Each authorization scheme of the website, and the cookie it hashes.
     */
    private static final String[][] AUTHORIZATION_SCHEMES = {
            {"SAPISIDHASH", SAPISID_COOKIE},
            {"SAPISID1PHASH", "__Secure-1PAPISID"},
            {"SAPISID3PHASH", "__Secure-3PAPISID"},
    };

    /**
     * DELEGATED_SESSION_ID||USER_SESSION_ID for a brand account, or USER_SESSION_ID|| for the main account.
     */
    private static final Pattern DATA_SYNC_ID_PATTERN = Pattern.compile("\"DATASYNC_ID\":\"([^\"]+)");
    /**
     * The config of a page of a signed out user.
     */
    private static final Pattern LOGGED_OUT_PATTERN = Pattern.compile("\"LOGGED_IN\":false");
    private static final Pattern VISITOR_DATA_PATTERN = Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)");
    private static final String DATA_SYNC_ID_SEPARATOR = "||";

    private static final int HTTP_TIMEOUT_MILLISECONDS = 10 * 1000;

    /**
     * Held while the DataSyncId is fetched, so it is fetched once at a time.
     * The state below has its own lock, so the main thread never waits for the fetch.
     */
    private static final Object fetchLock = new Object();

    /**
     * Id of the signed in account, with the session of the user that the authorization names.
     */
    @Nullable
    @GuardedBy("MwebSignIn.class")
    private static String dataSyncId;

    /**
     * Visitor of the website session, which is fetched with {@link #dataSyncId}.
     */
    @Nullable
    @GuardedBy("MwebSignIn.class")
    private static String visitorData;

    /**
     * The SAPISID cookie that {@link #dataSyncId} was fetched with.
     */
    @Nullable
    @GuardedBy("MwebSignIn.class")
    private static String dataSyncIdSapisid;

    private MwebSignIn() {
    }

    /**
     * A request of the website, with the cookies of the session and the browser of the web client.
     */
    private static HttpURLConnection openWebConnection(String url, String cookies) throws IOException {
        HttpURLConnection connection = Requester.openConnection(url);
        connection.setConnectTimeout(HTTP_TIMEOUT_MILLISECONDS);
        connection.setReadTimeout(HTTP_TIMEOUT_MILLISECONDS);
        connection.setRequestProperty("User-Agent", ClientType.MWEB.getUserAgent());
        connection.setRequestProperty("Cookie", cookies);
        connection.setUseCaches(false);
        return connection;
    }

    /**
     * @return The cookies of the website, or null if there are none.
     */
    @Nullable
    private static String getCookies() {
        try {
            return CookieManager.getInstance().getCookie(ORIGIN);
        } catch (Exception ex) {
            // WebView is missing or broken.
            Logger.printInfo(() -> "Could not read the cookies", ex);
            return null;
        }
    }

    @Nullable
    private static String getCookieValue(@Nullable String cookies, String name) {
        if (cookies == null) {
            return null;
        }
        for (String cookie : cookies.split(";")) {
            String trimmed = cookie.trim();
            if (trimmed.startsWith(name + "=")) {
                String value = trimmed.substring(name.length() + 1);
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    public static boolean isSignedIn() {
        return getCookieValue(getCookies(), SAPISID_COOKIE) != null;
    }

    /**
     * The headers of an InnerTube request of the website host with the web sign in, as the website makes it.
     * Blocking call, and must be made off the main thread.
     *
     * @return The headers, or null if signed out or the account could not be fetched.
     */
    @Nullable
    public static Map<String, String> getRequestHeaders() {
        Utils.verifyOffMainThread();

        String cookies = getCookies();
        String id = getDataSyncId(cookies);
        if (id == null) {
            return null;
        }

        // The id is the session of the user for the main account,
        // or the session of the brand account and then the session of the user.
        final int separator = id.indexOf(DATA_SYNC_ID_SEPARATOR);
        String userSessionId = id;
        String delegatedSessionId = null;
        if (separator >= 0) {
            String afterSeparator = id.substring(separator + DATA_SYNC_ID_SEPARATOR.length());
            if (afterSeparator.isEmpty()) {
                userSessionId = id.substring(0, separator);
            } else {
                userSessionId = afterSeparator;
                delegatedSessionId = id.substring(0, separator);
            }
        }

        String authorization = getAuthorization(cookies, userSessionId);
        if (authorization == null) {
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Cookie", cookies);
        headers.put("Authorization", authorization);
        // The authorization is made for this origin.
        headers.put("Origin", ORIGIN);
        headers.put("X-Origin", ORIGIN);
        headers.put("X-Goog-AuthUser", "0");
        headers.put("X-Youtube-Bootstrap-Logged-In", "true");
        if (delegatedSessionId != null) {
            // A brand account.
            headers.put("X-Goog-PageId", delegatedSessionId);
        }
        return headers;
    }

    /**
     * The authorization of a web request, as the website makes it:
     * a hash of each SAPISID cookie with the origin and the session of the account.
     *
     * @return The authorization, or null if signed out.
     */
    @Nullable
    private static String getAuthorization(String cookies, String userSessionId) {
        final long seconds = System.currentTimeMillis() / 1000;
        StringBuilder authorization = new StringBuilder();
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            for (String[] scheme : AUTHORIZATION_SCHEMES) {
                String sid = getCookieValue(cookies, scheme[1]);
                if (sid == null) {
                    continue;
                }
                String hashInput = userSessionId + " " + seconds + " " + sid + " " + ORIGIN;
                byte[] digest = sha1.digest(hashInput.getBytes(StandardCharsets.UTF_8));
                if (authorization.length() > 0) {
                    authorization.append(' ');
                }
                authorization.append(scheme[0]).append(' ').append(seconds).append('_');
                for (byte b : digest) {
                    authorization.append(Character.forDigit((b >> 4) & 0xF, 16))
                            .append(Character.forDigit(b & 0xF, 16));
                }
                authorization.append("_u");
            }
        } catch (Exception ex) {
            Logger.printException(() -> "getAuthorization failure", ex);
            return null;
        }
        return authorization.length() == 0 ? null : authorization.toString();
    }

    /**
     * The id of the signed in account, with the session of the user that the authorization names.
     *
     * @return The id, or null if signed out or it could not be fetched.
     */
    @Nullable
    private static String getDataSyncId(@Nullable String cookies) {
        String sapisid = getCookieValue(cookies, SAPISID_COOKIE);
        if (sapisid == null) {
            return null;
        }

        synchronized (fetchLock) {
            synchronized (MwebSignIn.class) {
                if (dataSyncId != null && sapisid.equals(dataSyncIdSapisid)) {
                    return dataSyncId;
                }
            }

            HttpURLConnection connection = null;
            try {
                connection = openWebConnection(ORIGIN + "/", cookies);

                final int responseCode = connection.getResponseCode();
                if (responseCode != HttpURLConnection.HTTP_OK) {
                    Logger.printDebug(() -> "Could not fetch the DataSyncId: " + responseCode);
                    return null;
                }

                String page = Requester.parseString(connection);
                Matcher matcher = DATA_SYNC_ID_PATTERN.matcher(page);
                if (!matcher.find()) {
                    // The page is of a signed out user, so the sign in is no longer valid.
                    // Any other page, as a page about unusual traffic, keeps the sign in.
                    final boolean signedOutPage = LOGGED_OUT_PATTERN.matcher(page).find();
                    Logger.printDebug(() -> "No DataSyncId in the page, signed out page: " + signedOutPage);
                    if (signedOutPage) {
                        onSignInExpired();
                    }
                    return null;
                }

                saveResponseCookies(connection);
                Matcher visitorDataMatcher = VISITOR_DATA_PATTERN.matcher(page);
                String id = matcher.group(1);
                synchronized (MwebSignIn.class) {
                    dataSyncId = id;
                    dataSyncIdSapisid = sapisid;
                    visitorData = visitorDataMatcher.find() ? visitorDataMatcher.group(1) : null;
                }
                Logger.printDebug(() -> "DataSyncId fetched");
                return id;
            } catch (IOException ex) {
                Logger.printInfo(() -> "Could not fetch the DataSyncId", ex);
            } catch (Exception ex) {
                Logger.printException(() -> "getDataSyncId failure", ex);
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
            return null;
        }
    }

    /**
     * @return The visitor of the website session, or null if it was not fetched with the sign in.
     */
    @Nullable
    public static synchronized String getVisitorData() {
        return visitorData;
    }

    /**
     * Saves the cookies a response of the website sets, as a browser does.
     * The website renews some cookies of the session with its responses.
     */
    public static void saveResponseCookies(HttpURLConnection connection) {
        try {
            List<String> cookies = connection.getHeaderFields().get("Set-Cookie");
            if (cookies == null || cookies.isEmpty()) {
                return;
            }
            String url = connection.getURL().toString();
            CookieManager cookieManager = CookieManager.getInstance();
            for (String cookie : cookies) {
                cookieManager.setCookie(url, cookie);
            }
            cookieManager.flush();
        } catch (Exception ex) {
            Logger.printInfo(() -> "Could not save the cookies", ex);
        }
    }

    private static synchronized void clearAccount() {
        dataSyncId = null;
        dataSyncIdSapisid = null;
        visitorData = null;
    }

    /**
     * Called when the user has signed in. The streams fetched signed out, and the open pages, are not used again.
     */
    public static void onSignedIn() {
        clearAccount();
        MwebSiteSession.reset();
    }

    /**
     * Called when a request with the web sign in is refused.
     * The sign in is checked again with the next fetch, and it ends only if the website has signed out the account.
     */
    public static void onSignInRefused() {
        Logger.printDebug(() -> "Mobile Web sign in refused, it is checked again with the next fetch");
        synchronized (MwebSignIn.class) {
            dataSyncId = null;
            dataSyncIdSapisid = null;
        }
    }

    private static void onSignInExpired() {
        Logger.printDebug(() -> "Mobile Web sign in is no longer valid");
        clearAll();
        Utils.showToastLong(str("morphe_spoof_video_streams_sign_in_mweb_toast_expired"));
    }

    /**
     * Signs out on the server, then removes the cookies and everything made from them.
     * Main thread only.
     */
    public static void signOut() {
        String cookies = getCookies();
        if (cookies != null) {
            Utils.runOnBackgroundThread(() -> {
                HttpURLConnection connection = null;
                try {
                    connection = openWebConnection(SIGN_OUT_URL, cookies);
                    final int responseCode = connection.getResponseCode();
                    Logger.printDebug(() -> "Mobile Web sign out response: " + responseCode);
                } catch (Exception ex) {
                    Logger.printInfo(() -> "Could not sign out on the server", ex);
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            });
        }
        clearAll();
        Utils.showToastShort(str("morphe_spoof_video_streams_sign_in_mweb_toast_signed_out"));
    }

    private static void clearAll() {
        clearAccount();
        MwebSiteSession.reset();
        Utils.runOnMainThreadNowOrLater(() -> {
            try {
                CookieManager cookieManager = CookieManager.getInstance();
                WebStorage webStorage = WebStorage.getInstance();
                for (String url : SIGN_IN_URLS) {
                    removeCookies(cookieManager, url);
                    webStorage.deleteOrigin(url);
                }
                cookieManager.flush();
            } catch (Exception ex) {
                Logger.printInfo(() -> "Could not remove the cookies", ex);
            }
        });
    }

    /**
     * Removes the cookies sent to a url, without the cookies of other sites the app may have.
     * Cookies cannot be removed by domain, so each one is replaced with an expired cookie.
     */
    private static void removeCookies(CookieManager cookieManager, String url) {
        String cookies = cookieManager.getCookie(url);
        if (cookies == null) {
            return;
        }
        String host = Uri.parse(url).getHost();
        for (String cookie : cookies.split(";")) {
            final int separator = cookie.indexOf('=');
            String name = (separator < 0 ? cookie : cookie.substring(0, separator)).trim();
            if (name.isEmpty()) {
                continue;
            }
            String expired = name + "=; Max-Age=0; Path=/; Secure";
            // A cookie of the host and a cookie of its parent domains can have the same name.
            cookieManager.setCookie(url, expired);
            if (host != null && !name.startsWith("__Host-")) {
                for (int dot = host.indexOf('.'); dot >= 0; dot = host.indexOf('.', dot + 1)) {
                    String domain = host.substring(dot);
                    if (domain.indexOf('.', 1) < 0) {
                        // Not a top level domain.
                        break;
                    }
                    cookieManager.setCookie(url, expired + "; Domain=" + domain);
                }
                cookieManager.setCookie(url, expired + "; Domain=" + host);
            }
        }
    }
}
