/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector.Script;

/**
 * Romanizes lyrics on the device, for the lines no service could romanize.
 *
 * <p>This runs last, after the provider, the AI endpoint and Google have all come back empty,
 * so what it produces is close to, but not the same as, what a service produces. A line in a
 * script nothing here covers is passed through rather than guessed at.
 *
 * <p>A word the dictionary does not have is left in the script it was written in, since
 * dropping the line would lose the words around it.
 */
public final class LocalRomanizer {


    public static final class Result {
        /** One line per input line. */
        public final List<String> lines;

        /** The name shown under the source. */
        public final String dictionary;

        Result(List<String> lines, String dictionary) {
            this.lines = lines;
            this.dictionary = dictionary;
        }
    }

    private static final String PINYIN = "Built-in pinyin";
    private static final String HANGUL = "Built-in romanization";
    private static final String JAPANESE = "SudachiDict";

    private LocalRomanizer() {
    }

    /** Null when nothing here applies, which tells the caller to leave the lyrics alone. */
    @Nullable
    public static Result romanize(List<String> lines) {
        if (lines == null || lines.isEmpty()) return null;
        if (ScriptDetector.detect(lines) == Script.OTHER) return null;

        boolean changed = false;
        String dictionary = null;
        List<String> result = new ArrayList<>(lines.size());

        for (String line : lines) {
            String romanized = line;
            switch (ScriptDetector.detect(line)) {
                case HAN:
                    if (PinyinDictStore.isReady()) {
                        romanized = PinyinRomanizer.romanize(line);
                        dictionary = PINYIN;
                    }
                    break;
                case JAPANESE:
                    romanized = japanese(line);
                    // Kana read by rule are not the dictionary's work, so nothing is credited.
                    if (JapaneseDictStore.isReady()) dictionary = JAPANESE;
                    break;
                case KOREAN:
                    romanized = HangulRomanizer.romanize(line);
                    dictionary = HANGUL;
                    break;
                default:
                    break;
            }

            if (!romanized.equals(line)) changed = true;
            result.add(romanized);
        }

        return changed ? new Result(result, dictionary) : null;
    }

    /** Kana by rule, everything else out of the dictionary. */
    private static String japanese(String line) {
        JapaneseReadingTable table = JapaneseDictStore.table();
        StringBuilder out = new StringBuilder(line.length() * 2);
        int i = 0;

        while (i < line.length()) {
            char c = line.charAt(i);
            if (ScriptDetector.isKana(c)) {
                int end = i;
                while (end < line.length() && ScriptDetector.isKana(line.charAt(end))) end++;
                kana(line, i, end, table, out);
                i = end;
                continue;
            }
            if (ScriptDetector.isHan(c)) {
                if (table == null) {
                    out.append(c);
                    i++;
                    continue;
                }
                // The longest entry that fits: a shorter one inside a longer word reads it wrong.
                int limit = Math.min(JapaneseReadingTable.MAX_WORD_LENGTH, line.length() - i);
                String reading = null;
                int length = 0;
                for (int candidate = limit; candidate >= 1; candidate--) {
                    String found = table.reading(line.substring(i, i + candidate));
                    if (found != null && !found.isEmpty()) {
                        reading = found;
                        length = candidate;
                        break;
                    }
                }
                if (reading == null) {
                    out.append(c);
                    i++;
                    continue;
                }
                append(out, KanaRomanizer.romanize(reading));
                i += length;
                continue;
            }
            // Anything else, such as a word in Latin letters, kept whole rather than per letter.
            int end = i;
            while (end < line.length() && !ScriptDetector.isKana(line.charAt(end))
                    && !ScriptDetector.isHan(line.charAt(end))) {
                end++;
            }
            append(out, line.substring(i, end).trim());
            i = end;
        }
        return out.toString().trim();
    }

    /** A run of kana, cut into words: the rule reads it either way, but without the words a
     * line reads as {@code chottomatte} where Google gives {@code Chotto matte}. */
    private static void kana(String line, int from, int to, JapaneseReadingTable table,
            StringBuilder out) {
        int at = from;
        while (at < to) {
            int length = to - at;
            if (table != null) {
                int limit = Math.min(JapaneseReadingTable.MAX_WORD_LENGTH, to - at);
                for (int candidate = limit; candidate >= 1; candidate--) {
                    if (table.reading(line.substring(at, at + candidate)) != null) {
                        length = candidate;
                        break;
                    }
                }
            }
            append(out, KanaRomanizer.romanize(line.substring(at, at + length)));
            at += length;
        }
    }

    private static void append(StringBuilder out, String word) {
        if (word.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(word);
    }
}
