/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsMerge;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
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

    @Nullable
    private static List<String> embeddedTranslation(Lyrics lyrics, String target, int lineCount) {
        Map<String, List<LyricsLine>> byLang = lyrics.translations();
        if (byLang == null || byLang.isEmpty()) {
            return null;
        }
        String targetLang = LyricsRequests.primarySubtag(target);
        final String targetScript = LyricsRequests.resolvedScript(target);
        for (Map.Entry<String, List<LyricsLine>> entry : byLang.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith(LyricsMerge.BACKGROUND_TRACK_PREFIX)) {
                continue;
            }
            if (!LyricsRequests.primarySubtag(key).equals(targetLang)) {
                continue;
            }
            if (!LyricsRequests.resolvedScript(key).equals(targetScript)) {
                continue;
            }
            List<LyricsLine> lines = entry.getValue();
            if (lines == null || lines.size() != lineCount || !LyricsMerge.hasText(lines)) {
                continue;
            }
            return embeddedTranslationLines(lyrics, lines,
                    LyricsMerge.backgroundTrack(byLang, key));
        }
        return null;
    }

    /** Elsewhere the fallback would put the sung line's own words under different ones. */
    private static List<String> embeddedTranslationLines(Lyrics lyrics, List<LyricsLine> lines,
                                                          @Nullable List<LyricsLine> backgroundLines) {
        List<String> out = new ArrayList<>(lines.size());
        for (LyricsLine line : lines) {
            String text = line.text();
            out.add(text == null ? "" : text);
        }
        List<LyricsLine> allLines = lyrics.lines();
        final boolean fallback = LyricsMerge.hasBackgroundTrack(lyrics, lyrics.translations());
        for (int i = 0; i < out.size() && i < allLines.size(); i++) {
            if (!allLines.get(i).isBG()) {
                continue;
            }
            final String own = LyricsMerge.textAt(backgroundLines, i);
            if (!own.isEmpty()) {
                out.set(i, own);
                continue;
            }
            if (!fallback) {
                continue;
            }
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
        return out;
    }

    public static void translate(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(line.text());
        }

        String language = LyricsRequests.translationLanguage();

        List<String> embedded = embeddedTranslation(lyrics, language, lines.size());
        if (embedded != null) {
            Utils.runOnMainThread(() -> callback.onTranslated(embedded, false, false, null));
            return;
        }

        executor.execute(() -> {
            try {
                String provider = Settings.LYRICS_TRANSLATION_PROVIDER.get().toLowerCase(Locale.ROOT);
                boolean useAiSetting = Settings.LYRICS_USE_AI_TRANSLATION.get();

                if (!"google".equals(provider) || useAiSetting) {
                    final String effectiveProvider = ("google".equals(provider) && useAiSetting)
                            ? "openai_compatible"
                            : provider;

                    List<String> aiCached = LyricsCache.getTranslationAI(
                            track, source, language, lines);
                    if (aiCached != null) {
                        Utils.runOnMainThread(() -> callback.onTranslated(aiCached, false, true, effectiveProvider));
                        return;
                    }

                    List<String> aiResult = null;
                    String modelUsed = null;

                    switch (effectiveProvider) {
                        case "gemini": {
                            String apiKey = Settings.LYRICS_GEMINI_API_KEY.get();
                            String model = Settings.LYRICS_GEMINI_MODEL.get();
                            if (!apiKey.isEmpty()) {
                                aiResult = GeminiClient.translate(lines, language, track.title(), track.artist(), apiKey, model);
                                modelUsed = model;
                            }
                            break;
                        }
                        case "openrouter": {
                            String apiKey = Settings.LYRICS_OPENROUTER_API_KEY.get();
                            String model = Settings.LYRICS_OPENROUTER_MODEL.get();
                            if (!apiKey.isEmpty()) {
                                aiResult = aiTranslate(lines, language, track.title(), track.artist(),
                                        "https://openrouter.ai/api/v1/chat/completions", apiKey, model);
                                modelUsed = model;
                            }
                            break;
                        }
                        case "openai": {
                            String apiKey = Settings.LYRICS_OPENAI_API_KEY.get();
                            String model = Settings.LYRICS_OPENAI_MODEL.get();
                            if (!apiKey.isEmpty()) {
                                aiResult = aiTranslate(lines, language, track.title(), track.artist(),
                                        "https://api.openai.com/v1/chat/completions", apiKey, model);
                                modelUsed = model;
                            }
                            break;
                        }
                        case "openai_compatible": {
                            String baseUrl = Settings.LYRICS_AI_BASE_URL.get();
                            String apiToken = Settings.LYRICS_AI_API_TOKEN.get();
                            String model = Settings.LYRICS_AI_MODEL.get();
                            aiResult = aiTranslate(lines, language, track.title(), track.artist(), baseUrl, apiToken, model);
                            modelUsed = model;
                            break;
                        }
                        case "deepl": {
                            String apiKey = Settings.LYRICS_DEEPL_API_KEY.get();
                            if (!apiKey.isEmpty()) {
                                aiResult = DeepLClient.translate(lines, language, apiKey);
                                modelUsed = "DeepL";
                            }
                            break;
                        }
                    }

                    boolean hasMeaningfulAi = false;
                    if (aiResult != null && !aiResult.isEmpty()) {
                        for (int i = 0; i < aiResult.size(); i++) {
                            String l = aiResult.get(i);
                            if (isSkipTranslation(l)) {
                                aiResult.set(i, "");
                            } else {
                                hasMeaningfulAi = true;
                            }
                        }
                    }

                    if (hasMeaningfulAi) {
                        LyricsCache.putTranslationAI(track, source, language, lines, aiResult);
                        final List<String> finalAiResult = aiResult;
                        final String finalModel = modelUsed;
                        Utils.runOnMainThread(() -> callback.onTranslated(finalAiResult, false, true, finalModel));
                        return;
                    } else if (aiResult != null) {
                        Utils.runOnMainThread(() -> callback.onTranslated(null, false, false, null));
                        return;
                    }
                }

                List<String> translated = LyricsCache.getTranslation(
                        track, source, language, lines);
                if (translated == null) {
                    translated = translateOnline(lines, language);
                    if (translated != null) {
                        LyricsCache.putTranslation(track, source, language, lines, translated);
                    }
                }

                List<String> result = translated;
                Utils.runOnMainThread(
                        () -> callback.onTranslated(result, result != null, false, null));
            } catch (Throwable ignored) {
                Utils.runOnMainThread(() -> callback.onTranslated(null, false, false, null));
            }
        });
    }

    @Nullable
    private static List<String> aiTranslate(List<String> lines, String language,
            String title, String artist, String baseUrl, String apiToken, String model) {
        String prompt = OpenAIClient.renderPrompt(Settings.LYRICS_AI_TRANSLATION_PROMPT.get(),
                "translation", language, title, artist, lines);
        return OpenAIClient.mapLines(baseUrl, apiToken, model, prompt, null, lines);
    }

    public static String buildTranslatePrompt(List<String> lines, String targetLang,
            String title, String artist) {
        StringBuilder sb = new StringBuilder(200 + lines.size() * 50);
        sb.append("Translate each line below to ").append(targetLang)
                .append(", preserving the poetic style and meaning.\n");
        sb.append("Song: ").append(title).append(" by ").append(artist).append("\n\n");
        sb.append("Rules:\n");
        sb.append("- Output exactly ").append(lines.size()).append(" numbered lines (format: 1. <translated text>)\n");
        sb.append("- Do not include the original lyrics\n");
        sb.append("- If already in ").append(targetLang).append(", output SKIP\n\n");
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            String clean = (l != null) ? l.trim() : "";
            sb.append(i + 1).append(". ").append(clean.isEmpty() ? "SKIP" : clean).append("\n");
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

    public static boolean isSkipTranslation(@Nullable String s) {
        if (s == null) return true;
        String t = s.trim();
        return t.isEmpty() || t.equalsIgnoreCase("SKIP") || t.equalsIgnoreCase("[SKIP]")
                || t.equalsIgnoreCase("<SKIP>") || t.equalsIgnoreCase("(SKIP)")
                || t.equalsIgnoreCase("SKIP.");
    }
}
