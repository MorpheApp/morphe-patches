/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1823
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_ALLOW_DIRECT_FALLBACK;
import static app.morphe.extension.shared.settings.SharedYouTubeSettings.PROXY_AUTH_ENABLED;
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
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.requests.proxy.CronetFallbackHttpURLConnection;
import app.morphe.extension.shared.requests.proxy.CronetHttpURLConnection;
import app.morphe.extension.shared.requests.proxy.ProxyFallbackHttpURLConnection;

@SuppressWarnings("unused")
public final class NetworkProxyPatch {
    private static final int ALL_PROXIES_FAILED_BEHAVIOR_DISALLOW_DIRECT = 0;
    private static final int ALL_PROXIES_FAILED_BEHAVIOR_ALLOW_DIRECT = 1;
    private static final String PROXY_AUTHORIZATION_HEADER = "Proxy-Authorization";
    private static final String BASIC_AUTHORIZATION_PREFIX = "Basic ";
    private static final String JAVA_CRONET_ENGINE_VERSION_PREFIX = "CronetHttpURLConnection/";
    private static final Executor DIRECT_EXECUTOR = Runnable::run;
    private static final AtomicReference<CronetEngine> REQUESTER_CRONET_ENGINE =
            new AtomicReference<>();

    private static final ThreadLocal<Boolean> PROXY_OPTIONS_APPLIED_ON_CURRENT_THREAD =
            new ThreadLocal<>();

    /**
     * Last engine successfully built with proxy options on this thread. Provider selection may
     * keep an earlier successful engine when a later provider fails, so only a newer successful
     * build may replace this candidate. {@link #setMainCronetEngine(CronetEngine)} consumes it.
     */
    private static final ThreadLocal<WeakReference<CronetEngine>>
            LAST_PROXY_CONFIGURED_ENGINE_ON_CURRENT_THREAD = new ThreadLocal<>();

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
        PROXY_OPTIONS_APPLIED_ON_CURRENT_THREAD.remove();

        if (!PROXY_ENABLED.get()) {
            clearProxyState();
            return;
        }

