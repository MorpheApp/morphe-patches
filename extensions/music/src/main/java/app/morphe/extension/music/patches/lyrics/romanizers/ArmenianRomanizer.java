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
 * Armenian writes every vowel in full, so this is rules, not a dictionary; only the pairs spelling
 * one sound with two letters need a second table.
 */
public final class ArmenianRomanizer {

    private static final Map<String, String> LETTERS = new LinkedHashMap<>();
    private static final Map<String, String> DIGRAPHS = new LinkedHashMap<>();

    static {
        LETTERS.put("ա", "a");
        LETTERS.put("բ", "b");
        LETTERS.put("գ", "g");
        LETTERS.put("դ", "d");
        LETTERS.put("ե", "e");
        LETTERS.put("զ", "z");
        LETTERS.put("է", "e");
        LETTERS.put("ը", "y");
        LETTERS.put("թ", "t");
        LETTERS.put("ժ", "zh");
        LETTERS.put("ի", "i");
        LETTERS.put("լ", "l");
        LETTERS.put("խ", "kh");
        LETTERS.put("ծ", "ts");
        LETTERS.put("կ", "k");
        LETTERS.put("հ", "h");
        LETTERS.put("ձ", "dz");
        LETTERS.put("ղ", "gh");
        LETTERS.put("ճ", "ch");
        LETTERS.put("մ", "m");
        LETTERS.put("յ", "y");
        LETTERS.put("ն", "n");
        LETTERS.put("շ", "sh");
        LETTERS.put("ո", "o");
        LETTERS.put("չ", "ch");
        LETTERS.put("պ", "p");
        LETTERS.put("ջ", "j");
        LETTERS.put("ռ", "r");
        LETTERS.put("ս", "s");
        LETTERS.put("վ", "v");
        LETTERS.put("տ", "t");
        LETTERS.put("ր", "r");
        LETTERS.put("ց", "ts");
        LETTERS.put("ւ", "v");
        LETTERS.put("փ", "p");
        LETTERS.put("ք", "k");
        LETTERS.put("օ", "o");
        LETTERS.put("ֆ", "f");
        // One ligature that stands for two letters.
        LETTERS.put("և", "ev");

        DIGRAPHS.put("ու", "u");
        DIGRAPHS.put("ՈՒ", "u");
    }

    private ArmenianRomanizer() {
    }

    public static String romanize(String line) {
        StringBuilder out = new StringBuilder(line.length() * 2);
        StringBuilder word = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            if (!ScriptDetector.isArmenian(line.charAt(i))) {
                i += flush(out, word, line, i);
                continue;
            }
            String pair = i + 1 < line.length() ? line.substring(i, i + 2) : "";
            String reading = DIGRAPHS.get(pair);
            if (reading == null) {
                word.append(letter(line.charAt(i)));
                i++;
            } else {
                word.append(reading);
                i += 2;
            }
        }
        append(out, word.toString());
        return out.toString().trim();
    }

    private static String letter(char c) {
        final String reading = LETTERS.get(String.valueOf(Character.toLowerCase(c)));
        return reading != null ? reading : String.valueOf(c);
    }

    /** Everything that is not Armenian runs through whole, so a mixed line keeps its other half. */
    private static int flush(StringBuilder out, StringBuilder word, String line, int at) {
        append(out, word.toString());
        word.setLength(0);
        int end = at + 1;
        while (end < line.length() && !ScriptDetector.isArmenian(line.charAt(end))) {
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