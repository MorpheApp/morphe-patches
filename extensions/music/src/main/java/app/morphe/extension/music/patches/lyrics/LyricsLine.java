/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;

import java.util.Collections;
import java.util.List;

/**
 * A single lyrics line.
 *
 * @param startTimeMs Start time in milliseconds, or {@link #NO_TIME} for unsynced lyrics.
 * @param text        Full line text. For word synced lines it is the concatenation of
 *                    every {@link #words()} entry, so translation and copy stay correct.
 * @param words       Word level timing, empty when only the line is synced.
 */
public record LyricsLine(long startTimeMs, String text, List<Word> words) {

    public static final long NO_TIME = -1;

    public LyricsLine {
        words = words == null ? List.of() : Collections.unmodifiableList(words);
    }

    public LyricsLine(long startTimeMs, String text) {
        this(startTimeMs, text, List.of());
    }

    public boolean hasWords() {
        return !words.isEmpty();
    }

    @NonNull
    @Override
    public String toString() {
        return "LyricsLine{" + startTimeMs + ", '" + text + "', words=" + words.size() + "}";
    }
}
