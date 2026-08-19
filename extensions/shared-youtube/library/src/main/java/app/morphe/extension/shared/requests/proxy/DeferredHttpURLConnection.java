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
import java.net.ProtocolException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Defers connection selection until request I/O and charges it to the connect timeout.
 */
public final class DeferredHttpURLConnection extends DelegatingHttpURLConnection {
    private final PendingHttpURLConnection pendingConnection;
    private final ConnectionFactory connectionFactory;

    public DeferredHttpURLConnection(URL url, ConnectionFactory connectionFactory) {
        this(new PendingHttpURLConnection(url), connectionFactory);
    }

    private DeferredHttpURLConnection(
            PendingHttpURLConnection pendingConnection,
            ConnectionFactory connectionFactory
    ) {
        super(pendingConnection);
        this.pendingConnection = pendingConnection;
        this.connectionFactory = connectionFactory;
    }

    private synchronized void selectConnection() throws IOException {
        if (delegate != pendingConnection) {
            return;
        }

        int connectTimeout = pendingConnection.getConnectTimeout();
        long startNanos = System.nanoTime();
        HttpURLConnection selectedConnection =
                connectionFactory.openConnection(connectTimeout);

        int remainingConnectTimeout = connectTimeout;
        if (connectTimeout > 0) {
            long elapsedMilliseconds = TimeUnit.NANOSECONDS.toMillis(
                    System.nanoTime() - startNanos
            );
            if (elapsedMilliseconds >= connectTimeout) {
                try {
                    selectedConnection.disconnect();
                } catch (RuntimeException ignored) {
                    // Preserve the timeout.
                }
                throw new SocketTimeoutException(
                        "Timed out while waiting for a network transport"
                );
            }
            remainingConnectTimeout = (int) Math.max(
                    1,
                    connectTimeout - elapsedMilliseconds
            );
        }

        pendingConnection.applyTo(selectedConnection, remainingConnectTimeout);
        delegate = selectedConnection;
    }

    @Override
    public void connect() throws IOException {
        selectConnection();
        super.connect();
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

    @FunctionalInterface
    public interface ConnectionFactory {
        HttpURLConnection openConnection(int connectTimeoutMilliseconds) throws IOException;
    }

    private static final class PendingHttpURLConnection extends HttpURLConnection {
        private PendingHttpURLConnection(URL url) {
            super(url);
        }

        private void applyTo(
                HttpURLConnection connection,
                int connectTimeoutMilliseconds
        ) throws ProtocolException {
            connection.setConnectTimeout(connectTimeoutMilliseconds);
            connection.setReadTimeout(getReadTimeout());
            connection.setRequestMethod(getRequestMethod());
            connection.setDoInput(getDoInput());
            connection.setDoOutput(getDoOutput());
            connection.setAllowUserInteraction(getAllowUserInteraction());
            connection.setUseCaches(getUseCaches());
            connection.setIfModifiedSince(getIfModifiedSince());
            connection.setDefaultUseCaches(getDefaultUseCaches());
            connection.setInstanceFollowRedirects(getInstanceFollowRedirects());

            if (fixedContentLengthLong >= 0) {
                connection.setFixedLengthStreamingMode(fixedContentLengthLong);
            } else if (fixedContentLength >= 0) {
                connection.setFixedLengthStreamingMode(fixedContentLength);
            } else if (chunkLength > 0) {
                connection.setChunkedStreamingMode(chunkLength);
            }

            for (Map.Entry<String, List<String>> entry : getRequestProperties().entrySet()) {
                List<String> values = entry.getValue();
                if (values.isEmpty()) {
                    continue;
                }

                connection.setRequestProperty(entry.getKey(), values.get(0));
                for (int i = 1; i < values.size(); i++) {
                    connection.addRequestProperty(entry.getKey(), values.get(i));
                }
            }
        }

        @Override
        public void connect() {
            throw new IllegalStateException("Pending connection cannot perform I/O");
        }

        @Override
        public void disconnect() {
        }

        @Override
        public boolean usingProxy() {
            return false;
        }
    }
}