/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collections;
import java.util.List;

/**
 * A single lyrics line.
 *
 * @param startTimeMs Start time in milliseconds, or {@link #NO_TIME} for unsynced lyrics.
 * @param endTimeMs   End time in milliseconds, or {@link #NO_TIME} when unknown.
 * @param text        Full line text. For word synced lines it is the concatenation of
 *                    every {@link #words()} entry, so translation and copy stay correct.
 * @param words       Word level timing, empty when only the line is synced.
 * @param agentId     Agent ID (e.g. "v1", "v2") from {@code ttm:agent}, or {@code null}.
 * @param isDuet      Right-aligned duet line derived from agent alternation.
 * @param isBG        Background vocal line.
 * @param songPart    Song section label (e.g. "Verse", "Chorus"), or {@code null}.
 */
public record LyricsLine(long startTimeMs, long endTimeMs, String text, List<Word> words,
                         @Nullable String agentId, boolean isDuet, boolean isBG,
                         @Nullable String songPart) {

    public static final long NO_TIME = -1;

    public LyricsLine {
        words = words == null ? List.of() : Collections.unmodifiableList(words);
    }

    public LyricsLine(long startTimeMs, long endTimeMs, String text, List<Word> words,
                      @Nullable String agentId, boolean isDuet, boolean isBG) {
        this(startTimeMs, endTimeMs, text, words, agentId, isDuet, isBG, null);
    }

    public LyricsLine(long startTimeMs, String text, List<Word> words,
                      @Nullable String agentId, boolean isDuet, boolean isBG) {
        this(startTimeMs, NO_TIME, text, words, agentId, isDuet, isBG, null);
    }

    public LyricsLine(long startTimeMs, String text, List<Word> words) {
        this(startTimeMs, NO_TIME, text, words, null, false, false, null);
    }

    public LyricsLine(long startTimeMs, long endTimeMs, String text, List<Word> words) {
        this(startTimeMs, endTimeMs, text, words, null, false, false, null);
    }

    public LyricsLine(long startTimeMs, String text) {
        this(startTimeMs, NO_TIME, text, List.of(), null, false, false, null);
    }

    public boolean hasWords() {
        return !words.isEmpty();
    }

    @NonNull
    @Override
    public String toString() {
        return "LyricsLine{" + startTimeMs + "-" + endTimeMs + ", '" + text + "', words=" + words.size()
                + ", agent=" + agentId + ", duet=" + isDuet + ", bg=" + isBG
                + ", songPart=" + songPart + "}";
    }
}
