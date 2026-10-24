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

import app.morphe.extension.shared.Logger;

/**
 * The Gemini API speaks its own protocol, not the OpenAI one, so it has a client of its own.
 * The prompt and the parsing of the answer are shared with {@link OpenAIClient}.
 */
public final class GeminiClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 45_000;

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private static final String MODEL_PREFIX = "models/";
    /** Sent as a header rather than in the query, where it would end up in request logs. */
    private static final String API_KEY_HEADER = "x-goog-api-key";

    private GeminiClient() {
    }

    @Nullable
    public static List<String> mapLines(String apiKey, String model, String prompt, List<String> sourceLines) {
        if (!OpenAIClient.fitsOneRequest(sourceLines)) {
            return null;
        }
        return OpenAIClient.mapResponse(request(apiKey, model, prompt), sourceLines);
    }

    @Nullable
    private static String request(String apiKey, String model, String prompt) {
        try {
            String cleanModel = model.startsWith(MODEL_PREFIX) ? model.substring(MODEL_PREFIX.length()) : model;

            JSONObject root = new JSONObject();
            JSONObject userContent = new JSONObject()
                    .put("role", "user")
                    .put("parts", new JSONArray().put(new JSONObject().put("text", prompt)));
            root.put("contents", new JSONArray().put(userContent));
            root.put("generationConfig", new JSONObject().put("temperature", 0.2));

            byte[] payload = root.toString().getBytes(StandardCharsets.UTF_8);

            HttpURLConnection conn = (HttpURLConnection) new URL(
                    BASE_URL + "/" + MODEL_PREFIX + cleanModel + ":generateContent").openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty(API_KEY_HEADER, apiKey);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    Logger.printDebug(() -> "Gemini API error code: " + code);
                    return null;
                }

                JSONObject resp = new JSONObject(OpenAIClient.readResponse(conn));
                JSONArray candidates = resp.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) {
                    return null;
                }
                JSONObject content = candidates.getJSONObject(0).optJSONObject("content");
                if (content == null) {
                    return null;
                }
                JSONArray parts = content.optJSONArray("parts");
                if (parts == null || parts.length() == 0) {
                    return null;
                }
                return parts.getJSONObject(0).optString("text", null);
            } finally {
                conn.disconnect();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Gemini request failed", ex);
            return null;
        }
    }

    /** The Gemini models that can generate text, empty when none could be listed. */
    public static List<String> fetchModels(String apiKey) {
        List<String> models = new ArrayList<>();
        if (apiKey.isEmpty()) {
            return models;
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(BASE_URL + "/models").openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty(API_KEY_HEADER, apiKey);

                if (conn.getResponseCode() != 200) {
                    return models;
                }

                JSONArray modelArr = new JSONObject(OpenAIClient.readResponse(conn)).optJSONArray("models");
                if (modelArr == null) {
                    return models;
                }
                for (int i = 0; i < modelArr.length(); i++) {
                    JSONObject m = modelArr.getJSONObject(i);
                    String name = m.optString("name", "");
                    if (name.startsWith(MODEL_PREFIX) && name.contains("gemini")
                            && supportsGenerateContent(m.optJSONArray("supportedGenerationMethods"))) {
                        models.add(name.substring(MODEL_PREFIX.length()));
                    }
                }
            } finally {
                conn.disconnect();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Gemini fetchModels failed", ex);
        }
        return models;
    }

    private static boolean supportsGenerateContent(@Nullable JSONArray methods) {
        if (methods == null) {
            return false;
        }
        for (int i = 0; i < methods.length(); i++) {
            if ("generateContent".equals(methods.optString(i))) {
                return true;
            }
        }
        return false;
    }
}
