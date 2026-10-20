/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

/**
 * Revised Romanization for Korean, written the way Google writes it, so that a line does not
 * change spelling between the online path and this one. Two conventions follow Google rather
 * than the textbook: a coda before a silent ㅇ is written out with a hyphen, as in 시간이
 * {@code sigan-i}, and ㄹ always reads as {@code l}.
 *
 * <p>The tables were read off the Unicode names of the jamo rather than typed, since the
 * nineteen initial slots start at ㄱ with the silent ㅇ at slot eleven and the coda slot the
 * formula produces is one higher than the jamo index it stands for.
 *
 * <p>Left out, because they need a morphological analysis rather than a romanization: tensing,
 * as in 빛나는 {@code bichnaneun}, and the extra {@code h} Google writes into some joins, as
 * in 않은.
 */
public final class HangulRomanizer {

    /** The silent {@code ㅇ} sits at index 11 and writes nothing. */
    private static final String[] INITIALS = {
            "g", "kk", "n", "d", "tt", "l", "m", "b", "bb", "s", "ss", "", "j", "jj", "ch",
            "k", "t", "p", "h"
    };

    private static final String[] MEDIALS = {
            "a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo",
            "u", "wo", "we", "wi", "yu", "eu", "ui", "i"
    };

    /**
     * How a coda reads on its own, index zero being the absence of one. Three of them take
     * the consonant Google gives them rather than the unaspirated letter of the textbook.
     */
    private static final String[] FINALS = {
            "", "g", "kk", "k", "n", "n", "n", "t", "l", "k", "m", "p", "l", "l", "p", "l",
            "m", "p", "p", "s", "ss", "ng", "t", "t", "k", "t", "p", "t"
    };

    /** The same coda once it has moved onto the {@code ㅇ} that follows it. */
    private static final String[] LATERAL = {
            "", "g", "kk", "g", "n", "n", "n", "d", "l", "g", "m", "p", "l", "l", "p", "l",
            "m", "p", "p", "h", "ss", "ng", "j", "ch", "k", "t", "p", "h"
    };

    private static final int FIRST = 0xAC00;
    private static final int LAST = 0xD7A3;
    private static final int INITIAL_COUNT = 19;
    private static final int MEDIAL_COUNT = 21;
    private static final int CODA_COUNT = 28;
    private static final int NO_CODA = 0;
    private static final int SILENT = 11;

    private HangulRomanizer() {
    }

    public static String romanize(String text) {
        StringBuilder out = new StringBuilder(text.length() * 2);
        int[] syllable = new int[3];

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!decompose(c, syllable)) {
                out.append(c);
                continue;
            }
            out.append(INITIALS[syllable[0]]).append(MEDIALS[syllable[1]]);

            int coda = syllable[2];
            if (coda == NO_CODA) continue;

            // A coda before a silent ㅇ is written out, and the hyphen marks the join.
            char next = i + 1 < text.length() ? text.charAt(i + 1) : 0;
            if (startsOnSilent(next)) {
                out.append(LATERAL[coda]).append('-');
            } else {
                out.append(FINALS[coda]);
            }
        }
        return out.toString();
    }

    private static boolean startsOnSilent(char c) {
        if (c < FIRST || c > LAST) return false;
        return (c - FIRST) / (MEDIAL_COUNT * CODA_COUNT) == SILENT;
    }

    private static boolean decompose(char c, int[] out) {
        if (c < FIRST || c > LAST) return false;
        int index = c - FIRST;
        out[0] = index / (MEDIAL_COUNT * CODA_COUNT);
        out[1] = (index % (MEDIAL_COUNT * CODA_COUNT)) / CODA_COUNT;
        out[2] = index % CODA_COUNT;
        return out[0] < INITIAL_COUNT;
    }
}
