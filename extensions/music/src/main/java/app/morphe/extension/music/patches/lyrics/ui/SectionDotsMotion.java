/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public final class SectionDotsMotion {
    /** Below this a silence is breathing room, not a break. */
    public static final long MIN_GAP_MS = 3000L;
    private static final long INFERRED_FLOOR_MS = 1200L;
    private static final long INFERRED_MS_PER_CHAR = 90L;
    /** Caps the guess so a long line cannot reach into the next one. */
    private static final int INFERRED_MAX_CHARS = 40;
    private static final long INFERRED_CEILING_MS = 5000L;

    private SectionDotsMotion() {
    }

    /**
     * Finds every silence long enough to play the dots, in line order. A gap belongs to the line
     * after it, which is the row the dots stand on; the silence before the first line counts too.
     *
     * @return The gaps to animate, empty when there are none.
     */
    @NonNull
    public static List<Gap> computeGaps(@Nullable List<LyricsLine> lines) {
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }
        List<Gap> gaps = null;
        // Only the first line: a later one starting late is an ordinary silence, measured
        // from the line before it in the loop below.
        int firstIndex = 0;
        while (firstIndex < lines.size() && lines.get(firstIndex) == null) {
            firstIndex++;
        }
        if (firstIndex < lines.size()) {
            final LyricsLine first = lines.get(firstIndex);
            if (first.startTimeMs() >= MIN_GAP_MS) {
                gaps = new ArrayList<>(4);
                gaps.add(new Gap(firstIndex, 0L, first.startTimeMs()));
            }
        }
        int previousLead = -1;
        for (int i = 0; i < lines.size(); i++) {
            final LyricsLine line = lines.get(i);
            if (line == null || line.isBG()) {
                continue;
            }
            if (previousLead >= 0) {
                final long gapStart = effectiveEndMs(lines.get(previousLead));
                final long gapEnd = line.startTimeMs();
                if (gapStart > 0 && gapEnd > gapStart && gapEnd - gapStart >= MIN_GAP_MS) {
                    if (gaps == null) {
                        gaps = new ArrayList<>(4);
                    }
                    gaps.add(new Gap(i, gapStart, gapEnd));
                }
            }
            previousLead = i;
        }
        return gaps == null ? List.of() : Collections.unmodifiableList(gaps);
    }

    /** The end of a line: its own, else its last word's, else a guess from the text. */
    private static long effectiveEndMs(@Nullable LyricsLine line) {
        if (line == null) {
            return LyricsLine.NO_TIME;
        }
        final long end = line.endTimeMs();
        if (end > line.startTimeMs()) {
            return end;
        }
        final List<Word> words = line.words();
        for (int i = words.size() - 1; i >= 0; i--) {
            final long wordEnd = words.get(i).endMs();
            if (wordEnd > line.startTimeMs()) {
                return wordEnd;
            }
        }
        final long inferred = inferredEndMs(line);
        return inferred > line.startTimeMs() ? inferred : LyricsLine.NO_TIME;
    }

    /**
     * Guesses where a start-only line stops being sung, scaled by its text. Without it an LRC gap
     * could never be measured. Never written back, so the original timing still drives everything.
     */
    private static long inferredEndMs(LyricsLine line) {
        final int chars = Math.min(line.text().length(), INFERRED_MAX_CHARS);
        if (chars <= 0) {
            return LyricsLine.NO_TIME;
        }
        return line.startTimeMs() + Math.min(
                INFERRED_FLOOR_MS + (long) chars * INFERRED_MS_PER_CHAR,
                INFERRED_CEILING_MS);
    }

    /**
     * A silence long enough to play the dots, anchored on the line that follows it.
     *
     * @param tagLineIndex The line the motion plays on, which is where its label would sit.
     * @param gapStartMs   Where the previous line stops, or 0 for a silence before the first line.
     */
    public record Gap(int tagLineIndex, long gapStartMs, long gapEndMs) {
    }

}
