/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.util.HashMap;
import java.util.Map;

/**
 * Armenian writes every vowel in full, so this is rules, not a dictionary; only the pairs spelling
 * one sound with two letters need a second table.
 */
public final class ArmenianRomanizer {

    private static final Map<String, String> LETTERS = new HashMap<>();
    private static final Map<String, String> DIGRAPHS = new HashMap<>();

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
    }

    private static final AlphabetRomanizer READER = new AlphabetRomanizer(
            ScriptDetector::isArmenian, LETTERS, DIGRAPHS, false);

    private ArmenianRomanizer() {
    }

    public static String romanize(String line) {
        return READER.romanize(line);
    }
}
