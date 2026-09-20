/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

public final class LyricsTranslator {

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    public interface Callback {
        void onTranslated(@Nullable List<String> translatedLines,
                          boolean fromGoogle, boolean fromAI, @Nullable String aiModel);
    }

    private LyricsTranslator() {
    }

    public static String deviceLanguage() {
        return Locale.getDefault().getLanguage();
    }

    @Nullable
    private static List<String> embeddedTranslation(Lyrics lyrics, String target, int lineCount) {
        Map<String, List<LyricsLine>> byLang = lyrics.translations();
        if (byLang == null || byLang.isEmpty()) {
            return null;
        }
        String targetLang = primarySubtag(target);
        for (Map.Entry<String, List<LyricsLine>> entry : byLang.entrySet()) {
            if (!primarySubtag(entry.getKey()).equals(targetLang)) {
                continue;
            }
            List<LyricsLine> lines = entry.getValue();
            if (lines == null || lines.size() != lineCount || !LyricsMerge.hasText(lines)) {
                continue;
            }
            List<String> out = new ArrayList<>(lines.size());
            for (LyricsLine line : lines) {
                String text = line.text();
                out.add(text == null ? "" : text);
            }
            List<LyricsLine> allLines = lyrics.lines();
            for (int i = 0; i < out.size() && i < allLines.size(); i++) {
                if (allLines.get(i).isBG()) {
                    for (int j = i - 1; j >= 0; j--) {
                        if (!allLines.get(j).isBG() && j < out.size()) {
                            String parentTrans = out.get(j);
                            if (parentTrans != null && !parentTrans.isEmpty()) {
                                out.set(i, parentTrans);
                            }
                            break;
                        }
                    }
                }
            }
            return out;
        }
        return null;
    }

    private static String primarySubtag(String lang) {
        if (lang == null) {
            return "";
        }
        final int idx = lang.indexOf('-');
        return (idx >= 0 ? lang.substring(0, idx) : lang).toLowerCase(Locale.ROOT);
    }

    private static final String SYSTEM_PROMPT =
            "You are a translator. Output ONLY the translation. No reasoning, no explanations, no step-by-step thinking.";

    public static void translate(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(line.text());
        }

        String language = deviceLanguage();

        List<String> embedded = embeddedTranslation(lyrics, language, lines.size());
        if (embedded != null) {
            Utils.runOnMainThread(() -> callback.onTranslated(embedded, false, false, null));
            return;
        }

        executor.execute(() -> {
            if (Settings.LYRICS_USE_AI_TRANSLATION.get()) {
                String baseUrl = Settings.LYRICS_AI_BASE_URL.get();
                String apiToken = Settings.LYRICS_AI_API_TOKEN.get();
                String model = Settings.LYRICS_AI_MODEL.get();

                List<String> aiCached = LyricsCache.getTranslationAI(
                        track, source, language, lines.size());
                if (aiCached != null) {
                    Utils.runOnMainThread(() -> callback.onTranslated(aiCached, false, true, model));
                    return;
                }

                List<String> aiResult = aiTranslate(lines, language, track.title(),
                        track.artist(), baseUrl, apiToken, model);
                if (aiResult != null) {
                    LyricsCache.putTranslationAI(track, source, language, aiResult);
                    Utils.runOnMainThread(() -> callback.onTranslated(aiResult, false, true, model));
                    return;
                }
            }

            List<String> translated = LyricsCache.getTranslation(track, source, language, lines.size());
            if (translated == null) {
                translated = translateOnline(lines, language);
                if (translated != null) {
                    LyricsCache.putTranslation(track, source, language, translated);
                }
            }

            List<String> result = translated;
            Utils.runOnMainThread(() -> callback.onTranslated(result, result != null, false, null));
        });
    }

    @Nullable
    private static List<String> aiTranslate(List<String> lines, String language,
            String title, String artist, String baseUrl, String apiToken, String model) {
        int totalChars = 0;
        for (String line : lines) {
            totalChars += line.length() + 1;
        }
        if (totalChars > OpenAIClient.getMaxChars()) {
            return null;
        }
        String prompt = buildTranslatePrompt(lines, language, title, artist);
        String response = OpenAIClient.request(baseUrl, apiToken, model,
                prompt, SYSTEM_PROMPT);
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
            result[i] = OpenAIClient.stripLineNumber(result[i]);
            if (!result[i].trim().equalsIgnoreCase("SKIP")) {
                allSkip = false;
            }
        }
        if (allSkip) {
            return null;
        }
        if (end > lines.size() + 2 || end < lines.size() - 2) {
            return null;
        }
        List<String> out = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            if (i < end) {
                String trimmed = result[i].trim();
                if (trimmed.equalsIgnoreCase("SKIP") || OpenAIClient.isNoteOrEmptyLine(lines.get(i))) {
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

    private static String buildTranslatePrompt(List<String> lines, String targetLang,
            String title, String artist) {
        StringBuilder sb = new StringBuilder(200 + lines.size() * 50);
        sb.append("You are a professional lyrics translator. Translate each line below to ")
                .append(targetLang).append(", preserving the emotional tone and poetic style of the original.\n");
        sb.append("Song: ").append(title).append(" by ").append(artist).append("\n\n");
        sb.append("Output format: Number every translated line, like:\n");
        sb.append("1. first translation\n");
        sb.append("2. second translation\n\n");
        sb.append("CRITICAL RULES:\n");
        sb.append("- Output exactly ").append(lines.size()).append(" numbered lines (same as input count)\n");
        sb.append("- Number format: \"N. translated text\" (number, period, space, text)\n");
        sb.append("- Do NOT include the original lyrics in your output\n");
        sb.append("- Do NOT use \"Line N:\" format\n");
        sb.append("- If the lyrics are already in ").append(targetLang)
                .append(", output one \"SKIP\" per line\n\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    @Nullable
    private static List<String> translateOnline(List<String> lines, String language) {
        return LyricsMerge.mapLinesOnline(
                lines, b -> {
                    try {
                        return TextTranslator.translate(b, language);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }
}
