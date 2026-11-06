/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4323
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;

/**
 * Skips the translation and romanization requests when the lyrics are already written in the
 * requested language. Only the script of each line is read, so a translation is skipped only for
 * languages whose script no other common language shares. Languages written in Latin, Cyrillic,
 * Arabic or Devanagari can't be told apart by script and are always requested.
 */
public final class LyricsLanguageDetector {

    /** Kana below this is a loanword rather than a Japanese line. */
    private static final int MIN_KANA = 2;

    private static final Map<String, Category> UNIQUE_SCRIPT_LANGUAGES = Map.of(
            "zh", Category.HAN,
            "ja", Category.KANA,
            "ko", Category.HANGUL,
            "el", Category.GREEK,
            "he", Category.HEBREW,
            "hy", Category.ARMENIAN,
            "ka", Category.GEORGIAN,
            "th", Category.THAI
    );

    private LyricsLanguageDetector() {
    }

    /** Whether any line is not already written in {@code language}. */
    public static boolean needsTranslation(List<LyricsLine> lines, @Nullable String language) {
        final Category target = UNIQUE_SCRIPT_LANGUAGES.get(LyricsRequests.primarySubtag(language));
        for (LyricsLine line : lines) {
            final int[] found = count(line.text());
            if (hasLetters(found) && (target == null || !isWrittenIn(found, target))) {
                return true;
            }
        }
        return false;
    }

    /** Whether any line has letters outside the Latin script, which is what a reading is written in. */
    public static boolean needsRomanization(List<LyricsLine> lines) {
        for (LyricsLine line : lines) {
            final int[] found = count(line.text());
            for (Category category : Category.values()) {
                if (category != Category.LATIN && found[category.ordinal()] > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * A line mixing in another script is never treated as written in the target language, since
     * telling a borrowed word from a change of language would be a guess. Latin is allowed beside
     * any script, and Japanese is written with Han as well as kana.
     */
    private static boolean isWrittenIn(int[] found, Category target) {
        final int targetCount = found[target.ordinal()];
        if (target == Category.KANA ? targetCount < MIN_KANA : targetCount == 0) {
            return false;
        }
        for (Category category : Category.values()) {
            if (category == target || category == Category.LATIN
                    || (target == Category.KANA && category == Category.HAN)) {
                continue;
            }
            if (found[category.ordinal()] > 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasLetters(int[] found) {
        for (int count : found) {
            if (count > 0) {
                return true;
            }
        }
        return false;
    }

    /** Counts the letters of one line by script. */
    private static int[] count(@Nullable String line) {
        final int[] found = new int[Category.values().length];
        if (line == null) {
            return found;
        }
        for (int i = 0; i < line.length(); ) {
            final int cp = Character.codePointAt(line, i);
            i += Character.charCount(cp);
            final Category category = categoryOf(cp);
            if (category != null) {
                found[category.ordinal()]++;
            }
        }
        return found;
    }

    @Nullable
    private static Category categoryOf(int cp) {
        if (cp <= Character.MAX_VALUE) {
            final char c = (char) cp;
            if (ScriptDetector.isHan(c)) return Category.HAN;
            if (ScriptDetector.isKana(c)) return Category.KANA;
            if (ScriptDetector.isAnyHangul(c)) return Category.HANGUL;
            if (ScriptDetector.isGreek(c)) return Category.GREEK;
            if (ScriptDetector.isHebrew(c)) return Category.HEBREW;
            if (ScriptDetector.isArmenian(c)) return Category.ARMENIAN;
            if (ScriptDetector.isGeorgian(c)) return Category.GEORGIAN;
            if (ScriptDetector.isThai(c)) return Category.THAI;
            if (ScriptDetector.isLatin(c)) return Category.LATIN;
        }
        // Every other script, including Han outside the basic plane.
        return Character.isLetter(cp) ? Category.OTHER : null;
    }

    private enum Category {
        HAN, KANA, HANGUL, GREEK, HEBREW, ARMENIAN, GEORGIAN, THAI, LATIN, OTHER
    }
}
