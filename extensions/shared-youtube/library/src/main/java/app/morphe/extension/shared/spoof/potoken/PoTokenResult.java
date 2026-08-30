/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2533
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.potoken;

public class PoTokenResult {
    public final String playerRequestPoToken;
    public final String streamingDataPoToken;
    private final long expirationMs;

    public PoTokenResult(String playerRequestPoToken, String streamingDataPoToken, long expirationMs) {
        this.playerRequestPoToken = playerRequestPoToken;
        this.streamingDataPoToken = streamingDataPoToken;
        this.expirationMs = expirationMs;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() >= expirationMs;
    }
}