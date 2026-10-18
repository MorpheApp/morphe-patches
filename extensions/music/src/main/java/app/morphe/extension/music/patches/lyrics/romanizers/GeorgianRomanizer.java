/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Georgian writes every vowel in full, so one letter is one sound; the capitalised letters of the
 * Georgian Extended block fold onto the lower-case ones.
 */
public final class GeorgianRomanizer {

    private static final Map<String, String> LETTERS = new LinkedHashMap<>();

    static {
        LETTERS.put("ა", "a");
        LETTERS.put("ბ", "b");
        LETTERS.put("გ", "g");
        LETTERS.put("დ", "d");
        LETTERS.put("ე", "e");
        LETTERS.put("ვ", "v");
        LETTERS.put("ზ", "z");
        LETTERS.put("თ", "t");
        LETTERS.put("ი", "i");
        LETTERS.put("კ", "k");
        LETTERS.put("ლ", "l");
        LETTERS.put("მ", "m");
        LETTERS.put("ნ", "n");
        LETTERS.put("ო", "o");
        LETTERS.put("პ", "p");
        LETTERS.put("ჟ", "zh");
        LETTERS.put("რ", "r");
        LETTERS.put("ს", "s");
        LETTERS.put("ტ", "t");
        LETTERS.put("უ", "u");
        LETTERS.put("ფ", "p");
        LETTERS.put("ქ", "k");
        LETTERS.put("ღ", "gh");
        LETTERS.put("ყ", "q");
        LETTERS.put("შ", "sh");
        LETTERS.put("ჩ", "ch");
        LETTERS.put("ც", "ts");
        LETTERS.put("ძ", "dz");
        LETTERS.put("წ", "ts");
        LETTERS.put("ხ", "kh");
        LETTERS.put("ჯ", "j");
        LETTERS.put("ჰ", "h");
    }

    private GeorgianRomanizer() {
    }

    public static String romanize(String line) {
        StringBuilder out = new StringBuilder(line.length() * 2);
        StringBuilder word = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            if (!ScriptDetector.isGeorgian(line.charAt(i))) {
                i += flush(out, word, line, i);
                continue;
            }
            word.append(letter(line.charAt(i)));
            i++;
        }
        append(out, word.toString());
        return out.toString().trim();
    }

    private static String letter(char c) {
        final String reading = LETTERS.get(String.valueOf(Character.toLowerCase(c)));
        return reading != null ? reading : String.valueOf(c);
    }

    /** Everything that is not Georgian runs through whole, so a mixed line keeps its other half. */
    private static int flush(StringBuilder out, StringBuilder word, String line, int at) {
        append(out, word.toString());
        word.setLength(0);
        int end = at + 1;
        while (end < line.length() && !ScriptDetector.isGeorgian(line.charAt(end))) {
            end++;
        }
        String rest = line.substring(at, end).trim();
        if (!rest.isEmpty()) append(out, rest);
        return end - at;
    }

    private static void append(StringBuilder out, String word) {
        if (word.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(word);
    }
}