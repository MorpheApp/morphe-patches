/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Hepburn romaji for a run of kana. Work is done a mora at a time: a character alone says
 * neither whether a small kana belongs to it nor whether it palatalizes. Tables come from the
 * Unicode names (handwritten tables are silently corruptible); two Google conventions are kept,
 * so both paths spell a song the same: no apostrophe before a vowel, n before a labial as m.
 */
public final class KanaRomanizer {

    private static final int KANA_BASE = 0x30A0;
    private static final int KATAKANA_START = 0x30A1;
    private static final int KATAKANA_END = 0x30FA;
    private static final String NAME_PREFIX = "KATAKANA LETTER";

    /** Where the Unicode names read differently from Hepburn, as name then Hepburn. */
    private static final String[][] HEPBURN = {
            {"si", "shi"}, {"ti", "chi"}, {"tu", "tsu"}, {"hu", "fu"},
            {"zi", "ji"}, {"di", "ji"}, {"du", "zu"},
    };

    /** The consonants whose small ya, yo and yu replace the vowel instead of gliding. */
    private static final String[] SIBILANTS = {"ch", "sh", "ts", "j"};

    private static final Map<Character, String> FULL_SIZE = new HashMap<>();

    static {
        for (int code = KATAKANA_START; code <= KATAKANA_END; code++) {
            char kana = (char) code;
            String name = Character.getName(kana);
            if (name == null || !name.startsWith(NAME_PREFIX)) continue;

            String reading = name.substring(name.lastIndexOf(' ') + 1).toLowerCase(Locale.ROOT);
            for (String[] pair : HEPBURN) {
                if (pair[0].equals(reading)) {
                    reading = pair[1];
                    break;
                }
            }
            FULL_SIZE.put(kana, reading);
        }
    }

    private KanaRomanizer() {
    }

    public static String romanize(String kana) {
        return romanize(kana, false);
    }

    /** The reading with a space between syllables, such as "no ru ma" for ノルマ. */
    public static String romanizeSyllables(String kana) {
        return romanize(kana, true).trim();
    }

    private static String romanize(String kana, boolean spaced) {
        StringBuilder out = new StringBuilder(kana.length() * 3);
        boolean geminate = false;
        int i = 0;

        while (i < kana.length()) {
            char c = katakana(kana.charAt(i));

            // Small tsu (sokuon).
            if (c == 0x3063 || c == 0x30C3) {

                geminate = true;
                i++;
                continue;
            }
            // Prolonged sound mark.
            if (c == 0x30FC || c == 0x2014) {
                repeatVowel(out);
                i++;
                continue;
            }
            if (isN(c)) {
                if (spaced) separate(out);
                appendN(out, kana, i);
                geminate = false;
                i++;
                continue;
            }

            String reading = FULL_SIZE.get(c);
            if (reading == null) {
                // An unknown kana, such as an iteration mark, is passed through, not dropped.
                if (spaced) {
                    separate(out);
                }
                if (!spaced || !Character.isWhitespace(c)) {
                    out.append(c);
                }
                geminate = false;
                i++;
                continue;
            }

            String consonant = consonantOf(reading);
            String syllable = consonant + reading.substring(consonant.length());
            int next = i + 1;

            char following = next < kana.length() ? katakana(kana.charAt(next)) : 0;
            if (isSmallY(following)) {
                syllable = palatalise(consonant, smallYVowel(following));
                next++;
            } else if (consonant.isEmpty() && isSmallVowel(following)) {
                syllable = readingOf(following);
                next++;
            }

            if (spaced) separate(out);
            if (geminate) {
                assert syllable != null;
                applyGeminate(out, syllable);
                geminate = false;
            }
            out.append(syllable);
            i = next;
        }
        return out.toString();
    }

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    private static void separate(StringBuilder out) {
        if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') {
            out.append(' ');
        }
    }

    /** Whether a small ya, yu or yo glides or replaces the vowel, which separates kyo from shu. */
    private static String palatalise(String consonant, String vowel) {
        for (String sibilant : SIBILANTS) {
            if (sibilant.equals(consonant)) return consonant + vowel;
        }
        return consonant + "y" + vowel;
    }

    /** The consonant a reading starts with. The y and w rows are vowels of their own. */
    private static String consonantOf(String romaji) {
        char first = romaji.charAt(0);
        if (isVowel(first) || first == 'y' || first == 'w') return "";
        return romaji.substring(0, romaji.length() - 1);
    }

    private static void applyGeminate(StringBuilder out, String syllable) {
        char first = syllable.charAt(0);
        if (!isConsonant(first) || first == 'n' || first == 'h') {
            // A vowel cannot double, and neither n nor h do, so t stands in for them.
            out.append('t');
            return;
        }
        int length = syllable.startsWith("ch") || syllable.startsWith("sh") ? 2 : 1;
        out.append(syllable, 0, length);
    }

    private static void appendN(StringBuilder out, String kana, int index) {
        char next = index + 1 < kana.length() ? katakana(kana.charAt(index + 1)) : 0;
        if (isN(next)) {
            out.append("nn");
            return;
        }
        String tail = readingOf(next);
        if (tail != null && !tail.isEmpty() && "bpm".indexOf(tail.charAt(0)) >= 0) {
            out.append('m');
        } else {
            out.append('n');
        }
    }

    private static void repeatVowel(StringBuilder out) {
        if (out.length() == 0) {
            return;
        }
        char c = out.charAt(out.length() - 1);
        if (!isConsonant(c)) {
            out.append(c);
        }
    }

    @Nullable
    private static String readingOf(char kana) {
        return FULL_SIZE.get(kana);
    }

    private static String smallYVowel(char c) {
        return switch (c) {
            case 0x3083, 0x30E3 -> "a";
            case 0x3085, 0x30E5 -> "u";
            default -> "o";
        };
    }

    private static boolean isN(char c) {
        return c == 0x3093 || c == 0x30F3;
    }

    private static boolean isSmallY(char c) {
        return c == 0x3083 || c == 0x3085 || c == 0x3087
                || c == 0x30E3 || c == 0x30E5 || c == 0x30E7;
    }

    private static boolean isSmallVowel(char c) {
        return c == 0x3041 || c == 0x3043 || c == 0x3045 || c == 0x3047 || c == 0x3049
                || c == 0x30A1 || c == 0x30A3 || c == 0x30A5 || c == 0x30A7 || c == 0x30A9;
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean isConsonant(char c) {
        return c >= 'a' && c <= 'z' && !isVowel(c);
    }

    /** Folds hiragana onto katakana, which is how the table is keyed. */
    private static char katakana(char c) {
        if (c >= 0x3041 && c <= 0x3096) {
            return (char) (c + (0x30A1 - 0x3041));
        }
        return c;
    }

    public static String toHiragana(String kana) {
        char[] chars = kana.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            char c = chars[i];
            if (c >= 'ァ' && c <= 'ヶ') {
                chars[i] = (char) (c - ('ァ' - 'ぁ'));
            }
        }
        return new String(chars);
    }

    public static String toKatakana(String kana) {
        char[] chars = kana.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = katakana(chars[i]);
        }
        return new String(chars);
    }

    /** Whether a reading fits the one byte packing the dictionary file uses. */
    public static boolean isPackable(char c) {
        // Strictly above the base, since the escape in a reading table is a zero byte and
        // packing the base character itself would produce one.
        return c > KANA_BASE && c < 0x3100;
    }

    public static byte pack(char c) {
        return (byte) (c - KANA_BASE);
    }

    public static char unpack(byte value) {
        return (char) (value + KANA_BASE);
    }
}
