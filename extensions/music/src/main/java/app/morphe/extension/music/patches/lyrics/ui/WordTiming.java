/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.Nullable;

/**
 * One word or syllable of a line: its offsets in the text, its time range, and the readings
 * drawn for it.
 *
 * @param hasTrailingSpace Whether a space follows the word, which a syllable reading keeps so
 *                         the readings of two words do not run together.
 * @param ruby             The furigana drawn above the word.
 * @param averaged         The line's own time spread evenly over its words, not the source's
 *                         word timings.
 */
public record WordTiming(int start, int end, long startMs, long endMs,
                         @Nullable String romaji, boolean hasTrailingSpace,
                         @Nullable String ruby, boolean averaged) {

    public WordTiming(int start, int end, long startMs, long endMs,
                      @Nullable String romaji, boolean hasTrailingSpace,
                      @Nullable String ruby) {
        this(start, end, startMs, endMs, romaji, hasTrailingSpace, ruby, false);
    }

    public WordTiming(int start, int end, long startMs, long endMs,
                      @Nullable String romaji, boolean hasTrailingSpace, boolean averaged) {
        this(start, end, startMs, endMs, romaji, hasTrailingSpace, null, averaged);
    }

    /** A reading ending in a space stands for the space after its word. */
    WordTiming(int start, int end, long startMs, long endMs, @Nullable String romaji) {
        this(start, end, startMs, endMs, romaji,
                romaji != null && romaji.endsWith(" "), null, false);
    }

    public WordTiming(int start, int end, long startMs, long endMs,
                      @Nullable String romaji, boolean hasTrailingSpace) {
        this(start, end, startMs, endMs, romaji, hasTrailingSpace, null, false);
    }
}
