/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.originaltitles;

import java.net.HttpURLConnection;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.requests.Requester;

/**
 * Pauses the title requests to the server after a temporary error, such as when the server
 * limits the requests of a VPN, so the titles are fetched again later instead of being treated
 * as not available, without sending more requests while the server is limiting them.
 */
final class RequestBackoff {

    /**
     * Time the requests are paused after a temporary error, the same for every error.
     */
    private static final long PAUSE_MILLISECONDS = 30_000;

    /**
     * Maximum time the requests are paused if the server asks for a longer time.
     */
    private static final long MAX_PAUSE_MILLISECONDS = 10 * 60_000;

    /**
     * Time until no request is sent. Read without locking, as it's checked for each request.
     */
    private static volatile long pausedUntil;

    private RequestBackoff() {
    }

    /**
     * @return If the requests are paused because of a temporary error of a previous request.
     */
    static boolean isPaused() {
        return System.currentTimeMillis() < pausedUntil;
    }

    /**
     * @return Time until the requests are sent again, or 0 if not paused.
     */
    static long pauseRemainingMilliseconds() {
        return Math.max(0, pausedUntil - System.currentTimeMillis());
    }

    /**
     * @return If the response is a temporary error, such as too many requests or a server error.
     */
    static boolean isTemporaryError(int responseCode) {
        return responseCode == Requester.HTTP_STATUS_CODE_TOO_MANY_REQUESTS
                || (responseCode >= 500 && responseCode <= 599);
    }

    /**
     * Pauses the requests, or for the time asked by the server if it's longer.
     * Errors of the requests sent before the pause do not make the pause longer.
     */
    static synchronized void onTemporaryError(HttpURLConnection connection, int responseCode) {
        if (isPaused()) {
            return;
        }
        long retryAfter = 0;
        try {
            String retryAfterHeader = connection.getHeaderField("Retry-After");
            if (retryAfterHeader != null) {
                final long seconds = Long.parseLong(retryAfterHeader.trim());
                retryAfter = Math.min(seconds * 1000, MAX_PAUSE_MILLISECONDS);
            }
        } catch (NumberFormatException ignored) {
            // The time can also be a date, which is not used.
        }
        final long pause = Math.max(PAUSE_MILLISECONDS, retryAfter);
        pausedUntil = System.currentTimeMillis() + pause;
        Logger.printInfo(() -> "Title requests failed with code: " + responseCode
                + ", paused for: " + pause / 1000 + " seconds");
    }
}
