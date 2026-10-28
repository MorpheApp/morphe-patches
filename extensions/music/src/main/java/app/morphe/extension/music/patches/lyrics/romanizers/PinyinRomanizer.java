/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Hanyu Pinyin for a line of Chinese: {@link PinyinDictStore#word()} reads it a word at a time,
 * {@link PinyinDictStore#reading(char)} reads the rest a character at a time, and {@link #WORDS}
 * covers the words neither has.
 */
public final class PinyinRomanizer {

    /**
     * Words the word table does not have: read one character at a time they are wrong, since a
     * character changes its reading before the next, or the reading it is given alone is not the
     * one the word wants.
     */
    private static final Map<String, String> WORDS = new HashMap<>();

    static {
        WORDS.put("一个人", "yí gè rén");
        WORDS.put("重新开始", "chóng xīn kāi shǐ");
    }

    private PinyinRomanizer() {
    }

    /** Pinyin for a Chinese line, with a space between readings. */
    public static String romanize(String line) {
        WordReadingTable table = PinyinDictStore.word();
        return HanRomanizer.romanize(line, new HanRomanizer.Readings() {
            @Nullable
            @Override
            public String word(String word) {
                String reading = table != null ? table.reading(word) : null;
                return reading != null ? reading : WORDS.get(word);
            }

            @Nullable
            @Override
            public String character(char c) {
                return PinyinDictStore.reading(c);
            }
        });
    }
}
