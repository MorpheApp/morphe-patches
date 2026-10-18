/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.util.HashMap;
import java.util.Map;

/**
 * Hanyu Pinyin for a line of Chinese: {@link PinyinDictStore#word()} reads it a word at a time,
 * {@link PinyinDictStore#reading(char)} reads the rest a character at a time, and
 * {@link PinyinWords} covers the words neither has.
 */
public final class PinyinRomanizer {

    private static final Map<String, String> WORDS = load();

    private static Map<String, String> load() {
        Map<String, String> words = new HashMap<>();
        for (String reading : PinyinWords.READINGS) {
            int split = reading.indexOf('=');
            if (split <= 0) continue;
            words.put(reading.substring(0, split), reading.substring(split + 1));
        }
        return words;
    }

    private PinyinRomanizer() {
    }

    /** Pinyin for a Chinese line, with a space between readings. Kana are read by rule and
     * the rest is passed through, so a line that mixes scripts keeps its other half. */
    public static String romanize(String line) {
        WordReadingTable table = PinyinDictStore.word();
        StringBuilder out = new StringBuilder(line.length() * 3);
        int i = 0;
        while (i < line.length()) {
            int length = wordLengthAt(line, i, table);
            if (length > 0) {
                append(out, wordReading(line, i, length, table));
                i += length;
                continue;
            }

            char c = line.charAt(i);
            String single = single(c);
            if (single != null) {
                append(out, single);
                i++;
                continue;
            }

            // A line with too little kana to count as Japanese can still carry some, and kana
            // need no dictionary.
            if (ScriptDetector.isKana(c)) {
                int kanaEnd = i;
                while (kanaEnd < line.length()
                        && ScriptDetector.isKana(line.charAt(kanaEnd))) {
                    kanaEnd++;
                }
                append(out, KanaRomanizer.romanize(line.substring(i, kanaEnd)));
                i = kanaEnd;
                continue;
            }

            int end = i + 1;
            while (end < line.length() && !hasReading(line.charAt(end))
                    && !ScriptDetector.isKana(line.charAt(end))) {
                end++;
            }
            String rest = line.substring(i, end).trim();
            if (!rest.isEmpty()) append(out, rest);
            i = end;
        }
        return out.toString().trim();
    }

    private static void append(StringBuilder out, String reading) {
        if (out.length() > 0) out.append(' ');
        out.append(reading);
    }

    /** The longest word starting here: a shorter one inside a longer word reads it wrong. */
    private static int wordLengthAt(String line, int index, WordReadingTable table) {
        int longest = Math.min(WordReadingTable.MAX_WORD_LENGTH, line.length() - index);
        for (int length = longest; length >= 1; length--) {
            if (WORDS.containsKey(word(line, index, length))) return length;
            if (table != null && table.reading(word(line, index, length)) != null) return length;
        }
        return 0;
    }

    private static String wordReading(String line, int index, int length, WordReadingTable table) {
        String word = word(line, index, length);
        String reading = table != null ? table.reading(word) : null;
        return reading != null ? reading : WORDS.get(word);
    }

    private static String word(String line, int index, int length) {
        return line.substring(index, index + length);
    }

    private static String single(char c) {
        return isHan(c) ? PinyinDictStore.reading(c) : null;
    }

    private static boolean hasReading(char c) {
        return isHan(c);
    }

    private static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }
}
