/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
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
import app.morphe.extension.music.patches.lyrics.romanizers.LocalRomanizer;
import app.morphe.extension.music.patches.lyrics.storage.LyricsCache;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

public final class LyricsRomanizer {

    public interface Callback {
        void onRomanized(@Nullable List<LyricsLine> romanizedLines,
                         boolean fromGoogle, boolean fromAI, @Nullable String aiModel,
                         boolean perWord, @Nullable String localDictionary);
    }

    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsRomanizer() {
    }

    public static void romanize(TrackInfo track, Lyrics lyrics, String source, Callback callback) {
        Utils.verifyOnMainThread();

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            String text = line.text();
            lines.add(text != null ? text : "");
        }

        executor.execute(() -> {
            try {
                // A romanization is read by line index, so one without an entry for every
                // line describes a different lyric and would put a reading under the wrong
                // line. It is dropped so the line is romanized again.
                final int lineCount = lyrics.lines().size();
                List<LyricsLine> embedded = lyrics.romanization();
                if (embedded != null && embedded.size() != lineCount) {
                    embedded = null;
                }
                if (embedded == null || embedded.isEmpty()) {
                    Map<String, List<LyricsLine>> romanizations = lyrics.romanizations();
                    if (romanizations != null && !romanizations.isEmpty()) {
                        embedded = collectMatchingRomanizations(romanizations, lyrics.lines(),
                                LyricsMerge.hasBackgroundTrack(lyrics, romanizations));
                    }
                }

                final boolean perWord = LyricsMerge.anyWordHasRomaji(lyrics.lines());
                if (LyricsMerge.hasText(embedded) || perWord) {
                    List<LyricsLine> result = embedded;
                    Utils.runOnMainThread(
                            () -> callback.onRomanized(result, false, false, null, perWord, null));
                    return;
                }

                if (Settings.LYRICS_USE_AI_ROMANIZATION.get()) {
                    String baseUrl = Settings.LYRICS_AI_BASE_URL.get();
                    String apiToken = Settings.LYRICS_AI_API_TOKEN.get();
                    String model = Settings.LYRICS_AI_MODEL.get();

                    List<LyricsLine> aiCached = LyricsCache.getRomanizationAI(
                            track, source, lines);
                    if (aiCached != null) {
                        publish(callback, lyrics, aiCached, false, true, model, null);
                        return;
                    }

                    List<String> aiResult = aiRomanize(lines, LyricsRequests.romanizationLanguage(), track.title(),
                            track.artist(), baseUrl, apiToken, model);
                    if (aiResult != null) {
                        List<LyricsLine> aiLines = toLines(aiResult);
                        LyricsCache.putRomanizationAI(track, source, lines, aiLines);
                        publish(callback, lyrics, aiLines, false, true, model, null);
                        return;
                    }
                }

                List<LyricsLine> romanized = LyricsCache.getRomanization(track, source, lines);
                boolean online = romanized != null;
                if (romanized == null) {
                    List<String> romanizedText = romanizeOnline(lines);
                    if (romanizedText != null) {
                        romanized = toLines(romanizedText);
                        LyricsCache.putRomanization(track, source, lines, romanized);
                        online = true;
                    }
                }

                // The last resort. Its reading is not stored, so an online one is used again
                // as soon as there is one.
                LocalRomanizer.Result local = romanized == null
                        && Settings.LYRICS_LOCAL_ROMANIZATION.get()
                        ? LocalRomanizer.romanize(lines) : null;

                final List<LyricsLine> wholeLine = local != null ? toLines(local.lines) : romanized;
                final boolean fromGoogle = online && local == null && wholeLine != null;
                publish(callback, lyrics, wholeLine, fromGoogle, false, null,
                        local != null ? local.dictionary : null);
            } catch (Throwable ex) {
                Logger.printException(() -> "romanize failure", ex);
                Utils.runOnMainThread(
                        () -> callback.onRomanized(null, false, false, null, false, null));
            }
        });
    }

    /**
     * The one place a reading leaves, so every source is offered word alignment alike: the word
     * timings are the source's either way, leaving the reading as the only part to be matched.
     */
    private static void publish(Callback callback, Lyrics lyrics,
            @Nullable List<LyricsLine> readings, boolean fromGoogle, boolean fromAI,
            @Nullable String aiModel, @Nullable String localDictionary) {
        final List<LyricsLine> aligned = readings != null
                && Settings.LYRICS_LOCAL_ROMANIZATION.get() && Settings.LYRICS_WORD_SYNC.get()
                ? LocalRomanizer.alignWords(lyrics.lines(), readings) : null;
        final List<LyricsLine> result = aligned != null ? aligned : readings;
        Utils.runOnMainThread(() -> callback.onRomanized(result, fromGoogle, fromAI, aiModel,
                aligned != null, localDictionary));
    }

    @Nullable
    private static List<String> aiRomanize(List<String> lines, String targetLanguage,
            String title, String artist, String baseUrl, String apiToken, String model) {
        String prompt = OpenAIClient.renderPrompt(Settings.LYRICS_AI_ROMANIZATION_PROMPT.get(),
                "romanization", targetLanguage, title, artist, lines);
        return OpenAIClient.mapLines(baseUrl, apiToken, model, prompt, lines);
    }

    private static List<LyricsLine> collectMatchingRomanizations(
            Map<String, List<LyricsLine>> romanizations, List<LyricsLine> allLines,
            boolean fallbackToSungLine) {
        // Keys are xml:lang from the file, with "romaji" standing in for a sidecar that named none.
        String langTag = LyricsRequests.isDefaultRomanizationLanguage()
                ? Locale.getDefault().toLanguageTag()
                : LyricsRequests.romanizationLanguage();
        String langCode = LyricsRequests.primarySubtag(langTag);

        List<String> matchedKeys = new ArrayList<>();
        for (String key : romanizations.keySet()) {
            if (key.startsWith(LyricsMerge.BACKGROUND_TRACK_PREFIX)) continue;
            if (key.equals(langTag) || key.equals(langCode)
                    || key.startsWith(langCode + "-") || key.startsWith(langCode + "_")) {
                matchedKeys.add(key);
            }
        }

        if (matchedKeys.isEmpty()) {
            for (String key : romanizations.keySet()) {
                if (!key.startsWith(LyricsMerge.BACKGROUND_TRACK_PREFIX)) {
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
                    //noinspection SizeReplaceableByIsEmpty
                    if (merged.length() > 0) merged.append('\n');
                    merged.append(text);
                }
            }
            result.add(new LyricsLine(LyricsLine.NO_TIME, merged.toString()));
        }

        if (allLines != null) {
            for (int i = 0; i < result.size() && i < allLines.size(); i++) {
                if (!allLines.get(i).isBG()) {
                    continue;
                }
                String own = "";
                for (String key : matchedKeys) {
                    own = LyricsMerge.textAt(
                            LyricsMerge.backgroundTrack(romanizations, key), i);
                    if (!own.isEmpty()) {
                        break;
                    }
                }
                if (!own.isEmpty()) {
                    result.set(i, new LyricsLine(LyricsLine.NO_TIME, own));
                    continue;
                }
                if (!fallbackToSungLine) {
                    continue;
                }
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

        if (allLines == null || result.size() != allLines.size()) {
            return null;
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
        final String language = Settings.LYRICS_ROMANIZE_LANGUAGE.get();
        return LyricsMerge.mapLinesOnline(lines,
                l -> {
                    try {
                        return TextTranslator.romanize(l, language);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                });
    }

}
