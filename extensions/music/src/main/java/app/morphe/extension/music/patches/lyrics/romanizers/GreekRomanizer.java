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
 * Modern Greek is written the way it sounds, so this is a set of rules and not a dictionary.
 * Accents come off first, since they mark stress and carry no sound, and readings come out one
 * word at a time, because cutting syllables would need to know where a word begins.
 */
public final class GreekRomanizer {

    private static final Map<String, String> LETTERS = new HashMap<>();
    private static final Map<String, String> DIGRAPHS = new HashMap<>();

    static {
        LETTERS.put("α", "a");
        LETTERS.put("β", "v");
        LETTERS.put("γ", "g");
        LETTERS.put("δ", "d");
        LETTERS.put("ε", "e");
        LETTERS.put("ζ", "z");
        LETTERS.put("η", "i");
        LETTERS.put("θ", "th");
        LETTERS.put("ι", "i");
        LETTERS.put("κ", "k");
        LETTERS.put("λ", "l");
        LETTERS.put("μ", "m");
        LETTERS.put("ν", "n");
        LETTERS.put("ξ", "x");
        LETTERS.put("ο", "o");
        LETTERS.put("π", "p");
        LETTERS.put("ρ", "r");
        LETTERS.put("σ", "s");
        LETTERS.put("ς", "s");
        LETTERS.put("τ", "t");
        LETTERS.put("υ", "y");
        LETTERS.put("φ", "f");
        LETTERS.put("χ", "ch");
        LETTERS.put("ψ", "ps");
        LETTERS.put("ω", "o");

        // A vowel pair writes one vowel between them, which is why it cannot be read letter by letter.
        DIGRAPHS.put("ου", "u");
        DIGRAPHS.put("αι", "ai");
        DIGRAPHS.put("ει", "i");
        DIGRAPHS.put("αυ", "av");
        DIGRAPHS.put("ευ", "ev");
        DIGRAPHS.put("ηυ", "iv");
        DIGRAPHS.put("ωυ", "o");
        DIGRAPHS.put("μπ", "mp");
        DIGRAPHS.put("ντ", "nd");
        DIGRAPHS.put("γκ", "gk");
        DIGRAPHS.put("γχ", "ch");
        DIGRAPHS.put("τσ", "ts");
        DIGRAPHS.put("τζ", "tz");
    }

    private static final AlphabetRomanizer READER = new AlphabetRomanizer(
            ScriptDetector::isGreek, LETTERS, DIGRAPHS, true);

    private GreekRomanizer() {
    }

    public static String romanize(String line) {
        return READER.romanize(line);
    }
}
