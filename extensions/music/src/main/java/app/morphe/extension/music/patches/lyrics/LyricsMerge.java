/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

/**
 * Aligns a provider's embedded auxiliary lines (romanization) to the original lyrics lines,
 * the way the upstream scripts do it: every original line is matched with the auxiliary line
 * whose start time falls inside its window. The result always has one entry per original line
 * so it can be rendered by index, with an empty string where no auxiliary line lines up.
 */
public final class LyricsMerge {

    private LyricsMerge() {
    }

    /**
     * @param original  The displayed lyrics lines, in playback order.
     * @param auxiliary The auxiliary lines (romanization), time sorted, or {@code null}.
     * @return One line per original line, carrying the matched auxiliary text. The start time
     * of each entry is the original line's, so the romanization stays pinned above it.
     */
    /**
     * @return {@code true} when at least one line carries non-empty auxiliary text.
     */
    public static boolean hasText(@Nullable List<LyricsLine> lines) {
        if (lines == null) {
            return false;
        }
        for (LyricsLine line : lines) {
            if (line.text() != null && !line.text().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static boolean anyWordHasRomaji(@Nullable List<LyricsLine> lines) {
        if (lines == null) {
            return false;
        }
        for (LyricsLine line : lines) {
            for (Word word : line.words()) {
                if (word.romaji() != null && !word.romaji().isEmpty()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Nullable
    public static Map<String, List<LyricsLine>> singleLanguageTranslations(
            @Nullable List<LyricsLine> lines, String lang) {
        if (lines == null || !hasText(lines)) {
            return null;
        }
        final Map<String, List<LyricsLine>> map = new HashMap<>();
        map.put(lang, lines);
        return map;
    }

    @Nullable
    static List<String> mapLinesOnline(List<String> lines,
                                       Function<List<String>, List<String>> batch,
                                       String what, String verb) {
        if (!Utils.isNetworkConnected()) {
            return null;
        }

        List<String> toMap = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (!line.isEmpty()) {
                toMap.add(line);
            }
        }

        List<String> mapped = new ArrayList<>(toMap.size());
        try {
            for (List<String> b : TextTranslator.splitByCharacterBudget(
                    toMap, TextTranslator.MAXIMUM_BATCH_CHARACTERS)) {
                List<String> r = batch.apply(b);
                // A mismatched count cannot be mapped back safely, and showing lines under the
                // wrong lyrics is worse than showing no result at all.
                if (r.size() != b.size()) {
                    return null;
                }
                mapped.addAll(r);
            }
        } catch (Exception ex) {
            return null;
        }

        List<String> result = new ArrayList<>(lines.size());
        int next = 0;
        for (String line : lines) {
            result.add(line.isEmpty() ? "" : mapped.get(next++));
        }
        return result;
    }

    public static List<LyricsLine> mergeRomanization(List<LyricsLine> original,
                                                    @Nullable List<LyricsLine> auxiliary) {
        List<LyricsLine> result = new ArrayList<>(original.size());
        if (auxiliary == null || auxiliary.isEmpty()) {
            for (LyricsLine line : original) {
                result.add(new LyricsLine(line.startTimeMs(), ""));
            }
            return result;
        }

        List<LyricsLine> sorted = new ArrayList<>(auxiliary);
        sorted.sort(Comparator.comparingLong(LyricsLine::startTimeMs));
        int auxIdx = 0;
        final int auxCount = sorted.size();

        for (int i = 0; i < original.size(); i++) {
            LyricsLine orig = original.get(i);

            if (orig.isBG()) {
                result.add(new LyricsLine(orig.startTimeMs(), ""));
                continue;
            }

            long winStart = orig.startTimeMs();
            long winEnd = (i < original.size() - 1)
                    ? original.get(i + 1).startTimeMs()
                    : Long.MAX_VALUE;

            String matched = "";
            while (auxIdx < auxCount) {
                long auxStart = sorted.get(auxIdx).startTimeMs();
                if (auxStart < winStart - 500) {
                    auxIdx++;
                    continue;
                }
                if (auxStart >= winEnd) {
                    break;
                }
                matched = sorted.get(auxIdx).text();
                auxIdx++;
                break;
            }
            result.add(new LyricsLine(winStart, matched == null ? "" : matched));
        }
        return result;
    }
}
