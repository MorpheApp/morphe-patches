/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
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

import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

public final class LyricsRomanizer {

    public interface Callback {
        void onRomanized(@Nullable List<LyricsLine> romanizedLines,
                         boolean fromGoogle, boolean fromAI, @Nullable String aiModel,
                         boolean perWord);
    }

    private static final String SYSTEM_PROMPT =
            "You are a romanization specialist. Output ONLY the romanized text. No reasoning, no explanations, no step-by-step thinking.";

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsRomanizer() {
    }

    public static void romanize(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        List<LyricsLine> embedded = lyrics.romanization();
        if (embedded == null || embedded.isEmpty()) {
            Map<String, List<LyricsLine>> romanizations = lyrics.romanizations();
            if (romanizations != null && !romanizations.isEmpty()) {
                embedded = collectMatchingRomanizations(romanizations, lyrics.lines());
            }
        }

        final boolean perWord = LyricsMerge.anyWordHasRomaji(lyrics.lines());
        if (LyricsMerge.hasText(embedded) || perWord) {
            List<LyricsLine> result = embedded;
            Utils.runOnMainThread(() -> callback.onRomanized(result, false, false, null, perWord));
            return;
        }

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            String text = line.text();
            lines.add(text != null ? text : "");
        }

        executor.execute(() -> {
            if (Settings.LYRICS_USE_AI_TRANSLATION.get()) {
                String baseUrl = Settings.LYRICS_AI_BASE_URL.get();
                String apiToken = Settings.LYRICS_AI_API_TOKEN.get();
                String model = Settings.LYRICS_AI_MODEL.get();

                List<LyricsLine> aiCached = LyricsCache.getRomanizationAI(
                        track, source, lines.size());
                if (aiCached != null) {
                    Utils.runOnMainThread(() -> callback.onRomanized(aiCached, false, true, model, false));
                    return;
                }

                List<String> aiResult = aiRomanize(lines, deviceLanguage(), track.title(),
                        track.artist(), baseUrl, apiToken, model);
                if (aiResult != null) {
                    List<LyricsLine> aiLines = toLines(aiResult);
                    LyricsCache.putRomanizationAI(track, source, aiLines);
                    Utils.runOnMainThread(() -> callback.onRomanized(aiLines, false, true, model, false));
                    return;
                }
            }

            List<LyricsLine> romanized = LyricsCache.getRomanization(track, source, lines.size());
            if (romanized == null) {
                List<String> romanizedText = romanizeOnline(lines);
                if (romanizedText != null) {
                    romanized = toLines(romanizedText);
                    LyricsCache.putRomanization(track, source, romanized);
                }
            }

            List<LyricsLine> result = romanized;
            Utils.runOnMainThread(() -> callback.onRomanized(result, result != null, false, null, false));
        });
    }

    @Nullable
    private static List<String> aiRomanize(List<String> lines, String targetLanguage,
            String title, String artist, String baseUrl, String apiToken, String model) {
        return OpenAIClient.mapLines(baseUrl, apiToken, model,
                buildRomanizePrompt(lines, targetLanguage, title, artist), SYSTEM_PROMPT, lines);
    }

    private static String buildRomanizePrompt(List<String> lines, String targetLang,
            String title, String artist) {
        StringBuilder sb = new StringBuilder(200 + lines.size() * 50);
        sb.append("You are a professional romanization specialist. Convert each line below to ")
                .append(targetLang).append(" Latin script.\n");
        sb.append("Song: ").append(title).append(" by ").append(artist).append("\n\n");
        sb.append("Output format: Number every romanized line, like:\n");
        sb.append("1. first romanization\n");
        sb.append("2. second romanization\n\n");
        sb.append("CRITICAL RULES:\n");
        sb.append("- Output exactly ").append(lines.size()).append(" numbered lines (same as input count)\n");
        sb.append("- Number format: \"N. romanized text\" (number, period, space, text)\n");
        sb.append("- Do NOT include the original lyrics in your output\n");
        sb.append("- Do NOT use \"Line N:\" format\n");
        sb.append("- Use standard ").append(targetLang).append(" romanization without tone marks\n");
        sb.append("- If the lyrics are already in Latin script, output one \"SKIP\" per line\n\n");
        for (String line : lines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    private static List<LyricsLine> collectMatchingRomanizations(
            Map<String, List<LyricsLine>> romanizations, List<LyricsLine> allLines) {
        String langTag = Locale.getDefault().toLanguageTag();
        String langCode = langTag.contains("-")
                ? langTag.substring(0, langTag.indexOf("-")) : langTag;

        List<String> matchedKeys = new ArrayList<>();
        for (String key : romanizations.keySet()) {
            if (key.startsWith("bg:")) continue;
            if (key.equals(langTag) || key.equals(langCode)
                    || key.startsWith(langCode + "-") || key.startsWith(langCode + "_")) {
                matchedKeys.add(key);
            }
        }

        if (matchedKeys.isEmpty()) {
            for (String key : romanizations.keySet()) {
                if (!key.startsWith("bg:")) {
                    matchedKeys.add(key);
                }
            }
        }

        if (matchedKeys.isEmpty()) {
            return null;
        }

        final int lineCount;
        {
            List<LyricsLine> first = romanizations.get(matchedKeys.get(0));
            lineCount = (first != null) ? first.size() : 0;
        }
        if (lineCount == 0) {
            return null;
        }

        List<LyricsLine> result = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            StringBuilder merged = new StringBuilder();
            for (String key : matchedKeys) {
                List<LyricsLine> langLines = romanizations.get(key);
                if (langLines == null || i >= langLines.size()) continue;
                String text = langLines.get(i).text();
                if (text == null) continue;
                text = text.trim();
                if (!text.isEmpty()) {
                    if (merged.length() > 0) merged.append('\n');
                    merged.append(text);
                }
            }
            result.add(new LyricsLine(LyricsLine.NO_TIME, merged.toString()));
        }

        if (allLines != null) {
            for (int i = 0; i < result.size() && i < allLines.size(); i++) {
                if (allLines.get(i).isBG()) {
                    String bgRoma = result.get(i).text();
                    if (bgRoma == null || bgRoma.isEmpty()) {
                        for (int j = i - 1; j >= 0; j--) {
                            if (!allLines.get(j).isBG() && j < result.size()) {
                                String parentRoma = result.get(j).text();
                                if (parentRoma != null && !parentRoma.isEmpty()) {
                                    result.set(i, new LyricsLine(LyricsLine.NO_TIME, parentRoma));
                                }
                                break;
                            }
                        }
                    }
                }
            }
        }

        return result;
    }

    private static List<LyricsLine> toLines(List<String> texts) {
        List<LyricsLine> result = new ArrayList<>(texts.size());
        for (String text : texts) {
            if (text == null || text.equals("null")) {
                text = "";
            }
            result.add(new LyricsLine(LyricsLine.NO_TIME, text));
        }
        return result;
    }

    @Nullable
    private static List<String> romanizeOnline(List<String> lines) {
        return LyricsMerge.mapLinesOnline(lines,
                l -> {
                    try {
                        return TextTranslator.romanize(l);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }

    public static String deviceLanguage() {
        return LyricsRequests.deviceLanguage();
    }
}
