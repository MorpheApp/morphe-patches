/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

/**
 * Jyutping for a line of Cantonese: {@link CantoneseDictStore#word()} reads it a word at a time,
 * and {@link CantoneseDictStore#reading(char)} reads the rest a character at a time. A character
 * neither has is kept as it is, since dropping it would lose the characters around it.
 */
public final class JyutpingRomanizer {

    private JyutpingRomanizer() {
    }

    public static String romanize(String line) {
        WordReadingTable table = CantoneseDictStore.word();
        return HanRomanizer.romanize(line, new HanRomanizer.Readings() {
            @Nullable
            @Override
            public String word(String word) {
                return table != null ? table.reading(word) : null;
            }

            @Nullable
            @Override
            public String character(char c) {
                return CantoneseDictStore.reading(c);
            }
        });
    }
}
