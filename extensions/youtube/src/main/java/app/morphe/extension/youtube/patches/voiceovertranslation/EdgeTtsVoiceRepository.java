/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3840
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Discovers available Edge TTS voices asynchronously.
 *
 * <p>The built-in voices always work offline. A validated server response becomes the
 * active catalog, and the last successful response is stored for subsequent launches.
 * Network errors never interrupt speech synthesis or replace a working catalog.
 */
final class EdgeTtsVoiceRepository {
    private static final String PREFS = "morphe_vot_edge_voices";
    private static final String KEY_CATALOG = "catalog";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1_000;
    private static final long AUTOMATIC_RETRY_MS = 30L * 60 * 1_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_RESPONSE_LENGTH = 2_000_000;
    private static final int MIN_VALID_VOICES = 100;

    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "morphe-edge-voices");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<Runnable> listeners = new ArrayList<>();

    private static boolean inFlight;
    private static boolean diskLoaded;
    private static long lastAttemptMs;
    private static volatile Status status = new Status(Source.FALLBACK,
            VoiceCatalog.getAvailableVoices().size(), 0, 0, "", 0, false, null);

    enum Source { ONLINE, CACHE, FALLBACK }

    /** Immutable status read by the voice picker without locking. */
    static final class Status {
        final Source source;
        final int totalVoices;
        final int added;
        final int removed;
        final String comparison;
        final long lastUpdatedMs;
        final boolean refreshing;
        final String error;

        Status(Source source, int totalVoices, int added, int removed,
               String comparison, long lastUpdatedMs, boolean refreshing, String error) {
            this.source = source;
            this.totalVoices = totalVoices;
            this.added = added;
            this.removed = removed;
            this.comparison = comparison;
            this.lastUpdatedMs = lastUpdatedMs;
            this.refreshing = refreshing;
            this.error = error;
        }

        Status withRefreshing(boolean refreshing) {
            return new Status(source, totalVoices, added, removed, comparison,
                    lastUpdatedMs, refreshing, error);
        }

        Status withError(String error) {
            return new Status(source, totalVoices, added, removed, comparison,
                    lastUpdatedMs, refreshing, error);
        }
    }

    private EdgeTtsVoiceRepository() {}

    static Status getStatus() {
        return status;
    }

    /** Refreshes at most once per day unless the cached data is stale or absent. */
    static void refreshIfNeeded(Context context, Runnable onFinished) {
        schedule(context, false, onFinished);
    }

    /** User-initiated refresh: bypass cache TTL and automatic retry delay. */
    static void refreshNow(Context context, Runnable onFinished) {
        schedule(context, true, onFinished);
    }

    private static void schedule(Context context, boolean force, Runnable onFinished) {
        synchronized (LOCK) {
            listeners.add(onFinished);
            if (inFlight) return;
            inFlight = true;
            status = status.withRefreshing(true);
        }
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> loadAndRefresh(appContext, force));
    }

    private static void loadAndRefresh(Context context, boolean force) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            loadSavedCatalog(prefs);

            long now = System.currentTimeMillis();
            long fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0);
            boolean fresh = status.source != Source.FALLBACK && fetchedAt > 0
                    && now >= fetchedAt && now - fetchedAt < CACHE_TTL_MS;
            if (!force && fresh) {
                logStatus("using saved catalog", status);
                return;
            }
            if (!force && now >= lastAttemptMs && now - lastAttemptMs < AUTOMATIC_RETRY_MS) {
                logStatus("automatic retry deferred", status);
                return;
            }
            lastAttemptMs = now;

            List<VoiceCatalog.Voice> previous = VoiceCatalog.getAvailableVoices();
            String response = download();
            List<VoiceCatalog.Voice> downloaded = parse(response);
            VoiceCatalog.ChangeSummary changes = VoiceCatalog.compare(previous, downloaded);
            String comparison = status.source == Source.FALLBACK ? "FALLBACK" : "PREVIOUS";

            // Publish only after receiving and validating the complete response.
            long updatedAt = System.currentTimeMillis();
            VoiceCatalog.setAvailableVoices(downloaded);
            prefs.edit().putString(KEY_CATALOG, response)
                    .putLong(KEY_FETCHED_AT, updatedAt).apply();
            status = new Status(Source.ONLINE, downloaded.size(), changes.added,
                    changes.removed, comparison, updatedAt, true, null);
            logStatus("HTTP 200", status);
        } catch (Exception error) {
            // Retain the last-good snapshot, including the bundled fallback.
            String message = error.getMessage() != null
                    ? error.getMessage() : error.getClass().getSimpleName();
            status = status.withError(message);
            logStatus("refresh failed", status);
        } finally {
            status = status.withRefreshing(false);
            List<Runnable> callbacks;
            synchronized (LOCK) {
                callbacks = new ArrayList<>(listeners);
                listeners.clear();
                inFlight = false;
            }
            Utils.runOnMainThread(() -> {
                for (Runnable callback : callbacks) callback.run();
            });
        }
    }

    private static void loadSavedCatalog(SharedPreferences prefs) {
        if (diskLoaded) return;
        diskLoaded = true;
        String cached = prefs.getString(KEY_CATALOG, null);
        if (cached == null) {
            logStatus("bundled fallback", status);
            return;
        }
        try {
            List<VoiceCatalog.Voice> voices = parse(cached);
            VoiceCatalog.setAvailableVoices(voices);
            status = new Status(Source.CACHE, voices.size(), 0, 0, "",
                    prefs.getLong(KEY_FETCHED_AT, 0), true, null);
            logStatus("loaded disk cache", status);
        } catch (Exception error) {
            // A corrupted cache should not stop an online refresh.
            prefs.edit().remove(KEY_CATALOG).remove(KEY_FETCHED_AT).apply();
            logStatus("invalid disk cache: " + error.getMessage(), status);
        }
    }

    private static void logStatus(String action, Status current) {
        Logger.printDebug(() -> "EdgeTtsVoiceCatalog: " + action
                + " source=" + current.source
                + " total=" + current.totalVoices
                + " added=" + current.added
                + " removed=" + current.removed
                + " comparedTo=" + current.comparison
                + " fetchedAt=" + current.lastUpdatedMs
                + (current.error != null ? " error=" + current.error : ""));
    }

    private static String download() throws IOException {
        String token = EdgeTtsConfig.TRUSTED_CLIENT_TOKEN;
        String query = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/"
                + "voices/list?trustedclienttoken=" + token
                + "&Sec-MS-GEC=" + EdgeTtsV2Token.generate(System.currentTimeMillis(), token)
                + "&Sec-MS-GEC-Version=" + EdgeTtsConfig.SEC_MS_GEC_VERSION;

        HttpURLConnection conn = (HttpURLConnection) new URL(query).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", EdgeTtsConfig.USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Sec-CH-UA",
                "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\"");
        conn.setRequestProperty("Sec-CH-UA-Mobile", "?0");
        conn.setRequestProperty("Sec-Fetch-Site", "none");
        conn.setRequestProperty("Sec-Fetch-Mode", "cors");
        conn.setRequestProperty("Sec-Fetch-Dest", "empty");
        conn.setRequestProperty("Cookie", "muid="
                + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.US));
        try {
            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                throw new IOException("HTTP " + code);
            }
            try (InputStream input = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(input, StandardCharsets.UTF_8))) {
                StringBuilder body = new StringBuilder();
                char[] chars = new char[4096];
                int read;
                while ((read = reader.read(chars)) != -1) {
                    if (body.length() + read > MAX_RESPONSE_LENGTH) {
                        throw new IOException("Voice catalog response too large");
                    }
                    body.append(chars, 0, read);
                }
                return body.toString();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** A partial or malformed catalog must not replace valid cached/bundled voices. */
    static List<VoiceCatalog.Voice> parse(String json) throws Exception {
        JSONArray rows = new JSONArray(json);
        Map<String, VoiceCatalog.Voice> unique = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("ShortName", "");
            String locale = row.optString("Locale", "");
            String gender = row.optString("Gender", "");
            if (id.length() < 8 || id.length() > 100 || locale.isEmpty()
                    || !id.startsWith(locale + "-") || !id.matches("[A-Za-z0-9-]+")) {
                continue;
            }
            if (!"Male".equalsIgnoreCase(gender) && !"Female".equalsIgnoreCase(gender)) continue;
            unique.putIfAbsent(id, new VoiceCatalog.Voice("Male".equalsIgnoreCase(gender), id));
        }
        if (unique.size() < MIN_VALID_VOICES) {
            throw new IOException("Incomplete Edge voice catalog: " + unique.size() + " voices");
        }
        return new ArrayList<>(unique.values());
    }
}
