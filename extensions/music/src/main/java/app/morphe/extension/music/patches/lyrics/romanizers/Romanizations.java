/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4323
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

/** Building a reading out of its words, the one way every romanizer lays them down. */
public final class Romanizations {

    private Romanizations() {
    }

    /** Appends a word after the ones already there, with one space between them. */
    public static void appendWord(StringBuilder out, @Nullable String word) {
        if (word == null || word.isEmpty()) {
            return;
        }
        //noinspection SizeReplaceableByIsEmpty
        if (out.length() > 0) {
            out.append(' ');
        }
        out.append(word);
    }
}
