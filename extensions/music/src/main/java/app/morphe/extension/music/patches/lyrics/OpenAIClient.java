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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;

public final class OpenAIClient {

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

    /**
     * The prompt template sent to the AI endpoint. One template covers both tasks;
     * {@link #renderPrompt} fills in its variables:
     * <ul>
     *   <li>{@code {task}} - "translation" or "romanization"</li>
     *   <li>{@code {language}} - the target language</li>
     *   <li>{@code {title}}, {@code {artist}} - the song</li>
     *   <li>{@code {count}} - the number of lines</li>
     *   <li>{@code {lines}} - the lyric lines, one per line</li>
     * </ul>
     * Unknown variables are left as typed.
     */
    public static final String DEFAULT_PROMPT =
            "You are a professional lyrics {task} engine. Perform {task} on every line below for "
                    + "{language}, keeping the original meaning, tone and punctuation.\n"
                    + "Song: {title} by {artist}\n"
                    + "\n"
                    + "Output format: Number every result on its own line, like:\n"
                    + "1. first result\n"
                    + "2. second result\n"
                    + "\n"
                    + "CRITICAL RULES:\n"
                    + "- Output exactly {count} numbered lines (same as input count)\n"
                    + "- Number format: \"N. text\" (number, period, space, text)\n"
                    + "- Do NOT include the original lines in your output\n"
                    + "- Do NOT use \"Line N:\" format\n"
                    + "- Follow the standard {language} conventions for {task}; no annotations, "
                    + "notes or tone marks\n"
                    + "- If a line already needs no {task}, output one \"SKIP\" for it\n"
                    + "- Output ONLY the {task}. No reasoning, no explanations, no "
                    + "step-by-step thinking.\n"
                    + "\n"
                    + "{lines}";

    private static final Pattern PROMPT_VARIABLE = Pattern.compile("\\{(\\w+)\\}");

    public static String renderPrompt(String template, String task, String language,
            String title, String artist, List<String> lines) {
        if (template == null || template.isEmpty()) {
            template = DEFAULT_PROMPT;
        }
        StringBuilder linesText = new StringBuilder();
        for (String line : lines) {
            linesText.append(line != null ? line : "").append('\n');
        }
        String count = String.valueOf(lines.size());
        Matcher matcher = PROMPT_VARIABLE.matcher(template);
        StringBuilder out = new StringBuilder(template.length() + 256);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = promptValue(name, task, language, title, artist, count,
                    linesText.toString());
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    private static String promptValue(String name, String task, String language,
            String title, String artist, String count, String lines) {
        if ("task".equals(name)) {
            return task;
        }
        if ("language".equals(name)) {
            return language;
        }
        if ("title".equals(name)) {
            return title != null ? title : "";
        }
        if ("artist".equals(name)) {
            return artist != null ? artist : "";
        }
        if ("count".equals(name)) {
            return count;
        }
        if ("lines".equals(name)) {
            return lines;
        }
        return null;
    }
}
