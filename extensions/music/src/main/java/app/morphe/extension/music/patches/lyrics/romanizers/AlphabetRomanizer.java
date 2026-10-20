/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.text.Normalizer;
import java.util.Map;

/**
 * Reads a script that is written the way it sounds, so rules and not a dictionary: each letter,
 * or each pair of letters that spells one sound, has a fixed reading. Everything outside the
 * script runs through whole, so a mixed line keeps its other half.
 */
final class AlphabetRomanizer {

    interface Script {
        boolean contains(char c);
    }

    private static final int COMBINING_DIAERESIS = 0x0308;

    private final Script script;
    private final Map<String, String> letters;
    private final Map<String, String> digraphs;
    private final boolean stripAccents;

    /**
     * @param letters      Readings keyed by the lower case letter.
     * @param digraphs     Readings keyed by a lower case pair of letters.
     * @param stripAccents Whether accents only mark stress and are dropped before the lookup.
     */
    AlphabetRomanizer(Script script, Map<String, String> letters, Map<String, String> digraphs,
            boolean stripAccents) {
        this.script = script;
        this.letters = letters;
        this.digraphs = digraphs;
        this.stripAccents = stripAccents;
    }

    String romanize(String line) {
        StringBuilder out = new StringBuilder(line.length() * 2);
        StringBuilder word = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            final char c = line.charAt(i);
            if (!script.contains(c)) {
                append(out, word.toString());
                word.setLength(0);
                int end = i + 1;
                while (end < line.length() && !script.contains(line.charAt(end))) {
                    end++;
                }
                append(out, line.substring(i, end).trim());
                i = end;
                continue;
            }

            final String base = base(c);
            if (i + 1 < line.length() && pairsWith(line.charAt(i + 1))) {
                final String pair = digraphs.get(base + base(line.charAt(i + 1)));
                if (pair != null) {
                    word.append(cased(pair, c));
                    i += 2;
                    continue;
                }
            }
            final String reading = letters.get(base);
            word.append(reading != null ? cased(reading, c) : String.valueOf(c));
            i++;
        }
        append(out, word.toString());
        return out.toString().trim();
    }

    /** A diaeresis says the vowel is sounded on its own, so it never joins the one before. */
    private boolean pairsWith(char next) {
        if (!script.contains(next)) return false;
        return !stripAccents || decompose(next).indexOf(COMBINING_DIAERESIS) < 0;
    }

    private String base(char c) {
        char lower = Character.toLowerCase(c);
        if (stripAccents) {
            String decomposed = decompose(lower);
            if (!decomposed.isEmpty()) lower = decomposed.charAt(0);
        }
        return String.valueOf(lower);
    }

    private static String decompose(char c) {
        return Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
    }

    /** A capital letter keeps its capital in the reading. */
    private static String cased(String reading, char source) {
        if (reading.isEmpty() || !Character.isUpperCase(source)) return reading;
        return Character.toUpperCase(reading.charAt(0)) + reading.substring(1);
    }

    private static void append(StringBuilder out, String word) {
        if (word.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(word);
    }
}
