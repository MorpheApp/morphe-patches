/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

public final class OpenAIClient {

    private static final int CONNECT_TIMEOUT_MS = 10_000;
    private static final int READ_TIMEOUT_MS = 45_000;
    private static final int MAX_CHARS = 3000;

    private static final String MODELS_PATH_SUFFIX = "/models";

    public static boolean validateConfig(String baseUrl, String apiToken, String model) {
        if (baseUrl == null || baseUrl.trim().isEmpty()
                || model == null || model.trim().isEmpty()) {
            return false;
        }
        HttpURLConnection conn = null;
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", new JSONArray().put(
                    new JSONObject().put("role", "user").put("content", "hi")));
            body.put("max_tokens", 1);
            // Asked what the feature will ask it, so a body the feature needs is one the endpoint accepts.
            applyExtraBody(body);

            conn = (HttpURLConnection) new URL(baseUrl.trim()).openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Content-Type", "application/json");
            if (apiToken != null && !apiToken.isEmpty()) {
                conn.setRequestProperty("Authorization", "Bearer " + apiToken);
            }
            conn.setDoOutput(true);

            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(payload.length);
            try (OutputStream stream = conn.getOutputStream()) {
                stream.write(payload);
            }

            return conn.getResponseCode() == 200;
        } catch (Exception ex) {
            Logger.printDebug(() -> "Could not validate the AI endpoint", ex);
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    public static String[] fetchReasoningEfforts(String baseUrl, String apiToken, String model) {
        final String url = modelsUrl(baseUrl);
        if (url == null) {
            return new String[0];
        }
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            try {
                conn.setRequestMethod("GET");
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                if (apiToken != null && !apiToken.isEmpty()) {
                    conn.setRequestProperty("Authorization", "Bearer " + apiToken);
                }
                if (conn.getResponseCode() != 200) {
                    return new String[0];
                }
                return parseSupportedEfforts(readResponse(conn), model);
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "Could not read supported reasoning efforts", e);
            return new String[0];
        }
    }

    private static String modelsUrl(String baseUrl) {
        if (baseUrl == null) {
            return null;
        }
        String base = baseUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        final int scheme = base.indexOf("://");
        final int slash = base.indexOf('/', scheme + 3);
        if (slash < 0) {
            return null;
        }
        final String host = base.substring(0, slash);
        String path = base.substring(slash);
        for (String chatPath : new String[]{"/chat/completions", "/completions"}) {
            if (path.endsWith(chatPath)) {
                path = path.substring(0, path.length() - chatPath.length());
                break;
            }
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        if (path.endsWith(MODELS_PATH_SUFFIX)) {
            return base;
        }
        return host + path + MODELS_PATH_SUFFIX;
    }

    private static String[] parseSupportedEfforts(String json, String model) throws JSONException {
        final String trimmed = json.trim();
        JSONArray entries;
        if (trimmed.startsWith("[")) {
            entries = new JSONArray(trimmed);
        } else {
            final JSONObject root = new JSONObject(trimmed);
            entries = root.optJSONArray("data");
            if (entries == null) {
                entries = root.optJSONArray("models");
            }
        }
        if (entries == null) {
            return new String[0];
        }
        for (int i = 0; i < entries.length(); i++) {
            final JSONObject entry = entries.optJSONObject(i);
            if (entry == null || !describesSameModel(entry, model)) {
                continue;
            }
            final Object reasoning = entry.opt("reasoning");
            if (!(reasoning instanceof JSONObject detail)) {
                // A plain true/false says the model reasons but not how hard, so there is nothing
                // to choose from and the parameter is not worth offering.
                return new String[0];
            }
            final JSONArray efforts = detail.optJSONArray("supported_efforts");
            if (efforts == null || efforts.length() == 0) {
                return new String[0];
            }
            final List<String> values = new ArrayList<>(efforts.length());
            for (int j = 0; j < efforts.length(); j++) {
                final String value = efforts.optString(j, "").trim();
                if (!value.isEmpty() && !values.contains(value)) {
                    values.add(value);
                }
            }
            return values.toArray(new String[0]);
        }
        return new String[0];
    }

    private static boolean describesSameModel(JSONObject entry, String model) {
        if (model == null) {
            return false;
        }
        final String wanted = model.trim();
        for (String key : new String[]{"id", "name", "canonical_slug"}) {
            final String candidate = entry.optString(key, "").trim();
            if (candidate.isEmpty()) {
                continue;
            }
            if (candidate.equalsIgnoreCase(wanted)
                    || stripVariant(candidate).equalsIgnoreCase(stripVariant(wanted))) {
                return true;
            }
        }
        final JSONArray aliases = entry.optJSONArray("aliases");
        if (aliases != null) {
            for (int i = 0; i < aliases.length(); i++) {
                if (aliases.optString(i, "").equalsIgnoreCase(wanted)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String stripVariant(String id) {
        final int colon = id.indexOf(':');
        return colon > 0 ? id.substring(0, colon) : id;
    }

    /** The stored effort, sent as typed, or empty when nothing is stored. */
    @SuppressWarnings("ConstantValue")
    public static String reasoningEffort() {
        final String configured = Settings.LYRICS_AI_REASONING_EFFORT.get();
        if (configured != null && !configured.trim().isEmpty()) {
            return configured.trim();
        }
        return "";
    }

    /** An array or a bare value has no keys to lay over a body, so neither counts as one. */
    public static boolean isValidExtraBody(@Nullable String body) {
        if (body == null) return true;
        final String trimmed = body.trim();
        if (trimmed.isEmpty()) return true;
        try {
            new JSONObject(trimmed);
            return true;
        } catch (JSONException e) {
            return false;
        }
    }

    /**
     * Two keys stay ours: the model, which has a setting the endpoint was validated against, and the
     * messages, whose length decides where the answer is cut into lines.
     */
    private static void applyExtraBody(JSONObject body) {
        final String extra = Settings.LYRICS_AI_EXTRA_BODY.get();
        if (extra == null) return;
        final String trimmed = extra.trim();
        if (trimmed.isEmpty()) return;

        try {
            final JSONObject overlay = new JSONObject(trimmed);
            final Iterator<String> keys = overlay.keys();
            while (keys.hasNext()) {
                final String key = keys.next();
                if (key.equals("model") || key.equals("messages")) continue;
                // get rather than opt, so an explicit null is sent as the null it was written as.
                body.put(key, overlay.get(key));
            }
        } catch (JSONException e) {
            // A body that will not parse is left out rather than allowed to take the feature down with it.
            Logger.printDebug(() -> "The stored extra request body is not valid JSON");
        }
    }

    private OpenAIClient() {
    }

    @Nullable
    static String request(String baseUrl, String apiToken, String model,
                          String userPrompt) {
        try {
            JSONObject body = new JSONObject();
            body.put("model", model);
            final String effort = reasoningEffort();
            if (!effort.isEmpty()) {
                body.put("reasoning_effort", effort);
            }

            JSONArray messages = new JSONArray();
            messages.put(new JSONObject()
                    .put("role", "user")
                    .put("content", userPrompt));
            body.put("messages", messages);

            // Laid over last, so what the user wrote wins even where it disagrees with the effort.
            applyExtraBody(body);

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

                return parseContentFromChoices(readResponse(conn));
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            Logger.printDebug(() -> "OpenAI request failed", e);
            return null;
        }
    }

    private static String readResponse(HttpURLConnection conn) throws IOException {
        StringBuilder sb = new StringBuilder(512);
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line);
            }
        }
        return sb.toString();
    }

    @Nullable
    private static String parseContentFromChoices(String jsonResponse) throws JSONException {
        JSONObject json = new JSONObject(jsonResponse);
        JSONArray choices = json.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            return null;
        }

        JSONObject message = choices.getJSONObject(0).optJSONObject("message");
        if (message == null) {
            return null;
        }

        String content = message.optString("content", "");
        if (!content.isEmpty()) {
            return content;
        }

        String reasoning = message.optString("reasoning", "");
        if (!reasoning.isEmpty()) {
            String extracted = extractLastNumberedBlock(reasoning);
            return Objects.requireNonNullElse(extracted, reasoning);
        }

        return null;
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
        return joinNumberedBlock(blockEnd, lines).toString();
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private static StringBuilder joinNumberedBlock(int blockEnd, String[] lines) {
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
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(line);
        }
        return sb;
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
                                 String prompt, List<String> sourceLines) {
        int totalChars = 0;
        for (String line : sourceLines) {
            totalChars += line.length() + 1;
        }
        if (totalChars > MAX_CHARS) {
            return null;
        }
        String response = request(baseUrl, apiToken, model, prompt);
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
    public static final String DEFAULT_PROMPT = """
            You are a professional lyrics {task} engine. Perform {task} on every line below for {language}, keeping the original meaning, tone and punctuation.
            Song: {title} by {artist}
            
            Output format: Number every result on its own line, like:
            1. first result
            2. second result
            
            CRITICAL RULES:
            - Output exactly {count} numbered lines (same as input count)
            - Number format: "N. text" (number, period, space, text)
            - Do NOT include the original lines in your output
            - Do NOT use "Line N:" format
            - Follow the standard {language} conventions for {task}; no annotations, notes or tone marks
            - If a line already needs no {task}, output one "SKIP" for it
            - Output ONLY the {task}. No reasoning, no explanations, no step-by-step thinking.
            
            {lines}""";

    /**
     * The prompt template for the section task. Where {@link #DEFAULT_PROMPT} rewrites each line,
     * this one labels it: only the line opening a section is named, every other line is skipped,
     * so the returned list maps onto {@code LyricsLine.songPart()} without any post-processing.
     */
    public static final String SECTION_PROMPT = """
            You are a music structure analyst. Analyze the lyrics below and label the song structure.
            Song: {title} by {artist}

            For every line, output the section that line opens. Only the FIRST line of a section carries its name; every other line that continues the same section is output as "SKIP".

            Use these exact section names:
            Intro, Verse, Pre-Chorus, Chorus, Hook, Refrain, Bridge, Instrumental, Transition, False-Chorus, Outro, Reprise

            CRITICAL RULES:
            - Output exactly {count} numbered lines (same as input count)
            - Number format: "N. text" (number, period, space, text)
            - First line of a section: its name. All other lines: "SKIP"
            - Repeated sections are numbered in order: Verse 1, Verse 2, Chorus 1, Chorus 2
            - Pick the most specific name from the list above
            - Output ONLY the labels. No reasoning, no explanations.

            {lines}""";

    private static final Pattern PROMPT_VARIABLE = Pattern.compile("\\{(\\w+)\\}");

    public static String renderPrompt(String template, String task, String language,
                                      String title, String artist, List<String> lines) {
        if (template == null || template.isEmpty()) {
            template = DEFAULT_PROMPT;
        }
        StringBuilder linesText = new StringBuilder();
        for (String line : lines) {
            linesText.append(Objects.requireNonNullElse(line, "")).append('\n');
        }
        String count = String.valueOf(lines.size());
        Matcher matcher = PROMPT_VARIABLE.matcher(template);

        // Use StringBuffer instead of StringBuilder for Matcher.appendReplacement
        // to maintain compatibility with API levels below 34.
        @SuppressWarnings("StringBufferMayBeStringBuilder")
        StringBuffer out = new StringBuffer(template.length() + 256);
        while (matcher.find()) {
            String name = matcher.group(1);
            String value = promptValue(name, task, language, title, artist, count,
                    linesText.toString());
            matcher.appendReplacement(out,
                    Matcher.quoteReplacement(Objects.requireNonNullElse(value, matcher.group())));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    @Nullable
    private static String promptValue(String name, String task, String language,
                                      String title, String artist, String count, String lines) {
        if ("task".equals(name)) {
            return task;
        }
        if ("language".equals(name)) {
            return language;
        }
        if ("title".equals(name)) {
            return Objects.requireNonNullElse(title, "");
        }
        if ("artist".equals(name)) {
            return Objects.requireNonNullElse(artist, "");
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
