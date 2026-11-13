/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.icu.text.BreakIterator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;

/**
 * Where the words of a line begin and end, and the word timings synthesized from those
 * boundaries. A CJK character is a syllable and so a word of its own; other text is split at
 * its word breaks.
 */
final class LyricsWordBoundaries {

    private LyricsWordBoundaries() {
    }

    /**
     * Spreads a line's own span across its words, counted as {@link #countWords} counts them.
     *
     * <p>Nothing here comes from the source, so every word is marked averaged.
     */
    static List<WordTiming> synthesizeLineWords(String text, long startMs, long endMs) {
        int[] bounds = wordBoundaries(text);
        List<WordTiming> timings = new ArrayList<>();
        // A run and the character after it share a boundary, and the gaps between words are
        // spelled by the same list, so only the spans that hold text are words.
        int words = 0;
        for (int i = 0; i + 1 < bounds.length; i++) {
            if (isWord(text, bounds[i], bounds[i + 1])) {
                words++;
            }
        }
        if (words == 0) {
            return timings;
        }
        final long span = endMs - startMs;
        int seen = 0;
        for (int i = 0; i + 1 < bounds.length && seen < words; i++) {
            if (!isWord(text, bounds[i], bounds[i + 1])) {
                continue;
            }
            final long wordStart = startMs + span * seen / words;
            final long wordEnd = startMs + span * (seen + 1) / words;
            final boolean trailSpace = bounds[i + 1] < text.length()
                    && Character.isWhitespace(text.charAt(bounds[i + 1]));
            timings.add(new WordTiming(bounds[i], bounds[i + 1], wordStart,
                    Math.max(wordEnd, wordStart + 1), null, trailSpace, true));
            seen++;
        }
        return timings;
    }

