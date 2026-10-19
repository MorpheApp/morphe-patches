/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Georgian writes every vowel in full, so one letter is one sound; the capitalised letters of the
 * Georgian Extended block fold onto the lower-case ones.
 */
public final class GeorgianRomanizer {

    private static final Map<String, String> LETTERS = new HashMap<>();

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

    private static final AlphabetRomanizer READER = new AlphabetRomanizer(
            ScriptDetector::isGeorgian, LETTERS, Collections.emptyMap(), false);

    private GeorgianRomanizer() {
    }

    public static String romanize(String line) {
        return READER.romanize(line);
    }
}
