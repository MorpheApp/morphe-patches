/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2510
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.requests.proxy;

import java.net.HttpURLConnection;

/**
 * Prevents a platform HTTP connection from following redirects into HTTPS.
 */
public final class PlaintextHttpURLConnection extends DelegatingHttpURLConnection {
    public PlaintextHttpURLConnection(HttpURLConnection delegate) {
        super(requireHttp(delegate));
        super.setInstanceFollowRedirects(false);
    }

    private static HttpURLConnection requireHttp(HttpURLConnection connection) {
        if (connection == null
                || !"http".equalsIgnoreCase(connection.getURL().getProtocol())) {
            throw new IllegalArgumentException(
                    "Platform connection is restricted to plaintext HTTP"
            );
        }
        return connection;
    }

    @Override
    public void setInstanceFollowRedirects(boolean followRedirects) {
        super.setInstanceFollowRedirects(false);
    }

    @Override
    public boolean getInstanceFollowRedirects() {
        return false;
    }
}