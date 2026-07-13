/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.requests.proxy;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Connection that can select a fallback before request I/O starts. Request configuration is
 * mirrored while either connection may still be selected.
 */
abstract class FallbackHttpURLConnection extends DelegatingHttpURLConnection {
    private static final String PROXY_AUTHORIZATION_HEADER = "Proxy-Authorization";

    private final HttpURLConnection primaryConnection;
    private final HttpURLConnection fallbackConnection;
    private final Predicate<Throwable> fallbackPredicate;
    private final Consumer<Throwable> fallbackCallback;
    private boolean fallbackAllowed = true;

    FallbackHttpURLConnection(
            HttpURLConnection primaryConnection,
            HttpURLConnection fallbackConnection,
            Predicate<Throwable> fallbackPredicate,
            Consumer<Throwable> fallbackCallback
    ) {
        super(primaryConnection);
        this.primaryConnection = primaryConnection;
        this.fallbackConnection = fallbackConnection;
        this.fallbackPredicate = fallbackPredicate;
        this.fallbackCallback = fallbackCallback;
    }

    protected final synchronized <T> T executeWithFallback(
            ConnectionOperation<T> operation
    ) throws IOException {
        HttpURLConnection selectedConnection = delegate;
        try {
            T result = operation.execute(selectedConnection);
            fallbackAllowed = false;
            return result;
        } catch (IOException | RuntimeException primaryException) {
            if (!fallbackAllowed
                    || selectedConnection != primaryConnection
                    || !fallbackPredicate.test(primaryException)) {
                throw primaryException;
            }

            try {
                primaryConnection.disconnect();
            } catch (RuntimeException ignored) {
                // Failure to clean up the primary connection must not prevent the fallback.
            }

            delegate = fallbackConnection;
            fallbackAllowed = false;
            fallbackCallback.accept(primaryException);

            try {
                return operation.execute(fallbackConnection);
            } catch (IOException | RuntimeException fallbackException) {
                fallbackException.addSuppressed(primaryException);
                throw fallbackException;
            }
        }
    }

    private boolean shouldMirrorToFallbackConnection() {
        return fallbackAllowed && delegate == primaryConnection;
    }

    private void mirrorToFallbackConnection(Consumer<HttpURLConnection> operation) {
        if (shouldMirrorToFallbackConnection()) {
            operation.accept(fallbackConnection);
        }
    }

    @Override
    public void setConnectTimeout(int timeout) {
        super.setConnectTimeout(timeout);
        mirrorToFallbackConnection(connection -> connection.setConnectTimeout(timeout));
    }

    @Override
    public void setReadTimeout(int timeout) {
        super.setReadTimeout(timeout);
        mirrorToFallbackConnection(connection -> connection.setReadTimeout(timeout));
    }

    @Override
    public void setDoInput(boolean doInput) {
        super.setDoInput(doInput);
        mirrorToFallbackConnection(connection -> connection.setDoInput(doInput));
    }

    @Override
    public void setDoOutput(boolean doOutput) {
        super.setDoOutput(doOutput);
        mirrorToFallbackConnection(connection -> connection.setDoOutput(doOutput));
    }

    @Override
    public void setAllowUserInteraction(boolean allowUserInteraction) {
        super.setAllowUserInteraction(allowUserInteraction);
        mirrorToFallbackConnection(connection ->
                connection.setAllowUserInteraction(allowUserInteraction));
    }

    @Override
    public void setUseCaches(boolean useCaches) {
        super.setUseCaches(useCaches);
        mirrorToFallbackConnection(connection -> connection.setUseCaches(useCaches));
    }

    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
        super.setIfModifiedSince(ifModifiedSince);
        mirrorToFallbackConnection(connection -> connection.setIfModifiedSince(ifModifiedSince));
    }

    @Override
    public void setDefaultUseCaches(boolean defaultUseCaches) {
        super.setDefaultUseCaches(defaultUseCaches);
        mirrorToFallbackConnection(connection ->
                connection.setDefaultUseCaches(defaultUseCaches));
    }

    @Override
    public void setRequestProperty(String key, String value) {
        super.setRequestProperty(key, value);
        if (!PROXY_AUTHORIZATION_HEADER.equalsIgnoreCase(key)) {
            mirrorToFallbackConnection(connection -> connection.setRequestProperty(key, value));
        }
    }

    @Override
    public void addRequestProperty(String key, String value) {
        super.addRequestProperty(key, value);
        if (!PROXY_AUTHORIZATION_HEADER.equalsIgnoreCase(key)) {
            mirrorToFallbackConnection(connection -> connection.addRequestProperty(key, value));
        }
    }

    @Override
    public void setFixedLengthStreamingMode(int contentLength) {
        super.setFixedLengthStreamingMode(contentLength);
        mirrorToFallbackConnection(connection ->
                connection.setFixedLengthStreamingMode(contentLength));
    }

    @Override
    public void setFixedLengthStreamingMode(long contentLength) {
        super.setFixedLengthStreamingMode(contentLength);
        mirrorToFallbackConnection(connection ->
                connection.setFixedLengthStreamingMode(contentLength));
    }

    @Override
    public void setChunkedStreamingMode(int chunkLength) {
        super.setChunkedStreamingMode(chunkLength);
        mirrorToFallbackConnection(connection ->
                connection.setChunkedStreamingMode(chunkLength));
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
        super.setInstanceFollowRedirects(followRedirects);
        mirrorToFallbackConnection(connection ->
                connection.setInstanceFollowRedirects(followRedirects));
    }

    @Override
    public void setRequestMethod(String method) throws ProtocolException {
        super.setRequestMethod(method);
        if (shouldMirrorToFallbackConnection()) {
            fallbackConnection.setRequestMethod(method);
        }
    }

    @FunctionalInterface
    protected interface ConnectionOperation<T> {
        T execute(HttpURLConnection connection) throws IOException;
    }
}
