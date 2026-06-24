/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_ALLOW_DIRECT_FALLBACK;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_AUTH_PASSWORD;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_AUTH_USERNAME;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_ENABLED;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_HOST;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_HTTPS;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_PORT;

import android.util.Base64;
import android.util.Pair;

import org.chromium.net.CronetEngine;
import org.chromium.net.Proxy;
import org.chromium.net.ProxyOptions;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public final class NetworkProxyPatch {
    private static final int ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT = 0;
    private static final int ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT = 1;
    private static final String PROXY_AUTHORIZATION_HEADER = "Proxy-Authorization";
    private static final String BASIC_AUTHORIZATION_PREFIX = "Basic ";
    private static final String HTTPS_PROXY_UNSUPPORTED_MESSAGE =
            "HttpURLConnection proxy requests only support HTTP proxies";
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final AtomicBoolean LOGGED_HTTPS_URL_CONNECTION_PROXY =
            new AtomicBoolean();

    private static final Proxy.HttpConnectCallback CONNECT_CALLBACK = new Proxy.HttpConnectCallback() {
        @Override
        public void onBeforeRequest(Proxy.HttpConnectCallback.Request request) {
            request.proceed(getProxyHeaders());
        }

        @Override
        public int onResponseReceived(List<?> responseHeaders, int statusCode) {
            return Proxy.HttpConnectCallback.RESPONSE_ACTION_PROCEED;
        }
    };

    private NetworkProxyPatch() {
    }

    /**
     * Injection point.
     */
    private static boolean useProxyListInt() {
        return false; // Modify during patching,
    }

    /**
     * Injection point.
     */
    public static void applyProxyOptions(CronetEngine.Builder builder) {
        if (!PROXY_ENABLED.get()) {
            Requester.setConnectionProvider(null);
            return;
        }

        try {
            ProxyConfig config = getProxyConfig();

            if (!config.isValid()) {
                Logger.printException(() -> "Ignoring invalid proxy settings: " + config.host + ":" + config.port);
                Requester.setConnectionProvider(null);
                return;
            }

            final int scheme = config.httpsProxy
                    ? Proxy.SCHEME_HTTPS
                    : Proxy.SCHEME_HTTP;
            ArrayList<Proxy> proxies = new ArrayList<>(config.allowDirectFallback ? 2 : 1);
            proxies.add(Proxy.createHttpProxy(scheme, config.host, config.port, DIRECT_EXECUTOR, CONNECT_CALLBACK));

            builder.setProxyOptions(createProxyOptions(proxies, config.allowDirectFallback));
            Requester.setConnectionProvider(NetworkProxyPatch::openUrlConnection);
        } catch (Throwable ex) {
            Requester.setConnectionProvider(null);
            Logger.printException(() -> "applyProxyOptions failure", ex);
        }
    }

    private static ProxyOptions createProxyOptions(ArrayList<Proxy> proxies, boolean allowDirectFallback) {
        if (useProxyListInt()) {
            return ProxyOptions.fromProxyList(
                    proxies,
                    allowDirectFallback
                            ? ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT
                            : ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT
            );
        }

        if (allowDirectFallback) {
            // Legacy Cronet proxy APIs use a null proxy as the direct fallback sentinel.
            proxies.add(null);
        }
        return ProxyOptions.fromProxyList(proxies);
    }

    private static HttpURLConnection openUrlConnection(URL url) throws IOException {
        ProxyConfig config = getProxyConfig();
        if (!config.isValid()) {
            return (HttpURLConnection) url.openConnection();
        }

        if (config.httpsProxy) {
            if (LOGGED_HTTPS_URL_CONNECTION_PROXY.compareAndSet(false, true)) {
                Logger.printInfo(() -> HTTPS_PROXY_UNSUPPORTED_MESSAGE);
            }
            if (config.allowDirectFallback) {
                return (HttpURLConnection) url.openConnection();
            }

            throw new IOException(HTTPS_PROXY_UNSUPPORTED_MESSAGE);
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection(new java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(config.host, config.port)
        ));

        setProxyAuthorizationHeader(connection);

        return connection;
    }

    private static ProxyConfig getProxyConfig() {
        return new ProxyConfig(
                PROXY_HOST.get().trim(),
                PROXY_PORT.get(),
                PROXY_HTTPS.get(),
                PROXY_ALLOW_DIRECT_FALLBACK.get()
        );
    }

    private static List<Pair<String, String>> getProxyHeaders() {
        String proxyAuthorization = getProxyAuthorizationHeader();
        if (proxyAuthorization == null) {
            return Collections.emptyList();
        }

        return Collections.singletonList(Pair.create(
                PROXY_AUTHORIZATION_HEADER,
                proxyAuthorization
        ));
    }

    private static void setProxyAuthorizationHeader(HttpURLConnection connection) {
        String proxyAuthorization = getProxyAuthorizationHeader();
        if (proxyAuthorization != null) {
            connection.setRequestProperty(PROXY_AUTHORIZATION_HEADER, proxyAuthorization);
        }
    }

    private static String getProxyAuthorizationHeader() {
        if (!SharedYouTubeSettings.PROXY_AUTH_ENABLED.get()) {
            return null;
        }

        String username = PROXY_AUTH_USERNAME.get();
        String password = PROXY_AUTH_PASSWORD.get();

        if (username.isEmpty() && password.isEmpty()) {
            Logger.printException(() -> "Proxy authentication is enabled but credentials are empty");
            return null;
        }

        String credentials = username + ":" + password;
        String encodedCredentials = Base64.encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8),
                Base64.NO_WRAP
        );

        return BASIC_AUTHORIZATION_PREFIX + encodedCredentials;
    }

    private record ProxyConfig(String host, int port, boolean httpsProxy,
                               boolean allowDirectFallback) {
        @SuppressWarnings("BooleanMethodIsAlwaysInverted")
            boolean isValid() {
                return !host.isEmpty() && port >= 1 && port <= 65535;
            }
        }
}
