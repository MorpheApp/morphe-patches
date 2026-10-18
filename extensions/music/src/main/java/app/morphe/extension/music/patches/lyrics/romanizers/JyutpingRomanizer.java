/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

/**
 * Jyutping for a line of Cantonese, read a word at a time, since the character table gives each
 * character one reading and cannot settle a word by itself. A character neither has is kept as
 * it is, since dropping it would lose the characters around it.
 */
public final class JyutpingRomanizer {

    private JyutpingRomanizer() {
    }

    public static String romanize(String line) {
        WordReadingTable table = CantoneseDictStore.word();
        StringBuilder out = new StringBuilder(line.length() * 3);
        int i = 0;
        while (i < line.length()) {
            int length = wordLengthAt(line, i, table);
            if (length > 0) {
                append(out, table.reading(line.substring(i, i + length)));
                i += length;
                continue;
            }

            char c = line.charAt(i);
            String syllable = syllable(c);
            if (syllable != null) {
                append(out, syllable);
                i++;
                continue;
            }
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
            while (end < line.length() && syllable(line.charAt(end)) == null
                    && !ScriptDetector.isKana(line.charAt(end))) {
                end++;
            }
            String rest = line.substring(i, end).trim();
            if (!rest.isEmpty()) append(out, rest);
            i = end;
        }
        return out.toString().trim();
    }

    /** The longest word starting here: a shorter one inside a longer word reads it wrong. */
    private static int wordLengthAt(String line, int index, WordReadingTable table) {
        if (table == null) return 0;

        int longest = Math.min(WordReadingTable.MAX_WORD_LENGTH, line.length() - index);
        for (int length = longest; length >= 1; length--) {
            if (table.reading(line.substring(index, index + length)) != null) return length;
        }
        return 0;
    }

    private static void append(StringBuilder out, @Nullable String reading) {
        if (reading == null || reading.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(reading);
    }

    @Nullable
    private static String syllable(char c) {
        return ScriptDetector.isHan(c) ? CantoneseDictStore.reading(c) : null;
    }
}
