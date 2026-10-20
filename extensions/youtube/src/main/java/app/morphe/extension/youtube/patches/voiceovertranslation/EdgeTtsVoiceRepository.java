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

/** Microsoft's voice list plus the last successfully downloaded disk copy. */
final class EdgeTtsVoiceRepository {
    private static final String PREFS = "morphe_vot_edge_voices";
    private static final String KEY_CATALOG = "catalog";
    private static final String KEY_FETCHED_AT = "fetched_at";
    private static final long CACHE_TTL_MS = 24L * 60 * 60 * 1_000;
    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_RESPONSE_LENGTH = 2_000_000;
    private static final int MIN_VALID_VOICES = 100;

    private static final long RETRY_DELAY_MS = 30L * 60 * 1_000;
    private static final Object LOCK = new Object();
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "morphe-edge-voices");
        thread.setDaemon(true);
        return thread;
    });
    private static final List<Runnable> callbacks = new ArrayList<>();
    private static boolean loading;
    // Only the dedicated executor accesses these fields.
    private static boolean cacheLoaded;
    private static long lastAttemptMs;

    private EdgeTtsVoiceRepository() {}

    /** The cached voices stay usable while the online list is refreshed. */
    static void load(Context context, Runnable onUpdate) {
        if (context == null) return;
        synchronized (LOCK) {
            if (onUpdate != null) callbacks.add(onUpdate);
            if (loading) return;
            loading = true;
        }
        Context appContext = context.getApplicationContext();
        EXECUTOR.execute(() -> loadAndRefresh(appContext));
    }

    private static void loadAndRefresh(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            if (!cacheLoaded) {
                cacheLoaded = true;
                String cached = prefs.getString(KEY_CATALOG, null);
                if (cached != null) {
                    try {
                        VoiceCatalog.setAvailableVoices(parse(cached));
                        notifyListeners(); // Do not wait for a potentially slow network refresh.
                    } catch (Exception ex) {
                        prefs.edit().remove(KEY_CATALOG).remove(KEY_FETCHED_AT).apply();
                        Logger.printDebug(() -> "Ignoring invalid Edge voice cache: " + ex);
                    }
                }
            }

            long now = System.currentTimeMillis();
            long savedAt = prefs.getLong(KEY_FETCHED_AT, 0);
            if (savedAt > 0 && now >= savedAt && now - savedAt < CACHE_TTL_MS) return;
            if (lastAttemptMs > 0 && now >= lastAttemptMs
                    && now - lastAttemptMs < RETRY_DELAY_MS) return;

            lastAttemptMs = now;
            String json = download();
            List<VoiceCatalog.Voice> voices = parse(json);
            // Persist a fully validated response before publishing the new snapshot.
            if (!prefs.edit().putString(KEY_CATALOG, json)
                    .putLong(KEY_FETCHED_AT, System.currentTimeMillis()).commit()) {
                Logger.printDebug(() -> "Could not persist Edge voice catalog");
            }
            VoiceCatalog.setAvailableVoices(voices);
            notifyListeners();
        } catch (Exception ex) {
            // Keep the last successfully cached snapshot (or an empty catalog on first run).
            Logger.printDebug(() -> "Edge voice catalog download failed: " + ex);
        } finally {
            synchronized (LOCK) {
                loading = false;
                callbacks.clear();
            }
        }
    }

    private static void notifyListeners() {
        List<Runnable> pending;
        synchronized (LOCK) {
            pending = new ArrayList<>(callbacks);
        }
        if (!pending.isEmpty()) {
            Utils.runOnMainThread(() -> {
                for (Runnable callback : pending) callback.run();
            });
        }
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

    /** Reject incomplete catalogs rather than replacing a working disk cache. */
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
                    || id.indexOf('-', id.indexOf('-') + 1) < 0
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
