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
 * Cronet connection that switches to direct if its engine becomes unavailable before request I/O
 * starts. A successful operation commits to its selected connection so requests are never replayed.
 */
public final class CronetFallbackHttpURLConnection extends FallbackHttpURLConnection {
    public CronetFallbackHttpURLConnection(
            HttpURLConnection cronetConnection,
            HttpURLConnection directConnection,
            Consumer<IllegalStateException> fallbackCallback
    ) {
        super(
                cronetConnection,
                directConnection,
                IllegalStateException.class::isInstance,
                exception -> fallbackCallback.accept((IllegalStateException) exception)
        );
    }

    @Override
    public void connect() throws IOException {
        executeWithFallback(connection -> {
            connection.connect();
            return null;
        });
    }

    @Override
    protected void beforeResponse() throws IOException {
        // Trigger Cronet's lazy request start while fallback is still safe. The subsequent response
        // accessor is served from the connection's cached response headers.
        executeWithFallback(HttpURLConnection::getResponseCode);
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return executeWithFallback(HttpURLConnection::getOutputStream);
    }
}
