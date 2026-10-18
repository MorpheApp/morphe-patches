/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
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
 * Loads the current Edge Read Aloud voices without blocking the UI.
 * A last-known-good disk snapshot survives restarts; bundled voices remain the fallback.
 */
final class EdgeTtsVoiceRepository {
    private static final String PREFS = "morphe_vot_edge_voices";
    private static final String KEY_CATALOG = "catalog";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1_000;
    private static final long RETRY_INTERVAL_MS = 30L * 60 * 1_000;
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

    private EdgeTtsVoiceRepository() {}

    /** Coalesce repeated picker opens into one background refresh. Callback runs on the UI thread. */
    static void refreshIfNeeded(Context context, Runnable onCatalogChanged) {
        Context appContext = context.getApplicationContext();
        synchronized (LOCK) {
            if (inFlight) {
                listeners.add(onCatalogChanged);
                return;
            }
            if (diskLoaded && System.currentTimeMillis() - lastAttemptMs < RETRY_INTERVAL_MS) {
                return;
            }
            inFlight = true;
            listeners.add(onCatalogChanged);
        }
        EXECUTOR.execute(() -> loadAndRefresh(appContext));
    }

    private static void loadAndRefresh(Context context) {
        boolean changed = false;
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            boolean validCache = false;
            if (!diskLoaded) {
                String cached = prefs.getString(KEY_CATALOG, null);
                diskLoaded = true;
                if (cached != null) {
                    try {
                        VoiceCatalog.setAvailableVoices(parse(cached));
                        validCache = true;
                        changed = true;
                    } catch (Exception e) {
                        Logger.printDebug(() -> "Ignoring invalid cached Edge voice catalog: " + e);
                    }
                }
            } else {
                validCache = prefs.contains(KEY_CATALOG);
            }

            long now = System.currentTimeMillis();
            long fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0);
            // Don't refetch when cached data is fresh, even after a process restart.
            if (validCache && now >= fetchedAt && now - fetchedAt < CACHE_TTL_MS) return;
            if (now >= lastAttemptMs && now - lastAttemptMs < RETRY_INTERVAL_MS) return;
            lastAttemptMs = now;

            String body = download();
            List<VoiceCatalog.Voice> voices = parse(body);
            VoiceCatalog.setAvailableVoices(voices);
            prefs.edit().putString(KEY_CATALOG, body).putLong(KEY_FETCHED_AT, now).apply();
            changed = true;
            Logger.printDebug(() -> "Updated Edge TTS catalog with " + voices.size() + " voices");
        } catch (Exception e) {
            // Voice discovery is optional; network failures must not interrupt translation.
            Logger.printDebug(() -> "Edge TTS catalog unavailable; using saved voices: " + e);
        } finally {
            final boolean notifyListeners = changed;
            List<Runnable> callbacks;
            synchronized (LOCK) {
                callbacks = new ArrayList<>(listeners);
                listeners.clear();
                inFlight = false;
            }
            if (notifyListeners) {
                Utils.runOnMainThread(() -> {
                    for (Runnable callback : callbacks) callback.run();
                });
            }
        }
    }

    private static String download() throws IOException {
        String token = EdgeTtsV2Token.TRUSTED_CLIENT_TOKEN;
        String query = "https://speech.platform.bing.com/consumer/speech/synthesize/readaloud/"
                + "voices/list?trustedclienttoken=" + token
                + "&Sec-MS-GEC=" + EdgeTtsV2Token.generate(System.currentTimeMillis(), token)
                + "&Sec-MS-GEC-Version=" + EdgeTtsV2Token.SEC_MS_GEC_VERSION;
        HttpURLConnection conn = (HttpURLConnection) new URL(query).openConnection();
        conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
        conn.setReadTimeout(READ_TIMEOUT_MS);
        conn.setRequestProperty("User-Agent", EdgeTtsV2Token.USER_AGENT);
        conn.setRequestProperty("Accept", "*/*");
        conn.setRequestProperty("Sec-CH-UA", "\" Not;A Brand\";v=\"99\", \"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\"");
        conn.setRequestProperty("Sec-CH-UA-Mobile", "?0");
        conn.setRequestProperty("Sec-Fetch-Site", "none");
        conn.setRequestProperty("Sec-Fetch-Mode", "cors");
        conn.setRequestProperty("Sec-Fetch-Dest", "empty");
        conn.setRequestProperty("Cookie", "muid=" + UUID.randomUUID().toString().replace("-", "").toUpperCase(Locale.US));
        try {
            int status = conn.getResponseCode();
            if (status != HttpURLConnection.HTTP_OK) throw new IOException("Voice list HTTP " + status);
            try (InputStream stream = conn.getInputStream();
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                StringBuilder result = new StringBuilder();
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    if (result.length() + count > MAX_RESPONSE_LENGTH) {
                        throw new IOException("Edge voice catalog response too large");
                    }
                    result.append(buffer, 0, count);
                }
                return result.toString();
            }
        } finally {
            conn.disconnect();
        }
    }

    /** Parse and validate the complete response before it can replace the bundled catalog. */
    private static List<VoiceCatalog.Voice> parse(String json) throws Exception {
        JSONArray source = new JSONArray(json);
        Map<String, VoiceCatalog.Voice> unique = new LinkedHashMap<>();
        for (int i = 0; i < source.length(); i++) {
            JSONObject row = source.optJSONObject(i);
            if (row == null) continue;
            String id = row.optString("ShortName", "");
            String locale = row.optString("Locale", "");
            String gender = row.optString("Gender", "");
            if (id.length() < 8 || id.length() > 100 || locale.isEmpty()
                    || !id.startsWith(locale + "-") || !id.matches("[A-Za-z0-9-]+")) continue;
            if (!"Male".equalsIgnoreCase(gender) && !"Female".equalsIgnoreCase(gender)) continue;
            unique.putIfAbsent(id, new VoiceCatalog.Voice("Male".equalsIgnoreCase(gender), id));
        }
        if (unique.size() < MIN_VALID_VOICES) {
            throw new IOException("Incomplete Edge voice catalog: " + unique.size() + " voices");
        }
        return new ArrayList<>(unique.values());
    }
}
