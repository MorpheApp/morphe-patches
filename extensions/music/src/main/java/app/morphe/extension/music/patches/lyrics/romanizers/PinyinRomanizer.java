/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Hanyu Pinyin for a line of Chinese: {@link PinyinDictionaryStore#word()} reads it a word at a time,
 * {@link PinyinDictionaryStore#reading(char)} reads the rest a character at a time, and {@link #WORDS}
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
        // Kept split so the cluster boundary lands here.
        WORDS.put("重新", "chóng xīn");
        WORDS.put("开始", "kāi shǐ");
    }

    private PinyinRomanizer() {
    }

    /** Word groups for plain Chinese; kana keeps its own boundaries. */
    @Nullable
    public static int[] wordGroups(String line) {
        if (line.isEmpty()) {
            return null;
        }
        boolean hasHan = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (ScriptDetector.isKana(c)) {
                return null;
            }
            if (ScriptDetector.isHan(c)) {
                hasHan = true;
            }
        }
        if (!hasHan) {
            return null;
        }
        // Dictionary boundaries beat the reading table's longest match.
        final int[] jieba = JiebaSegmenter.wordGroups(line);
        if (jieba != null) {
            return jieba;
        }

        int[] groups = new int[line.length()];
        Arrays.fill(groups, -1);
        int group = 0;
        try (WordReadingTable table = PinyinDictionaryStore.word()) {
            int i = 0;
            int unknown = -1;
            while (i < line.length()) {
                if (!ScriptDetector.isHan(line.charAt(i))) {
                    unknown = -1;
                    i++;
                    continue;
                }
                final int length = longestWord(line, i, table);
                if (length == 0) {
                    if (table != null) {
                        // Unknown stretch: one group, not per-character.
                        if (unknown < 0) {
                            unknown = group++;
                        }
                        groups[i] = unknown;
                    } else if (PinyinDictionaryStore.reading(line.charAt(i)) != null) {
                        // A reading alone cannot place a boundary.
                        groups[i] = group++;
                    }
                    i++;
                    continue;
                }
                unknown = -1;
                final int end = i + length;
                for (int j = i; j < end; j++) {
                    groups[j] = group;
                }
                group++;
                i = end;
            }
        }
        return groups;
    }

    /** {@return the longest word of at least two characters starting here, or 0 when none} */
    private static int longestWord(String line, int index, @Nullable WordReadingTable table) {
        final int longest = Math.min(WordReadingTable.MAX_WORD_LENGTH, line.length() - index);
        for (int length = longest; length >= 2; length--) {
            final String word = line.substring(index, index + length);
            if ((table != null && table.reading(word) != null) || WORDS.containsKey(word)) {
                return length;
            }
        }
        return 0;
    }

    /** Pinyin for a Chinese line, with a space between readings. */
    public static String romanize(String line) {
        try (WordReadingTable table = PinyinDictionaryStore.word()) {
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
                    return PinyinDictionaryStore.reading(c);
                }
            });
        }
    }
}