        try {
            ProxyConfig config = getProxyConfig();

            if (!config.isValid()) {
                Logger.printException(() -> "Ignoring invalid proxy settings: " + config.host + ":" + config.port);
                clearProxyState();
                return;
            }

            final int scheme = config.httpsProxy
                    ? Proxy.SCHEME_HTTPS
                    : Proxy.SCHEME_HTTP;
            ArrayList<Proxy> proxies = new ArrayList<>(config.allowDirectFallback ? 2 : 1);
            proxies.add(Proxy.createHttpProxy(scheme, config.host, config.port, DIRECT_EXECUTOR, CONNECT_CALLBACK));

            builder.setProxyOptions(createProxyOptions(proxies, config.allowDirectFallback));
            PROXY_OPTIONS_APPLIED_ON_CURRENT_THREAD.set(true);
            Requester.setConnectionProvider(NetworkProxyPatch::openConnection);
        } catch (Throwable ex) {
            Logger.printException(() -> "applyProxyOptions failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void recordProxyConfiguredCronetEngine(CronetEngine engine) {
        boolean proxyOptionsApplied =
                Boolean.TRUE.equals(PROXY_OPTIONS_APPLIED_ON_CURRENT_THREAD.get());
        PROXY_OPTIONS_APPLIED_ON_CURRENT_THREAD.remove();

        if (!proxyOptionsApplied || engine == null) {
            return;
        }

        try {
            String version = engine.getVersionString();
            if (version != null && version.startsWith(JAVA_CRONET_ENGINE_VERSION_PREFIX)) {
                // This fallback delegates openConnection to URL.openConnection and ignores the
                // ProxyOptions configured on the Cronet builder.
                Logger.printInfo(() -> "Ignoring Java fallback Cronet engine for extension requests");
                return;
            }

            // Do not retain every engine built by optional features. The application-wide engine
            // factory runs synchronously on this thread and publishes only its own return value.
            LAST_PROXY_CONFIGURED_ENGINE_ON_CURRENT_THREAD.set(new WeakReference<>(engine));
        } catch (Throwable ex) {
            Logger.printException(() -> "recordProxyConfiguredCronetEngine failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setMainCronetEngine(CronetEngine engine) {
        PROXY_OPTIONS_APPLIED_ON_CURRENT_THREAD.remove();
        WeakReference<CronetEngine> candidateReference =
                LAST_PROXY_CONFIGURED_ENGINE_ON_CURRENT_THREAD.get();
        LAST_PROXY_CONFIGURED_ENGINE_ON_CURRENT_THREAD.remove();

        if (engine == null
                || candidateReference == null
                || candidateReference.get() != engine) {
            return;
        }

        try {
            if (!PROXY_ENABLED.get() || !getProxyConfig().isValid()) {
                clearProxyState();
                return;
            }

            REQUESTER_CRONET_ENGINE.set(engine);
            Requester.setConnectionProvider(NetworkProxyPatch::openConnection);
        } catch (Throwable ex) {
            Logger.printException(() -> "setMainCronetEngine failure", ex);
        }
    }

    private static void clearProxyState() {
        PROXY_OPTIONS_APPLIED_ON_CURRENT_THREAD.remove();
        LAST_PROXY_CONFIGURED_ENGINE_ON_CURRENT_THREAD.remove();
        REQUESTER_CRONET_ENGINE.set(null);
        Requester.setConnectionProvider(null);
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

    private static HttpURLConnection openConnection(URL url) throws IOException {
        ProxyConfig config = getProxyConfig();
        if (!PROXY_ENABLED.get() || !config.isValid()) {
            return openDirectConnection(url);
        }

        String protocol = url.getProtocol();
        boolean httpTarget = "http".equalsIgnoreCase(protocol);
        boolean httpsTarget = "https".equalsIgnoreCase(protocol);
        if (!httpTarget && !httpsTarget) {
            return openDirectConnection(url);
        }

        boolean proxyAuthenticationEnabled = PROXY_AUTH_ENABLED.get();

        // An HTTPS proxy cannot be represented by java.net.Proxy. Cronet is also required for
        // authenticated CONNECT requests and for applying the configured direct fallback.
        // Authenticated plain HTTP requests stay on HttpURLConnection because they do not use
        // CONNECT and therefore need an explicit Proxy-Authorization header.
        if (config.httpsProxy
                || (httpsTarget && proxyAuthenticationEnabled)
                || (config.allowDirectFallback && !proxyAuthenticationEnabled)) {
            return openCronetConnection(url, config);
        }

        return openHttpProxyConnection(url, config);
    }

    private static HttpURLConnection openCronetConnection(URL url, ProxyConfig config)
            throws IOException {
        CronetEngine engine = REQUESTER_CRONET_ENGINE.get();
        if (engine != null) {
            try {
                HttpURLConnection connection = new CronetHttpURLConnection(
                        (HttpURLConnection) engine.openConnection(url),
                        ex -> discardCronetEngine(engine, ex)
                );
                if (!config.allowDirectFallback) {
                    return connection;
                }

                return new CronetFallbackHttpURLConnection(
                        connection,
                        openDirectConnection(url),
                        ex -> Logger.printInfo(
                                () -> "Cronet engine is unavailable; using direct fallback",
                                ex
                        )
                );
            } catch (IllegalStateException ex) {
                discardCronetEngine(engine, ex);
            }
        }

        if (config.allowDirectFallback) {
            return openDirectConnection(url);
        }

        throw new IOException("Proxy-configured Cronet engine is not available");
    }

    private static void discardCronetEngine(CronetEngine engine, IllegalStateException ex) {
        if (REQUESTER_CRONET_ENGINE.compareAndSet(engine, null)) {
            Logger.printInfo(() -> "Proxy Cronet engine is no longer available", ex);
        }
    }

    private static HttpURLConnection openHttpProxyConnection(URL url, ProxyConfig config)
            throws IOException {
        if (config.httpsProxy) {
            throw new IOException("HTTPS proxy connections require Cronet");
        }

        HttpURLConnection connection = (HttpURLConnection) url.openConnection(new java.net.Proxy(
                java.net.Proxy.Type.HTTP,
                InetSocketAddress.createUnresolved(config.host, config.port)
        ));

        setProxyAuthorizationHeader(connection);

        if (!config.allowDirectFallback) {
            return connection;
        }

        return new ProxyFallbackHttpURLConnection(
                connection,
                openDirectConnection(url),
                ex -> Logger.printInfo(
                        () -> "HTTP proxy is unavailable; using direct fallback",
                        ex
                )
        );
    }

    private static HttpURLConnection openDirectConnection(URL url) throws IOException {
        return (HttpURLConnection) url.openConnection();
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
        if (!PROXY_AUTH_ENABLED.get()) {
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
