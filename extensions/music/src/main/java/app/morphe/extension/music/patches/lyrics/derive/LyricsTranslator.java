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
import app.morphe.extension.shared.Logger;
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

    private static List<LyricsLine> toLyricsLines(List<String> textLines) {
        if (textLines == null) return java.util.Collections.emptyList();
        List<LyricsLine> lines = new ArrayList<>(textLines.size());
        for (String t : textLines) {
            lines.add(new LyricsLine(LyricsLine.NO_TIME, t));
        }
        return lines;
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
                String provider = Settings.LYRICS_TRANSLATION_PROVIDER.get();
                if (!AiProviders.GOOGLE.equals(provider)) {
                    List<String> aiCached = LyricsCache.getTranslationAI(
                            track, source, language, lines);
                    if (aiCached != null) {
                        Utils.runOnMainThread(() -> callback.onTranslated(aiCached, false, true, provider));
                        return;
                    }

                    AiProviders.Result ai = AiProviders.DEEPL.equals(provider)
                            ? deepLTranslate(lines, language)
                            : AiProviders.mapLines(provider, OpenAIClient.renderPrompt(
                                    Settings.LYRICS_AI_TRANSLATION_PROMPT.get(), "translation",
                                    language, track.title(), track.artist(), lines), lines);
                    if (ai != null && ai.lines() != null && !ai.lines().isEmpty() && LyricsMerge.hasText(toLyricsLines(ai.lines()))) {
                        LyricsCache.putTranslationAI(track, source, language, lines, ai.lines());
                        Utils.runOnMainThread(() -> callback.onTranslated(ai.lines(), false, true, ai.model()));
                        return;
                    }
                    // AI / DeepL request failed or returned empty; gracefully fall through to Google Translate.
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
            } catch (Exception ex) {
                Logger.printException(() -> "translate failure", ex);
                Utils.runOnMainThread(() -> callback.onTranslated(null, false, false, null));
            }
        });
    }

    @Nullable
    private static AiProviders.Result deepLTranslate(List<String> lines, String language) {
        String apiKey = Settings.LYRICS_DEEPL_API_KEY.get();
        if (apiKey.isEmpty()) {
            return null;
        }
        List<String> translated = DeepLClient.translate(lines, language, apiKey);
        return translated == null ? null : new AiProviders.Result(translated, "DeepL");
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
