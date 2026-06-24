/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches;

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

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

@SuppressWarnings("unused")
public final class ProxyPatch {
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

    private ProxyPatch() {
    }

    /**
     * Injection point.
     */
    public static void applyProxyOptions(CronetEngine.Builder builder) {
        if (!Settings.PROXY_ENABLED.get()) {
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

            int scheme = config.httpsProxy
                    ? Proxy.SCHEME_HTTPS
                    : Proxy.SCHEME_HTTP;
            ArrayList<Proxy> proxies = new ArrayList<>(config.allowDirectFallback ? 2 : 1);
            proxies.add(Proxy.createHttpProxy(scheme, config.host, config.port, DIRECT_EXECUTOR, CONNECT_CALLBACK));

            builder.setProxyOptions(createProxyOptions(proxies, config.allowDirectFallback));
            Requester.setConnectionProvider(ProxyPatch::openUrlConnection);
        } catch (Throwable ex) {
            Requester.setConnectionProvider(null);
            Logger.printException(() -> "applyProxyOptions failure", ex);
        }
    }

    private static ProxyOptions createProxyOptions(ArrayList<Proxy> proxies, boolean allowDirectFallback)
            throws ReflectiveOperationException {
        try {
            return (ProxyOptions) ProxyOptions.class
                    .getMethod("fromProxyList", List.class, Integer.TYPE)
                    .invoke(
                            null,
                            proxies,
                            allowDirectFallback
                                    ? ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT
                                    : ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT
                    );
        } catch (NoSuchMethodException ex) {
            if (allowDirectFallback) {
                // Legacy Cronet proxy APIs use a null proxy as the direct fallback sentinel.
                proxies.add(null);
            }

            return (ProxyOptions) ProxyOptions.class
                    .getMethod("fromProxyList", List.class)
                    .invoke(null, proxies);
        }
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
                Settings.PROXY_HOST.get().trim(),
                Settings.PROXY_PORT.get(),
                Settings.PROXY_HTTPS.get(),
                Settings.PROXY_ALLOW_DIRECT_FALLBACK.get()
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
        if (!Settings.PROXY_AUTH_ENABLED.get()) {
            return null;
        }

        String username = Settings.PROXY_AUTH_USERNAME.get();
        String password = Settings.PROXY_AUTH_PASSWORD.get();

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

    private static final class ProxyConfig {
        private final String host;
        private final int port;
        private final boolean httpsProxy;
        private final boolean allowDirectFallback;

        ProxyConfig(String host, int port, boolean httpsProxy, boolean allowDirectFallback) {
            this.host = host;
            this.port = port;
            this.httpsProxy = httpsProxy;
            this.allowDirectFallback = allowDirectFallback;
        }

        boolean isValid() {
            return !host.isEmpty() && port >= 1 && port <= 65535;
        }
    }
}
