/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/1972
 * https://github.com/MorpheApp/morphe-patches/pull/2763
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils.requests;

import androidx.annotation.Nullable;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Fetches the AiSList blocklist and warnlist raw text files from GitHub and stores them
 * in non-exported StringSettings. Failure is silent (logged only) - the filter continues
 * to use the previous cache.
 */
public final class AiSListRequester {

    private static final String BLOCKLIST_URL =
            "https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/aislist_blocklist.txt";
    private static final String WARNLIST_URL =
            "https://raw.githubusercontent.com/Override92/AiSList/main/AiSList/aislist_warnlist.txt";

    /** Refresh the cached list from GitHub raw after this long since last successful fetch. */
    public static final long REFRESH_CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000L; // 4 hours.

    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int READ_TIMEOUT_MS = 30_000;
    private static final int MAX_RESPONSE_BYTES = 5 * 1024 * 1024; // 5 MB safety cap.

    private AiSListRequester() {}

    /**
     * Makes the lists available to a caller that does not run the filters, which are what
     * normally keep them fresh. Must be called off the main thread.
     *
     * <p>Waits for the fetch only when nothing is cached, because there is otherwise
     * something usable to answer with right away.
     */
    public static void fetchIfStale() {
        Utils.verifyOffMainThread();

        if (Settings.AISLIST_BLOCKLIST_CACHE.get().isEmpty()
                && Settings.AISLIST_WARNLIST_CACHE.get().isEmpty()) {
            fetchAndStore();
        } else if (System.currentTimeMillis() - Settings.AISLIST_LAST_FETCH_MS.get()
                > REFRESH_CHECK_INTERVAL_MS) {
            Utils.runOnBackgroundThread(AiSListRequester::fetchAndStore);
        }
    }

    /**
     * Fetches both lists sequentially. Must be called off the main thread.
     * Persists whichever list succeeds and only advances the last-fetch timestamp
     * when at least one succeeded.
     */
    public static void fetchAndStore() {
        Utils.verifyOffMainThread();

        String block = fetch(BLOCKLIST_URL, false);
        String warn = fetch(WARNLIST_URL, true);

        boolean any = false;
        if (block != null) {
            Settings.AISLIST_BLOCKLIST_CACHE.save(block);
            any = true;
        }
        if (warn != null) {
            Settings.AISLIST_WARNLIST_CACHE.save(warn);
            any = true;
        }
        if (any) {
            Settings.AISLIST_LAST_FETCH_MS.save(System.currentTimeMillis());
            Logger.printDebug(() -> "AiSList fetch stored" +
                    (block != null ? " blocklist=" + block.length() + "B" : "") +
                    (warn != null ? " warnlist=" + warn.length() + "B" : ""));
        } else {
            Logger.printDebug(() -> "AiSList fetch: both lists failed, keeping cached copy");
        }
    }

    @Nullable
    private static String fetch(String url, boolean disconnect) {
        HttpURLConnection connection = null;
        final long start = System.currentTimeMillis();
        try {
            connection = Requester.openConnection(url);
            connection.setFixedLengthStreamingMode(0);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setRequestProperty("Accept-Encoding", "gzip");
            connection.setRequestProperty("User-Agent", "Morphe-AiSList");

            final int code = connection.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                Logger.printDebug(() -> "AiSList fetch response: " + code + " url: " + url);
                return null;
            }

            InputStream raw = connection.getInputStream();
            InputStream stream = "gzip".equalsIgnoreCase(connection.getContentEncoding())
                    ? new GZIPInputStream(raw)
                    : raw;

            StringBuilder sb = new StringBuilder(256 * 1024);
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                char[] buf = new char[8192];
                int read;
                while ((read = reader.read(buf)) >= 0) {
                    if (sb.length() + read > MAX_RESPONSE_BYTES) {
                        Logger.printException(() -> "AiSList response exceeded "
                                + MAX_RESPONSE_BYTES + " bytes: " + url);
                        return null;
                    }
                    sb.append(buf, 0, read);
                }
            }
            return sb.toString();
        } catch (Exception ex) {
            Logger.printInfo(() -> "AiSList fetch failed for url: " + url, ex);
            return null;
        } finally {
            if (disconnect && connection != null) connection.disconnect();
            Logger.printDebug(() -> "AiSList fetch took "
                    + (System.currentTimeMillis() - start) + "ms url: " + url);
        }
    }
}
