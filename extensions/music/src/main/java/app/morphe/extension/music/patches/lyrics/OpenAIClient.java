/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

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

final class OpenAIClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_CHARS = 3000;

    private OpenAIClient() {
    }

    @Nullable
    static String request(String baseUrl, String apiToken, String model,
                          String userPrompt, @Nullable String systemPrompt) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);

            JSONArray messages = new JSONArray();
            if (systemPrompt != null && !systemPrompt.isEmpty()) {
                messages.put(new JSONObject()
                        .put("role", "system")
                        .put("content", systemPrompt));
            }
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userPrompt));
            body.put("messages", messages);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

            URL url = new URL(baseUrl);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                conn.setRequestMethod("POST");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Content-Type", "application/json");
                if (apiToken != null && !apiToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiToken);
                }
                conn.setDoOutput(true);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }

                int code = conn.getResponseCode();
                if (code != 200) {
                    conn.disconnect();
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

                JSONObject json = new JSONObject(sb.toString());
                JSONArray choices = json.optJSONArray("choices");
                if (choices == null || choices.length() == 0) {
                    return null;
                }

                JSONObject message = choices.getJSONObject(0).optJSONObject("message");
                if (message == null) {
                    return null;
                }

                String content = message.optString("content", null);
                if (content != null && !content.isEmpty()) {
                    return content;
                }

                String reasoning = message.optString("reasoning", null);
                if (reasoning != null && !reasoning.isEmpty()) {
                    String extracted = extractLastNumberedBlock(reasoning);
                    if (extracted != null) {
                        return extracted;
                    }
                    return reasoning;
                }

                return null;
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "OpenAI request failed", e);
            return null;
        }
    }

    @Nullable
    static String extractLastNumberedBlock(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        int blockEnd = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty() || trimmed.startsWith("Line")) {
                break;
            }
            if (trimmed.matches("\\d+\\..+")) {
                if (blockEnd == -1) {
                    blockEnd = i;
                }
            } else if (blockEnd != -1) {
                break;
            }
        }
        if (blockEnd == -1) {
            return null;
        }
        int blockStart = blockEnd;
        while (blockStart > 0 && lines[blockStart - 1].trim().matches("\\d+\\..+")) {
            blockStart--;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = blockStart; i <= blockEnd; i++) {
            String line = lines[i].trim();
            int dot = line.indexOf('.');
            if (dot >= 0 && dot + 1 < line.length()) {
                line = line.substring(dot + 1).trim();
            }
            //noinspection SizeReplaceableByIsEmpty
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb.toString();
    }

    private static String stripLineNumber(String line) {
        return line.replaceFirst("^\\d+\\.\\s*", "");
    }

    private static boolean isNoteOrEmptyLine(String text) {
        if (text == null) return true;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return true;
        return !trimmed.matches(".*\\p{L}.*");
    }

    @Nullable
    static List<String> mapLines(String baseUrl, String apiToken, String model,
            String prompt, String systemPrompt, List<String> sourceLines) {
        int totalChars = 0;
        for (String line : sourceLines) {
            totalChars += line.length() + 1;
        }
        if (totalChars > MAX_CHARS) {
            return null;
        }
        String response = request(baseUrl, apiToken, model, prompt, systemPrompt);
        if (response == null) {
            return null;
        }
        String[] result = response.split("\n", -1);
        int end = result.length;
        while (end > 0 && result[end - 1].trim().isEmpty()) {
            end--;
        }
        if (end == 0) {
            return null;
        }
        boolean allSkip = true;
        for (int i = 0; i < end; i++) {
            result[i] = stripLineNumber(result[i]);
            if (!result[i].trim().equalsIgnoreCase("SKIP")) {
                allSkip = false;
            }
        }
        if (allSkip) {
            return null;
        }
        if (end > sourceLines.size() + 2 || end < sourceLines.size() - 2) {
            return null;
        }
        List<String> out = new ArrayList<>(sourceLines.size());
        for (int i = 0; i < sourceLines.size(); i++) {
            if (i < end) {
                String trimmed = result[i].trim();
                if (trimmed.equalsIgnoreCase("SKIP") || isNoteOrEmptyLine(sourceLines.get(i))) {
                    out.add("");
                } else {
                    out.add(trimmed);
                }
            } else {
                out.add("");
            }
        }
        return out;
    }
}
