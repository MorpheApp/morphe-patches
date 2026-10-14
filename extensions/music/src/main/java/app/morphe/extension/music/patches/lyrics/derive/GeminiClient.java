/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;

public final class GeminiClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 45_000;

    private static final String BASE_URL = "https://generativelanguage.googleapis.com/v1beta";

    private GeminiClient() {
    }

    @Nullable
    public static List<String> translate(@NonNull List<String> lines,
                                         @NonNull String targetLang,
                                         @Nullable String title,
                                         @Nullable String artist,
                                         @NonNull String apiKey,
                                         @NonNull String model) {
        if (apiKey.isEmpty() || lines.isEmpty()) {
            return null;
        }

        String prompt = LyricsTranslator.buildTranslatePrompt(lines, targetLang, title, artist);
        String systemInstruction = "You are a professional song lyrics translator. "
                + "Translate the lyrics faithfully to target language '" + targetLang + "'. "
                + "Preserve line numbers 1., 2., ... matching each input line. "
                + "If an input line contains purely instrumental marks or cannot be translated, output SKIP for that line. "
                + "Do not add explanations or notes.";

        String response = requestGenerateContent(apiKey, model, prompt, systemInstruction);
        if (response == null) {
            return null;
        }

        return parseNumberedLines(response, lines.size());
    }

    @Nullable
    public static List<String> romanize(@NonNull List<String> lines,
                                        @NonNull String targetLang,
                                        @Nullable String title,
                                        @Nullable String artist,
                                        @NonNull String apiKey,
                                        @NonNull String model) {
        if (apiKey.isEmpty() || lines.isEmpty()) {
            return null;
        }

        String prompt = LyricsRomanizer.buildRomanizePrompt(lines, targetLang, title, artist);
        String systemInstruction = "You are an expert phonetic transliteration engine for music lyrics. "
                + "Romanize the text line-by-line into clean standard Latin characters (Romaji for Japanese, Pinyin for Chinese, Romaja for Korean). "
                + "Return each line numbered 1., 2., ... matching the input lines. Do not add notes.";

        String response = requestGenerateContent(apiKey, model, prompt, systemInstruction);
        if (response == null) {
            return null;
        }

        return parseNumberedLines(response, lines.size());
    }

    @Nullable
    public static String requestGenerateContent(@NonNull String apiKey,
                                                @NonNull String model,
                                                @NonNull String userPrompt,
                                                @Nullable String systemPrompt) {
        try {
            String cleanModel = model.startsWith("models/") ? model.substring(7) : model;
            String endpoint = BASE_URL + "/models/" + cleanModel + ":generateContent?key=" + apiKey;

            JSONObject root = new JSONObject();

            JSONArray contents = new JSONArray();
            JSONObject userContent = new JSONObject();
            userContent.put("role", "user");
            JSONArray parts = new JSONArray();
            parts.put(new JSONObject().put("text", userPrompt));
            userContent.put("parts", parts);
            contents.put(userContent);
            root.put("contents", contents);

            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                JSONObject sysInstruction = new JSONObject();
                JSONArray sysParts = new JSONArray();
                sysParts.put(new JSONObject().put("text", systemPrompt));
                sysInstruction.put("parts", sysParts);
                root.put("systemInstruction", sysInstruction);
            }

            JSONObject genConfig = new JSONObject();
            genConfig.put("temperature", 0.2);
            root.put("generationConfig", genConfig);

            byte[] payload = root.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
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

                StringBuilder sb = new StringBuilder(512);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JSONObject resp = new JSONObject(sb.toString());
                JSONArray candidates = resp.optJSONArray("candidates");
                if (candidates == null || candidates.length() == 0) {
                    return null;
                }

                JSONObject candidate = candidates.getJSONObject(0);
                JSONObject content = candidate.optJSONObject("content");
                if (content == null) {
                    return null;
                }

                JSONArray respParts = content.optJSONArray("parts");
                if (respParts == null || respParts.length() == 0) {
                    return null;
                }

                return respParts.getJSONObject(0).optString("text", null);
            } finally {
                conn.disconnect();
            }
        } catch (Exception ex) {
            Logger.printDebug(() -> "Gemini request failed", ex);
            return null;
        }
    }

    @NonNull
    public static List<String> fetchModels(@NonNull String apiKey) {
        List<String> models = new ArrayList<>();
        if (apiKey.isEmpty()) {
            return models;
        }
        try {
            String endpoint = BASE_URL + "/models?key=" + apiKey;
            URL url = new URL(endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);

                int code = conn.getResponseCode();
                if (code != 200) {
                    return models;
                }

                StringBuilder sb = new StringBuilder(1024);
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        sb.append(line);
                    }
                }

                JSONObject json = new JSONObject(sb.toString());
                JSONArray modelArr = json.optJSONArray("models");
                if (modelArr != null) {
                    for (int i = 0; i < modelArr.length(); i++) {
                        JSONObject m = modelArr.getJSONObject(i);
                        String name = m.optString("name", "");
                        JSONArray methods = m.optJSONArray("supportedGenerationMethods");
                        boolean canGenerate = false;
                        if (methods != null) {
                            for (int j = 0; j < methods.length(); j++) {
                                if ("generateContent".equals(methods.optString(j))) {
                                    canGenerate = true;
                                    break;
                                }
                            }
                        }
                        if (canGenerate && name.startsWith("models/")) {
                            String shortName = name.substring(7);
                            if (shortName.contains("gemini")) {
                                models.add(shortName);
                            }
                        }
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

    public static boolean isSkipToken(@Nullable String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.equalsIgnoreCase("SKIP") || t.equalsIgnoreCase("[SKIP]")
                || t.equalsIgnoreCase("<SKIP>") || t.equalsIgnoreCase("(SKIP)")
                || t.equalsIgnoreCase("SKIP.");
    }

    private static final java.util.regex.Pattern NUMBERED_LINE_PATTERN =
            java.util.regex.Pattern.compile("^(?:Line\\s+)?(\\d+)[.)\\]:\\-]\\s*(.*)$", java.util.regex.Pattern.CASE_INSENSITIVE);

    @Nullable
    static List<String> parseNumberedLines(String text, int expectedCount) {
        if (text == null || expectedCount <= 0) return null;
        String[] raw = text.split("\n", -1);
        int end = raw.length;
        while (end > 0 && raw[end - 1].trim().isEmpty()) {
            end--;
        }
        if (end == 0) return null;

        String[] byIndex = new String[expectedCount];
        int matchedNumberedCount = 0;
        boolean anyIndexedNonEmpty = false;

        for (int i = 0; i < end; i++) {
            String line = raw[i].trim();
            if (line.isEmpty()) continue;
            java.util.regex.Matcher m = NUMBERED_LINE_PATTERN.matcher(line);
            if (m.matches()) {
                try {
                    int num = Integer.parseInt(m.group(1));
                    int idx = num - 1;
                    if (idx >= 0 && idx < expectedCount) {
                        matchedNumberedCount++;
                        String val = m.group(2) != null ? m.group(2).trim() : "";
                        if (isSkipToken(val)) {
                            byIndex[idx] = "";
                        } else {
                            byIndex[idx] = val;
                            if (!val.isEmpty()) {
                                anyIndexedNonEmpty = true;
                            }
                        }
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }

        if (matchedNumberedCount >= Math.max(1, expectedCount / 2)) {
            if (!anyIndexedNonEmpty) {
                return null;
            }
            List<String> indexedList = new ArrayList<>(expectedCount);
            for (int i = 0; i < expectedCount; i++) {
                indexedList.add(byIndex[i] != null ? byIndex[i] : "");
            }
            return indexedList;
        }

        List<String> list = new ArrayList<>(expectedCount);
        boolean anyNonEmpty = false;
        for (int i = 0; i < end && list.size() < expectedCount; i++) {
            String line = raw[i].trim();
            if (line.isEmpty()) continue;
            line = line.replaceFirst("^\\d+[.)\\]:\\-]\\s*", "").trim();
            if (isSkipToken(line)) {
                line = "";
            } else {
                anyNonEmpty = true;
            }
            list.add(line);
        }

        if (!anyNonEmpty) {
            return null;
        }

        while (list.size() < expectedCount) {
            list.add("");
        }
        return list;
    }
}
