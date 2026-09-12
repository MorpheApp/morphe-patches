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

import app.morphe.extension.music.patches.lyrics.LyricsLine;
import app.morphe.extension.music.patches.lyrics.LyricsMerge;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

/**
 * Romanizes (transliterates to Latin script) the lyrics line by line, so the pronunciation
 * of non-Latin scripts can be shown above each line.
 */
public final class LyricsRomanizer {

    public interface Callback {
        /**
         * Called on the main thread with one romanized line per original line,
         * or {@code null} if the romanization failed.
         *
         * @param fromGoogle {@code true} when the result came from Google, in which case
         *                   the UI may attribute it; {@code false} for the embedded source.
         * @param perWord    {@code true} when the source ships per-word romanization (carried
         *                   on each {@link Word}); the UI should render it above each word.
         */
        void onRomanized(@Nullable List<LyricsLine> romanizedLines, boolean fromGoogle, boolean perWord);
    }

    /** Separate from the lyrics executor, so a romanization never delays a lyrics lookup. */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private LyricsRomanizer() {
    }

    /**
     * Romanizes the lyrics of a track, preferring an embedded romanization and falling back
     * to Google (with the on-disk cache) when the source provides none.
     */
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
            // The source already ships an aligned (line-level or per-word) romanization: no network needed.
            final List<LyricsLine> result = embedded;
            Utils.runOnMainThread(() -> callback.onRomanized(result, false, perWord));
            return;
        }

        List<String> lines = new ArrayList<>(lyrics.lines().size());
        for (LyricsLine line : lyrics.lines()) {
            String text = line.text();
            lines.add(text != null ? text : "");
        }

        executor.execute(() -> {
            List<LyricsLine> romanized = LyricsCache.getRomanization(track, source, lines.size());
            if (romanized == null) {
                List<String> romanizedText = romanizeOnline(lines);
                if (romanizedText != null) {
                    romanized = toLines(romanizedText);
                    LyricsCache.putRomanization(track, source, romanized);
                }
            }

            final List<LyricsLine> result = romanized;
            Utils.runOnMainThread(() -> callback.onRomanized(result, true, false));
        });
    }

    private static List<LyricsLine> collectMatchingRomanizations(
            Map<String, List<LyricsLine>> romanizations, List<LyricsLine> allLines) {
        final String langTag = Locale.getDefault().toLanguageTag();
        final String langCode = langTag.contains("-")
                ? langTag.substring(0, langTag.indexOf("-")) : langTag;

        final List<String> matchedKeys = new ArrayList<>();
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

        // Find the line count from the first matching language
        final int lineCount;
        {
            List<LyricsLine> first = romanizations.get(matchedKeys.get(0));
            lineCount = (first != null) ? first.size() : 0;
        }
        if (lineCount == 0) {
            return null;
        }

        // Merge multi-language romanizations line by line
        final List<LyricsLine> result = new ArrayList<>(lineCount);
        for (int i = 0; i < lineCount; i++) {
            final StringBuilder merged = new StringBuilder();
            for (String key : matchedKeys) {
                List<LyricsLine> langLines = romanizations.get(key);
                if (langLines == null || i >= langLines.size()) continue;
                String text = langLines.get(i).text();
                if (text != null) text = text.trim();
                if (!text.isEmpty()) {
                    if (merged.length() > 0) merged.append('\n');
                    merged.append(text);
                }
            }
            result.add(new LyricsLine(LyricsLine.NO_TIME, merged.toString()));
        }

        // Fill BG lines with their parent's romanization
        if (allLines != null) {
            for (int i = 0; i < result.size() && i < allLines.size(); i++) {
                if (allLines.get(i).isBG()) {
                    final String bgRoma = result.get(i).text();
                    if (bgRoma == null || bgRoma.isEmpty()) {
                        for (int j = i - 1; j >= 0; j--) {
                            if (!allLines.get(j).isBG() && j < result.size()) {
                                final String parentRoma = result.get(j).text();
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

    /**
     * @return One line per input line, or {@code null} if any batch failed or came
     * back with a different number of lines than it was given.
     */
    @Nullable
    private static List<String> romanizeOnline(List<String> lines) {
        return LyricsMerge.mapLinesOnline(lines,
                l -> {
                    try {
                        return TextTranslator.romanize(l);
                    } catch (Exception ex) {
                        throw new RuntimeException(ex);
                    }
                }, "romanization", "romanize");
    }
}
