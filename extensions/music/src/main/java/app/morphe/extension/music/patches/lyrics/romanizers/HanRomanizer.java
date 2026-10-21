/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

/**
 * Reads a line of Han characters a word at a time, since a character alone cannot say which of its
 * readings the word around it wants, and a character at a time where no word is known. Kana are
 * read by rule and the rest is passed through, so a line that mixes scripts keeps its other half.
 */
final class HanRomanizer {

    interface Readings {
        @Nullable
        String word(String word);

        @Nullable
        String character(char c);
    }

    private HanRomanizer() {
    }

    /** The readings with a space between them. */
    static String romanize(String line, Readings readings) {
        StringBuilder out = new StringBuilder(line.length() * 3);
        int i = 0;
        while (i < line.length()) {
            int length = appendLongestWord(line, i, readings, out);
            if (length > 0) {
                i += length;
                continue;
            }

            char c = line.charAt(i);
            String syllable = character(c, readings);
            if (syllable != null) {
                append(out, syllable);
                i++;
                continue;
            }

            // A line with too little kana to count as Japanese can still carry some, and kana
            // need no dictionary.
            if (ScriptDetector.isKana(c)) {
                int kanaEnd = i;
                while (kanaEnd < line.length() && ScriptDetector.isKana(line.charAt(kanaEnd))) {
                    kanaEnd++;
                }
                append(out, KanaRomanizer.romanize(line.substring(i, kanaEnd)));
                i = kanaEnd;
                continue;
            }

            int end = i + 1;
            while (end < line.length() && character(line.charAt(end), readings) == null
                    && !ScriptDetector.isKana(line.charAt(end))) {
                end++;
            }
            append(out, line.substring(i, end).trim());
            i = end;
        }
        return out.toString().trim();
    }

    /**
     * The longest word starting here, since a shorter one inside a longer word reads it wrong.
     *
     * @return how many characters the word took, 0 when none is known.
     */
    private static int appendLongestWord(String line, int index, Readings readings,
            StringBuilder out) {
        int longest = Math.min(WordReadingTable.MAX_WORD_LENGTH, line.length() - index);
        for (int length = longest; length >= 1; length--) {
            String reading = readings.word(line.substring(index, index + length));
            if (reading != null) {
                append(out, reading);
                return length;
            }
        }
        return 0;
    }

    @Nullable
    private static String character(char c, Readings readings) {
        return ScriptDetector.isHan(c) ? readings.character(c) : null;
    }

    private static void append(StringBuilder out, @Nullable String reading) {
        if (reading == null || reading.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(reading);
    }
}
