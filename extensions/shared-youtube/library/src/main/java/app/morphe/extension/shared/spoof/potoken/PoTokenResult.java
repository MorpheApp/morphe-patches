/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof.potoken;

public class PoTokenResult {
    private final String playerRequestPoToken;
    private final String streamingDataPoToken;
    private final long expirationMs;

    public PoTokenResult(String playerRequestPoToken,
                         String streamingDataPoToken,
                         long expirationMs) {
        this.playerRequestPoToken = playerRequestPoToken;
        this.streamingDataPoToken = streamingDataPoToken;
        this.expirationMs = expirationMs;
    }

    public String getPlayerRequestPoToken() {
        return playerRequestPoToken;
    }

    public String getStreamingDataPoToken() {
        return streamingDataPoToken;
    }

    public boolean isExpired() {
        return System.currentTimeMillis() > expirationMs;
    }
}