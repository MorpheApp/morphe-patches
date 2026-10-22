/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3877
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.Logger;

public final class DeepLClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 30_000;

    private static final String API_FREE_URL = "https://api-free.deepl.com/v2/translate";
    private static final String API_PRO_URL = "https://api.deepl.com/v2/translate";

    private DeepLClient() {
    }

    @Nullable
    public static List<String> translate(List<String> lines,
                                         String targetLang,
                                         String apiKey) {
        if (apiKey.isEmpty() || lines.isEmpty()) {
            return null;
        }

        try {
            String endpoint = apiKey.endsWith(":fx") ? API_FREE_URL : API_PRO_URL;
            String deeplLang = normalizeDeepLLanguage(targetLang);

            JSONObject body = new JSONObject();
            JSONArray textArray = new JSONArray();
            for (String line : lines) {
                textArray.put(line);
            }
            body.put("text", textArray);
            body.put("target_lang", deeplLang);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Authorization", "DeepL-Auth-Key " + apiKey);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    Logger.printDebug(() -> "DeepL API error code: " + code);
                    return null;
                }

                JSONObject resp = new JSONObject(OpenAIClient.readResponse(conn));
                JSONArray translations = resp.optJSONArray("translations");
                if (translations == null) {
                    return null;
                }

                List<String> result = new ArrayList<>(lines.size());
                for (int i = 0; i < translations.length(); i++) {
                    JSONObject item = translations.getJSONObject(i);
                    result.add(item.optString("text", ""));
                }
                return result;
            } finally {
                conn.disconnect();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "DeepL request failed", ex);
            return null;
        }
    }

    private static String normalizeDeepLLanguage(String lang) {
        String clean = lang.trim().toUpperCase(Locale.ROOT);
        if (clean.equals("EN") || clean.startsWith("EN-")) {
            return "EN-US";
        }
        if (clean.equals("PT") || clean.equals("PT-BR")) {
            return "PT-BR";
        }
        if (clean.equals("PT-PT")) {
            return "PT-PT";
        }
        if (clean.equals("ZH") || clean.equals("ZH-CN") || clean.equals("ZH-HANS") || clean.equals("ZH-SG")) {
            return "ZH-HANS";
        }
        if (clean.equals("ZH-TW") || clean.equals("ZH-HK") || clean.equals("ZH-HANT")) {
            return "ZH-HANT";
        }
        if (clean.equals("IN") || clean.equals("ID")) {
            return "ID";
        }
        // Other languages use the plain two letter code, such as JA, KO or DE.
        int hyphen = clean.indexOf('-');
        return hyphen > 0 ? clean.substring(0, hyphen) : clean;
    }
}
