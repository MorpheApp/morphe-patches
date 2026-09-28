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

import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
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

    private static String translationLanguage() {
        String language = Settings.LYRICS_TRANSLATION_LANGUAGE.get();
        return "DEFAULT".equalsIgnoreCase(language)
                ? LyricsRequests.deviceLanguage()
                : language.toLowerCase(Locale.ROOT);
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

    public static void translate(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            lines.add(line.text());
        }

        String language = translationLanguage();

        List<String> embedded = embeddedTranslation(lyrics, language, lines.size());
        if (embedded != null) {
            Utils.runOnMainThread(() -> callback.onTranslated(embedded, false, false, null));
            return;
        }

        executor.execute(() -> {
            try {
                if (Settings.LYRICS_USE_AI_TRANSLATION.get()) {
                    String baseUrl = Settings.LYRICS_AI_BASE_URL.get();
                    String apiToken = Settings.LYRICS_AI_API_TOKEN.get();
                    String model = Settings.LYRICS_AI_MODEL.get();

                    List<String> aiCached = LyricsCache.getTranslationAI(
                            track, source, language, lines);
                    if (aiCached != null) {
                        Utils.runOnMainThread(
                                () -> callback.onTranslated(aiCached, false, true, model));
                        return;
                    }

                    List<String> aiResult = aiTranslate(lines, language, track.title(),
                            track.artist(), baseUrl, apiToken, model);
                    if (aiResult != null) {
                        LyricsCache.putTranslationAI(track, source, language, lines, aiResult);
                        Utils.runOnMainThread(
                                () -> callback.onTranslated(aiResult, false, true, model));
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
        String prompt = OpenAIClient.renderPrompt(Settings.LYRICS_AI_PROMPT.get(),
                "translation", language, title, artist, lines);
        return OpenAIClient.mapLines(baseUrl, apiToken, model, prompt, null, lines);
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
