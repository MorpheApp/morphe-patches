/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

/**
 * Bopomofo (Zhuyin) for a line of Chinese: {@link BopomofoDictStore#word()} reads it a word at a
 * time, {@link BopomofoDictStore#reading(char)} reads the rest a character at a time.
 */
public final class BopomofoRomanizer {

    private BopomofoRomanizer() {
    }

    /** Bopomofo for a Chinese line, with a space between readings. */
    public static String romanize(String line) {
        final WordReadingTable table = BopomofoDictStore.word();
        return HanRomanizer.romanize(line, new HanRomanizer.Readings() {
            @Nullable
            @Override
            public String word(String word) {
                return table != null ? table.reading(word) : null;
            }

            @Nullable
            @Override
            public String character(char c) {
                return BopomofoDictStore.reading(c);
            }
        });
    }
}
