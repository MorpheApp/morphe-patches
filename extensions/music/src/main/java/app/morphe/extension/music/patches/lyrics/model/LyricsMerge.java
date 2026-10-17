/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.translation.TextTranslator;

/**
 * Aligns a provider's embedded auxiliary lines (romanization) to the original lyrics lines,
 * the way the upstream scripts do it: every original line is matched with the auxiliary line
 * whose start time falls inside its window. The result always has one entry per original line
 * so it can be rendered by index, with an empty string where no auxiliary line lines up.
 */
public final class LyricsMerge {

    /** Only TTML states background lines separately, so only there does the prefix mean anything. */
    public static final String BACKGROUND_TRACK_PREFIX = "bg:";

    /** A sung line matches anywhere before the next one starts, which would let a background line
     *  take the following line's own. */
    private static final long BACKGROUND_MATCH_WINDOW_MS = 300L;

    private LyricsMerge() {
    }

    @Nullable
    public static List<LyricsLine> backgroundTrack(
            @Nullable Map<String, List<LyricsLine>> tracks, String languageKey) {
        return tracks == null ? null : tracks.get(BACKGROUND_TRACK_PREFIX + languageKey);
    }

    public static boolean hasBackgroundTrack(Lyrics lyrics,
                                            @Nullable Map<String, List<LyricsLine>> tracks) {
        if ("ttml".equals(lyrics.formatType())) {
            return true;
        }
        if (tracks == null) {
            return false;
        }
        for (String key : tracks.keySet()) {
            if (key.startsWith(BACKGROUND_TRACK_PREFIX)) {
                return true;
            }
        }
        return false;
    }

    public static String textAt(@Nullable List<LyricsLine> track, int index) {
        if (track == null || index < 0 || index >= track.size()) {
            return "";
        }
        final String text = track.get(index).text();
        return text == null ? "" : text.trim();
    }

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
        if (!hasText(lines)) {
            return null;
        }
        Map<String, List<LyricsLine>> map = new HashMap<>();
        map.put(lang, lines);
        return map;
    }

    @Nullable
    public static List<String> mapLinesOnline(List<String> lines,
                                       Function<List<String>, List<String>> batch) {
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
            Logger.printDebug(() -> "mapLinesOnline failure", ex);
            return null;
        }

        List<String> result = new ArrayList<>(lines.size());
        int next = 0;
        for (String line : lines) {
            result.add(line.isEmpty() ? "" : mapped.get(next++));
        }
        return result;
    }

    /**
     * The lyric with one more credit line on it, or unchanged when it already carries that exact
     * line or either half is missing.
     */
    @Nullable
    public static Lyrics addSongwriter(@Nullable Lyrics lyrics, @Nullable String entry) {
        if (lyrics == null || entry == null) {
            return lyrics;
        }
        List<String> songwriters = new ArrayList<>();
        List<String> existing = lyrics.songwriters();
        if (existing != null) {
            songwriters.addAll(existing);
        }
        if (songwriters.contains(entry)) {
            return lyrics;
        }
        songwriters.add(entry);
        return new Lyrics(lyrics.lines(), lyrics.providerName(), lyrics.synced(),
                lyrics.romanization(), lyrics.translations(), lyrics.romanizations(),
                songwriters, lyrics.rawFormat(), lyrics.formatType(), lyrics.sourceUrl(),
                lyrics.about());
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

        for (int i = 0, size = original.size(); i < size; i++) {
            LyricsLine orig = original.get(i);

            final long winStart = orig.startTimeMs();
            final long winEnd;
            if (orig.isBG()) {
                winEnd = winStart + BACKGROUND_MATCH_WINDOW_MS;
            } else {
                winEnd = windowEnd(original, i, winStart);
            }

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

    /** Lines sharing a start would leave an empty window, matching nothing and stranding an entry. */
    private static long windowEnd(List<LyricsLine> lines, int index, long winStart) {
        final long next = (index + 1 < lines.size())
                ? lines.get(index + 1).startTimeMs()
                : Long.MAX_VALUE;
        if (winStart == LyricsLine.NO_TIME || next > winStart) {
            return next;
        }
        for (int i = index + 1; i < lines.size(); i++) {
            final long start = lines.get(i).startTimeMs();
            if (start > winStart) {
                return start;
            }
        }
        return Long.MAX_VALUE;
    }
}
