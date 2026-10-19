/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3851
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Modern Greek is written the way it sounds, so this is a set of rules and not a dictionary.
 * Accents come off first, since they mark stress and carry no sound, and readings come out one
 * word at a time, because cutting syllables would need to know where a word begins.
 */
public final class GreekRomanizer {

    private static final Map<String, String> LETTERS = new LinkedHashMap<>();
    private static final Map<String, String> DIGRAPHS = new LinkedHashMap<>();

    static {
        LETTERS.put("α", "a");   // α
        LETTERS.put("β", "v");   // β
        LETTERS.put("γ", "g");   // γ
        LETTERS.put("δ", "d");   // δ
        LETTERS.put("ε", "e");   // ε
        LETTERS.put("ζ", "z");   // ζ
        LETTERS.put("η", "i");   // η
        LETTERS.put("θ", "th");  // θ
        LETTERS.put("ι", "i");   // ι
        LETTERS.put("κ", "k");   // κ
        LETTERS.put("λ", "l");   // λ
        LETTERS.put("μ", "m");   // μ
        LETTERS.put("ν", "n");   // ν
        LETTERS.put("ξ", "x");   // ξ
        LETTERS.put("ο", "o");   // ο
        LETTERS.put("π", "p");   // π
        LETTERS.put("ρ", "r");   // ρ
        LETTERS.put("σ", "s");   // σ
        LETTERS.put("ς", "s");   // ς
        LETTERS.put("τ", "t");   // τ
        LETTERS.put("υ", "y");   // υ
        LETTERS.put("φ", "f");   // φ
        LETTERS.put("χ", "ch");  // χ
        LETTERS.put("ψ", "ps");  // ψ
        LETTERS.put("ω", "o");   // ω

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

    private GreekRomanizer() {
    }

    public static String romanize(String line) {
        StringBuilder out = new StringBuilder(line.length() * 2);
        StringBuilder word = new StringBuilder();
        int i = 0;

        while (i < line.length()) {
            if (!ScriptDetector.isGreek(line.charAt(i))) {
                i += flush(out, word, line, i);
                continue;
            }
            String pair = i + 1 < line.length() ? line.substring(i, i + 2) : "";
            String reading = DIGRAPHS.get(pair);
            if (reading == null) {
                word.append(letter(stripped(line.charAt(i))));
                i++;
            } else {
                word.append(reading);
                i += 2;
            }
        }
        append(out, word.toString());
        return out.toString().trim();
    }

    private static char stripped(char c) {
        String base = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
        return base.isEmpty() ? c : base.charAt(0);
    }

    private static String letter(char c) {
        String reading = LETTERS.get(String.valueOf(c));
        if (reading != null) return reading;
        String lower = letter(Character.toLowerCase(c));
        return lower.isEmpty() ? String.valueOf(c)
                : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    /** Everything that is not Greek runs through whole, so a mixed line keeps its other half. */
    private static int flush(StringBuilder out, StringBuilder syllable, String line, int at) {
        append(out, syllable.toString());
        syllable.setLength(0);
        int end = at + 1;
        while (end < line.length() && !ScriptDetector.isGreek(line.charAt(end))) {
            end++;
        }
        String rest = line.substring(at, end).trim();
        if (!rest.isEmpty()) append(out, rest);
        return end - at;
    }

    private static void append(StringBuilder out, String syllable) {
        if (syllable.isEmpty()) return;
        if (out.length() > 0) out.append(' ');
        out.append(syllable);
    }
}