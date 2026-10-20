/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3750
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import androidx.annotation.Nullable;

import java.util.List;

/**
 * Decides which script a line is written in, since a romanizer is only correct for one of them
 * and the wrong one reads as a translation of something else. Lines are judged on their own:
 * a song mixes scripts, which is what a verdict for the whole song gets wrong.
 *
 * <p>The counters are indexed by {@link Script}, so a new script only needs a line in {@link #classify}.
 */
public final class ScriptDetector {

    public enum Script {
        /** Han with too little kana to be Japanese, which is how Chinese reads. */
        HAN,
        JAPANESE,
        KOREAN,
        CYRILLIC,
        GEORGIAN,
        ARMENIAN,
        GREEK,
        /** Latin script, digits or punctuation only: nothing here needs a romanizer. */
        OTHER
    }

    private static final Script[] SCRIPTS = Script.values();

    /** Kana below this, in a line of Han characters, is a loanword rather than Japanese. */
    private static final int MIN_KANA = 3;
    private static final int KANA_PER_HAN = 6;

    /** Hiragana is written for grammar and never for a loanword, so its count settles a line. */
    private static final int HIRAGANA = SCRIPTS.length;
    private static final int COUNTS = SCRIPTS.length + 1;

    private ScriptDetector() {
    }

    @Nullable
    public static Script detect(String line) {
        if (line == null) return null;
        return classify(count(line));
    }

    public static Script detect(List<String> lines) {
        int[] total = new int[COUNTS];
        for (String line : lines) {
            if (line == null) continue;
            add(total, count(line));
        }
        return classify(total);
    }

    private static int[] count(CharSequence line) {
        int[] found = new int[COUNTS];
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (isHan(c)) {
                found[Script.HAN.ordinal()]++;
            } else if (isKana(c)) {
                found[Script.JAPANESE.ordinal()]++;
                if (isHiragana(c)) found[HIRAGANA]++;
            } else if (isHangul(c)) {
                found[Script.KOREAN.ordinal()]++;
            } else if (isCyrillic(c)) {
                found[Script.CYRILLIC.ordinal()]++;
            } else if (isGeorgian(c)) {
                found[Script.GEORGIAN.ordinal()]++;
            } else if (isArmenian(c)) {
                found[Script.ARMENIAN.ordinal()]++;
            } else if (isGreek(c)) {
                found[Script.GREEK.ordinal()]++;
            }
        }
        return found;
    }

    private static void add(int[] total, int[] found) {
        for (int i = 0; i < found.length; i++) {
            total[i] += found[i];
        }
    }

    private static Script classify(int[] found) {
        int han = found[Script.HAN.ordinal()];
        int kana = found[Script.JAPANESE.ordinal()];
        int hangul = found[Script.KOREAN.ordinal()];
        int cyrillic = found[Script.CYRILLIC.ordinal()];
        int georgian = found[Script.GEORGIAN.ordinal()];
        int armenian = found[Script.ARMENIAN.ordinal()];
        int greek = found[Script.GREEK.ordinal()];

        if (hangul > 0 && hangul * 2 >= han + kana) return Script.KOREAN;
        // Kana with no Han at all is Japanese: nothing else that uses kana has none. Hiragana settles a
        // line with Han too, being what grammar is written in and what no language borrows a word into.
        // Katakana a Chinese line carries for a loanword, so it is only counted.
        if (kana > 0 && (han == 0 || found[HIRAGANA] > 0
                || (kana >= MIN_KANA && kana * KANA_PER_HAN >= han))) {
            return Script.JAPANESE;
        }
        if (han > 0) return Script.HAN;
        // Each of these is a block of its own, so none of them can crowd the others out.
        if (cyrillic > 0) return Script.CYRILLIC;
        if (georgian > 0) return Script.GEORGIAN;
        if (armenian > 0) return Script.ARMENIAN;
        return greek > 0 ? Script.GREEK : Script.OTHER;
    }

    /** The blocks a modern lyric line is written with. */
    static boolean isHan(char c) {
        return (c >= 0x4E00 && c <= 0x9FFF)
                || (c >= 0x3400 && c <= 0x4DBF)
                || (c >= 0xF900 && c <= 0xFAFF);
    }

    static boolean isKana(char c) {
        return (c >= 0x3040 && c <= 0x309F)
                || (c >= 0x30A0 && c <= 0x30FF)
                || (c >= 0x31F0 && c <= 0x31FF);
    }

    static boolean isHangul(char c) {
        return c >= 0xAC00 && c <= 0xD7A3;
    }

    static boolean isHiragana(char c) {
        return (c >= 0x3041 && c <= 0x3096) || c == 0x309D || c == 0x309E;
    }

    static boolean isGreek(char c) {
        return (c >= 0x0370 && c <= 0x03FF) || (c >= 0x1F00 && c <= 0x1FFF);
    }

    static boolean isCyrillic(char c) {
        return (c >= 0x0400 && c <= 0x04FF) || (c >= 0x0500 && c <= 0x052F);
    }

    static boolean isGeorgian(char c) {
        return (c >= 0x10A0 && c <= 0x10FF) || (c >= 0x1C90 && c <= 0x1CBF);
    }

    static boolean isArmenian(char c) {
        return c >= 0x0530 && c <= 0x058F;
    }
}
