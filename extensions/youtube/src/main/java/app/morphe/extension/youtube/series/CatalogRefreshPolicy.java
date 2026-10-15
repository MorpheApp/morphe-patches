/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

/** Foreground-only freshness policy. Failed requests never loop on UI updates. */
final class CatalogRefreshPolicy {
    static final long STALE_MS = 6 * 60 * 60 * 1000L;
    static final long RETRY_MS = 30 * 60 * 1000L;

    static boolean due(long fetchedAt, long attemptedAt, long retryAt, long now) {
        return now >= retryAt
                && (attemptedAt == 0 || now - attemptedAt >= RETRY_MS)
                && (fetchedAt == 0 || now < fetchedAt || now - fetchedAt >= STALE_MS);
    }

    private CatalogRefreshPolicy() {}
}
