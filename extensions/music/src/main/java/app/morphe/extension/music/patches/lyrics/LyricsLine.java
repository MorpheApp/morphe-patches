/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.NonNull;

/**
 * A single lyrics line.
 *
 * @param startTimeMs Start time in milliseconds, or {@link #NO_TIME} for unsynced lyrics.
 */
public record LyricsLine(long startTimeMs, String text) {

    public static final long NO_TIME = -1;

    @NonNull
    @Override
    public String toString() {
        return "LyricsLine{" + startTimeMs + ", '" + text + "'}";
    }
}
