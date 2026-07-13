/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.requests.proxy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.function.Consumer;

/**
 * HTTP proxy connection that selects a direct connection if the initial proxy connection fails.
 * Request configuration is mirrored before either connection starts, but proxy credentials remain
 * exclusive to the proxy connection.
 */
public final class ProxyFallbackHttpURLConnection extends FallbackHttpURLConnection {
    private boolean connectionSelected;

    public ProxyFallbackHttpURLConnection(
            HttpURLConnection proxyConnection,
            HttpURLConnection directConnection,
            Consumer<IOException> fallbackCallback
    ) {
        super(
                proxyConnection,
                directConnection,
                IOException.class::isInstance,
                exception -> fallbackCallback.accept((IOException) exception)
        );
    }

    private synchronized void selectConnection() throws IOException {
        if (connectionSelected) {
            return;
        }

        executeWithFallback(connection -> {
            connection.connect();
            return null;
        });
        connectionSelected = true;
    }

    @Override
    public void connect() throws IOException {
        selectConnection();
    }

    @Override
    protected void beforeResponse() throws IOException {
        selectConnection();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        selectConnection();
        return super.getOutputStream();
    }
}
