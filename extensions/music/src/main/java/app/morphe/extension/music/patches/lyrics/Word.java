/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import androidx.annotation.Nullable;

public record Word(long startMs, long endMs, String text, @Nullable String romaji,
                   boolean endsWithSpace) {

    public Word(long startMs, long endMs, String text) {
        this(startMs, endMs, text, null, false);
    }

    public Word(long startMs, long endMs, String text, @Nullable String romaji) {
        this(startMs, endMs, text, romaji, false);
    }
}