    /** Whether a span between two boundaries carries anything that is said. */
    static boolean isWord(String text, int start, int end) {
        if (end <= start) {
            return false;
        }
        for (int i = start; i < end; i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Where each word of a line begins and ends, counted the way it is said. A CJK character is a
     * syllable, so it is a word of its own. Other text is split at its word breaks, so a script
     * written without spaces such as Thai still splits into words, and punctuation stays on the
     * word it touches.
     */
    static int[] wordBoundaries(String text) {
        final List<int[]> spans = hasCjkCodePoint(text) ? cjkWordSpans(text) : wordBreakSpans(text);
        int[] result = new int[spans.size() * 2];
        for (int i = 0; i < spans.size(); i++) {
            result[2 * i] = spans.get(i)[0];
            result[2 * i + 1] = spans.get(i)[1];
        }
        return result;
    }

    /** Splits at whitespace and around every CJK character. */
    static List<int[]> cjkWordSpans(String text) {
        List<int[]> spans = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            final int charCount = Character.charCount(codePoint);
            if (isCjkCodePoint(codePoint)) {
                if (runStart >= 0) {
                    spans.add(new int[]{runStart, i});
                    runStart = -1;
                }
                spans.add(new int[]{i, i + charCount});
            } else if (isWhitespace(codePoint)) {
                if (runStart >= 0) {
                    spans.add(new int[]{runStart, i});
                    runStart = -1;
                }
            } else if (runStart < 0) {
                runStart = i;
            }
            i += charCount;
        }
        if (runStart >= 0) {
            spans.add(new int[]{runStart, text.length()});
        }
        return spans;
    }

    /**
     * Splits at the word breaks of the text. In text with spaces only a space ends a word, as
     * the breaks inside a word such as "don't" are not where it is said apart. Opening
     * punctuation joins the word after it, and other punctuation the word before it.
     */
    static List<int[]> wordBreakSpans(String text) {
        final boolean spaced = text.codePoints().anyMatch(LyricsWordBoundaries::isWhitespace);
        final java.text.BreakIterator breaks = java.text.BreakIterator.getWordInstance(Locale.ROOT);
        breaks.setText(text);

        List<int[]> spans = new ArrayList<>();
        int opening = -1;
        int start = breaks.first();
        for (int end = breaks.next(); end != java.text.BreakIterator.DONE; start = end, end = breaks.next()) {
            final String piece = text.substring(start, end);
            if (piece.codePoints().allMatch(LyricsWordBoundaries::isWhitespace)) continue;
            final boolean letters = piece.codePoints().anyMatch(Character::isLetterOrDigit);
            final boolean joined = start > 0 && !isWhitespace(text.codePointBefore(start));
            final boolean joinsPrevious = joined && !spans.isEmpty() && (spaced || !letters);
            if (!letters && isOpening(piece) && !(spaced && joinsPrevious)) {
                if (opening < 0) opening = start;
                continue;
            }
            if (opening < 0 && joinsPrevious) {
                spans.get(spans.size() - 1)[1] = end;
            } else {
                spans.add(new int[]{opening >= 0 ? opening : start, end});
            }
            opening = -1;
        }
        if (opening >= 0) {
            if (spans.isEmpty()) {
                spans.add(new int[]{opening, text.length()});
            } else {
                spans.get(spans.size() - 1)[1] = text.length();
            }
        }
        return spans;
    }

    static boolean hasCjkCodePoint(String text) {
        return text.codePoints().anyMatch(LyricsWordBoundaries::isCjkCodePoint);
    }

    static boolean isWhitespace(int cp) {
        return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
    }

    static boolean isOpening(String piece) {
        return piece.codePoints().allMatch(cp -> {
            final int type = Character.getType(cp);
            return type == Character.START_PUNCTUATION || type == Character.INITIAL_QUOTE_PUNCTUATION
                    || cp == '"' || cp == '\'' || cp == '„' || cp == '‚' || cp == '¿' || cp == '¡';
        });
    }

    static List<WordTiming> synthesizeLineChars(String text, long startMs, long endMs) {
        int[] boundaries = clusterBoundaries(text);
        final int clusterCount = boundaries.length - 1;
        if (clusterCount < 2 || endMs <= startMs) {
            return Collections.emptyList();
        }
        List<WordTiming> timings = new ArrayList<>(clusterCount);
        for (int i = 0; i < clusterCount; i++) {
            final long clusterStart = startMs + (endMs - startMs) * i / clusterCount;
            final long clusterEnd = i + 1 == clusterCount
                    ? endMs
                    : startMs + (endMs - startMs) * (i + 1) / clusterCount;
            timings.add(new WordTiming(boundaries[i], boundaries[i + 1], clusterStart, clusterEnd, null, false, false));
        }
        return timings;
    }

    static int[] clusterBoundaries(String text) {
        BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        List<Integer> bounds = new ArrayList<>();
        bounds.add(0);
        for (int boundary = iterator.first();
             boundary != BreakIterator.DONE;
             boundary = iterator.next()) {
            if (boundary > 0) {
                bounds.add(boundary);
            }
        }
        int[] result = new int[bounds.size()];
        for (int i = 0; i < bounds.size(); i++) {
            result[i] = bounds.get(i);
        }
        return result;
    }

    static int countWords(String text) {
        int[] bounds = wordBoundaries(text);
        int words = 0;
        for (int i = 0; i + 1 < bounds.length; i++) {
            if (isWord(text, bounds[i], bounds[i + 1])) {
                words++;
            }
        }
        return words;
    }

    /** The ranges live in {@link ScriptDetector}, so a syllable here is a syllable there too. */
    static boolean isCjkCodePoint(int codePoint) {
        return ScriptDetector.isCjkCodePoint(codePoint);
    }

    /** A break may fall before or after it, since the script has no word spaces. */
    static boolean isUnspacedCodePoint(int codePoint) {
        return ScriptDetector.isUnspacedCodePoint(codePoint);
    }

    /** A CJK glyph spells a word on its own, so a neighbouring one is not part of a longer word. */
    static boolean isWordContinuation(char c) {
        return Character.isLetterOrDigit(c) && !isCjkCodePoint(c);
    }

    static boolean isPurePunctuation(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isLetterOrDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }
}
