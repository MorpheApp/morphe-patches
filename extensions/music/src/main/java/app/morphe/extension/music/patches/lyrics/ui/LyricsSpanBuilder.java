/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Paint;
import android.icu.text.BreakIterator;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.romanizers.LocalRomanizer;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.settings.Settings;

final class LyricsSpanBuilder {
    private static final float TRANSLATION_RELATIVE_SIZE = 0.7f;
    private static final float MAIN_LINE_SPACING = 1.3f;

record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji) {
    }

    record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
            int transStart, int transEnd, int romaStart, int romaEnd,
            @Nullable List<WordTiming> readingSpans, boolean smallRunHoldsWords) {

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, null, false);
        }

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd,
                @Nullable List<WordTiming> readingSpans) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, readingSpans, false);
        }
    }

    private record Spacing(int mainClear, int smallClear, int boundaryClear) {
    }

    private static final class UniformLineSpacingSpan implements LineHeightSpan {
        private final Spacing spacing;
        private int baseDescent;
        private int lastWritten = Integer.MIN_VALUE;
        private int previousEnd = -1;

        UniformLineSpacingSpan(Spacing spacing) {
            this.spacing = spacing;
        }

        @Override
        public void chooseHeight(CharSequence text, int start, int end, int spanstartv, int v,
                Paint.FontMetricsInt fm) {
            if (end >= text.length()) {
                return;
            }
            final boolean paragraphStart = start == 0 || text.charAt(start - 1) == '\n';
            if (paragraphStart) {
                baseDescent = fm.descent;
            }
            final boolean carried = !paragraphStart && start == previousEnd
                    && fm.descent == lastWritten;
            final int from = carried ? baseDescent : Math.max(baseDescent, fm.descent);
            final boolean small = isSmallAt(text, start);
            final boolean nextSmall = isSmallAt(text, end);
            final int clearance;
            if (small) {
                clearance = nextSmall ? spacing.smallClear() : spacing.boundaryClear();
            } else {
                clearance = nextSmall ? spacing.boundaryClear() : spacing.mainClear();
            }
            baseDescent = Math.max(baseDescent, from);
            lastWritten = from + clearance;
            fm.descent = lastWritten;
            if (fm.bottom < fm.descent) {
                fm.bottom = fm.descent;
            }
            previousEnd = end;
        }
    }

    private static boolean isSmallAt(CharSequence text, int offset) {
        if (!(text instanceof Spanned spanned)) {
            return false;
        }
        final int window = Math.min(offset + 1, text.length());
        final RelativeSizeSpan[] spans = spanned.getSpans(offset, window, RelativeSizeSpan.class);
        for (RelativeSizeSpan span : spans) {
            final int spanStart = spanned.getSpanStart(span);
            final int spanEnd = spanned.getSpanEnd(span);
            if (spanStart <= offset && offset < spanEnd) {
                return true;
            }
        }
        return false;
    }

    /**
     * The reading shown for a line, and where each word's own reading sits inside it.
     *
     * <p>Reading it off the words rather than off the stored reading keeps the two the same
     * string, so the highlight can follow the words without having to guess where a reading
     * of its own would land.
     */
    record Reading(@Nullable String text, @Nullable List<WordTiming> spans) {
    }

    /**
     * @param timings Null when the caller wants the reading's text alone: they are what a
     *                whole-line reading is spread over.
     */
    static Reading readingFor(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, boolean perWordRomaji,
            @Nullable List<WordTiming> timings) {
        if (perWordRomaji) {
            final List<Word> words = readingWords(line, index, romanizedLines);
            // A word shown as written stands in for a reading only where the line is really read per word.
            if (!words.isEmpty() && anyReading(words)) {
                final StringBuilder text = new StringBuilder();
                final List<WordTiming> spans = new ArrayList<>(words.size());
                final String original = line.text();
                int at = 0;
                String previous = null;
                for (Word word : words) {
                    final String reading = word.romaji() != null ? word.romaji() : word.text().trim();
                    if (reading.isEmpty()) {
                        continue;
                    }
                    // The line's own spacing tells a word handed over in pieces from one written whole; an
                    // unplaced word keeps the gap.
                    final String own = word.text().trim();
                    final int found = own.isEmpty() ? -1 : original.indexOf(own, at);
                    //noinspection SizeReplaceableByIsEmpty
                    if (text.length() > 0 && !sameWrittenWord(at, found, previous, own)) {
                        text.append(' ');
                    }
                    final int start = text.length();
                    text.append(reading);
                    spans.add(new WordTiming(start, text.length(), word.startMs(), word.endMs(), null));
                    if (found >= 0) {
                        at = found + own.length();
                        previous = own;
                    }
                }
                if (!spans.isEmpty()) {
                    return new Reading(text.toString(), closeReadingGaps(spans, line));
                }
            }
        }
        return wholeLineReading(line, index, romanizedLines, timings);
    }

    /**
     * The stored string stays the one drawn, so the words are measured against it and not
     * against the original.
     */
    private static Reading wholeLineReading(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, @Nullable List<WordTiming> timings) {
        final String whole = lineRomanizationAt(line, index, romanizedLines);
        if (whole == null || timings == null || timings.isEmpty()) {
            return new Reading(whole, null);
        }
        return new Reading(whole, spreadOverWords(whole, line.text(), timings));
    }

    private static boolean anyReading(List<Word> words) {
        for (Word word : words) {
            final String romaji = word.romaji();
            if (romaji != null && !romaji.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether two written pieces are one word, which is the case only when the line has them side by
     * side. Han, Hangul and kana keep their gap either way, being a syllable each; letters are the
     * word they spell and must not be read apart.
     */
    private static boolean sameWrittenWord(int from, int start,
            @Nullable String previous, String current) {
        if (previous == null || start != from) return false;
        return !spellsSyllable(previous) && !spellsSyllable(current);
    }

    private static boolean spellsSyllable(String piece) {
        return isCjkAt(piece, 0) || isCjkAt(piece, piece.length() - 1);
    }

    private static boolean isCjkAt(String text, int index) {
        final char c = text.charAt(index);
        if (Character.isHighSurrogate(c) && index + 1 < text.length()
                && Character.isLowSurrogate(text.charAt(index + 1))) {
            return isCjkCodePoint(Character.toCodePoint(c, text.charAt(index + 1)));
        }
        return isCjkCodePoint(c);
    }

    /** An aligned reading rides in the romanization's own lines; the lyric's own words are read
     * only when they already carry one, as a provider's ruby does. */
    private static List<Word> readingWords(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines != null && index < romanizedLines.size()) {
            final List<Word> words = romanizedLines.get(index).words();
            if (!words.isEmpty()) {
                return words;
            }
        }
        return line.words();
    }

    private static List<WordTiming> closeReadingGaps(List<WordTiming> spans, LyricsLine line) {
        final List<Word> words = line.words();
        final List<WordTiming> closed = new ArrayList<>(spans.size());
        for (int i = 0; i < spans.size(); i++) {
            final WordTiming span = spans.get(i);
            long end = i + 1 < spans.size() ? spans.get(i + 1).startMs() : -1L;
            if (end < 0 && !words.isEmpty()) {
                end = words.get(words.size() - 1).endMs();
            }
            closed.add(end > span.endMs()
                    ? new WordTiming(span.start(), span.end(), span.startMs(), end, span.romaji())
                    : span);
        }
        return closed;
    }

    static List<WordTiming> computeWordTimings(List<LyricsLine> lines, int index,
            boolean splitWordlessLine) {
        final LyricsLine line = lines.get(index);
        if (countWords(line.text()) == 1) {
            long rangeStart = line.startTimeMs();
            if (rangeStart < 0 && line.hasWords()) {
                rangeStart = line.words().get(0).startMs();
            }
            long rangeEnd = line.endTimeMs();
            if (rangeEnd <= rangeStart) {
                rangeEnd = index + 1 < lines.size()
                        ? lines.get(index + 1).startTimeMs()
                        : LyricsLine.NO_TIME;
            }
            if (rangeEnd <= rangeStart && line.hasWords()) {
                rangeEnd = line.words().get(line.words().size() - 1).endMs();
            }
            if (rangeStart >= 0 && rangeEnd > rangeStart) {
                final List<WordTiming> synthesized =
                        synthesizeLineChars(line.text(), rangeStart, rangeEnd);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            }
        } else if (splitWordlessLine && !line.hasWords()) {
            // An only or dual mode shows the translation or the reading in its main slot while
            // the timings it lights by are still the original's, so a line carrying no word
            // timings leaves that slot with nothing to light. Splitting it is what fills it.
            final long lineStart = line.startTimeMs();
            final long lineEnd = line.endTimeMs() > lineStart ? line.endTimeMs()
                    : index + 1 < lines.size() ? lines.get(index + 1).startTimeMs()
                            : LyricsLine.NO_TIME;
            if (lineStart >= 0 && lineEnd > lineStart) {
                final List<WordTiming> synthesized =
                        synthesizeLineWords(line.text(), lineStart, lineEnd);
                if (!synthesized.isEmpty()) {
                    return synthesized;
                }
            }
        }
        return computeWordTimingsFromWords(line);
    }

    /**
     * Spreads a line's own span across its words.
     *
     * <p>A Han, Hangul or kana character is said as a syllable of its own and so counts as a word
     * on its own, the same way {@link #countWords} counts it. Letters are said as the word they
     * spell, so they are taken a run at a time.
     */
    private static List<WordTiming> synthesizeLineWords(String text, long startMs, long endMs) {
        final int[] bounds = wordBoundaries(text);
        final List<WordTiming> timings = new ArrayList<>();
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
            timings.add(new WordTiming(bounds[i], bounds[i + 1], wordStart,
                    Math.max(wordEnd, wordStart + 1), null));
            seen++;
        }
        return timings;
    }

    /** Whether a span between two boundaries carries anything that is said. */
    private static boolean isWord(String text, int start, int end) {
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

    /** Where each word of a line begins and ends, counted the way it is said. */
    private static int[] wordBoundaries(String text) {
        final List<Integer> bounds = new ArrayList<>();
        int runStart = -1;
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            final int charCount = Character.charCount(codePoint);
            if (isCjkCodePoint(codePoint)) {
                if (runStart >= 0) {
                    bounds.add(runStart);
                    bounds.add(i);
                    runStart = -1;
                }
                bounds.add(i);
                bounds.add(i + charCount);
            } else if (Character.isWhitespace(codePoint)) {
                if (runStart >= 0) {
                    bounds.add(runStart);
                    bounds.add(i);
                    runStart = -1;
                }
            } else if (runStart < 0) {
                runStart = i;
            }
            i += charCount;
        }
        if (runStart >= 0) {
            bounds.add(runStart);
            bounds.add(text.length());
        }
        final int[] result = new int[bounds.size()];
        for (int i = 0; i < bounds.size(); i++) {
            result[i] = bounds.get(i);
        }
        return result;
    }

    private static List<WordTiming> synthesizeLineChars(String text, long startMs, long endMs) {
        final int[] boundaries = clusterBoundaries(text);
        final int clusterCount = boundaries.length - 1;
        if (clusterCount < 2 || endMs <= startMs) {
            return Collections.emptyList();
        }
        final List<WordTiming> timings = new ArrayList<>(clusterCount);
        for (int i = 0; i < clusterCount; i++) {
            final long clusterStart = startMs + (endMs - startMs) * i / clusterCount;
            final long clusterEnd = i + 1 == clusterCount
                    ? endMs
                    : startMs + (endMs - startMs) * (i + 1) / clusterCount;
            timings.add(new WordTiming(boundaries[i], boundaries[i + 1], clusterStart, clusterEnd, null));
        }
        return timings;
    }

    private static int[] clusterBoundaries(String text) {
        final BreakIterator iterator = BreakIterator.getCharacterInstance();
        iterator.setText(text);
        final List<Integer> bounds = new ArrayList<>();
        bounds.add(0);
        for (int boundary = iterator.first();
                boundary != BreakIterator.DONE;
                boundary = iterator.next()) {
            if (boundary > 0) {
                bounds.add(boundary);
            }
        }
        final int[] result = new int[bounds.size()];
        for (int i = 0; i < bounds.size(); i++) {
            result[i] = bounds.get(i);
        }
        return result;
    }

    private static int countWords(String text) {
        int words = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            final int codePoint = text.codePointAt(i);
            i += Character.charCount(codePoint);
            if (Character.isWhitespace(codePoint)) {
                if (inWord) {
                    words++;
                    inWord = false;
                }
                continue;
            }
            if (isCjkCodePoint(codePoint)) {
                if (inWord) {
                    words++;
                    inWord = false;
                }
                words++;
                continue;
            }
            inWord = true;
        }
        return words + (inWord ? 1 : 0);
    }

    private static boolean isCjkCodePoint(int codePoint) {
        return (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF)
                || (codePoint >= 0xAC00 && codePoint <= 0xD7A3)
                || (codePoint >= 0x3040 && codePoint <= 0x30FF)
                || (codePoint >= 0x31F0 && codePoint <= 0x31FF);
    }

    private static List<WordTiming> computeWordTimingsFromWords(LyricsLine line) {
        if (!line.hasWordTimings()) {
            return Collections.emptyList();
        }

        List<WordTiming> timings = new ArrayList<>(line.words().size());
        String text = line.text();
        int textLength = text.length();
        int offset = 0;
        for (Word word : line.words()) {
            String wordText = word.text();
            int wordLength = wordText.length();
            if (wordLength == 0) {
                continue;
            }
            int start = text.indexOf(wordText, offset);
            int len = wordLength;
            if (start < 0) {
                String trimmed = wordText.trim();
                if (!trimmed.isEmpty()) {
                    start = text.indexOf(trimmed, offset);
                    len = trimmed.length();
                }
            }
            if (start < 0) {
                // Unmatched word: advance past it so following words stay aligned,
                // rather than emitting a span that falls outside the line text.
                offset = Math.min(offset + len, textLength);
                continue;
            }
            int end = Math.min(start + len, textLength);
            if (start >= end) {
                continue;
            }
            timings.add(new WordTiming(start, end, word.startMs(), word.endMs(), word.romaji()));
            offset = end;
        }
        return timings;
    }

    /**
     * Builds the displayed text for a line, appending the translation (when shown) in a
     * smaller, dimmer style and coloring each word sung or unsung for the karaoke
     * highlight.
     *
     * <p>A fresh {@link SpannableString} is returned on every call so that
     * {@link android.widget.TextView#setText(CharSequence)} performs a full re-layout
     * and repaint. Mutating an existing Spannable in place was not reliably redrawn by
     * this TextView, which left the highlight invisible.
     *
     */
    static BuildResult buildLineText(LyricsLine line, List<WordTiming> timings, int index,
            boolean perWordRomaji, List<LyricsLine> romanizedLines, List<String> translatedLines,
            LyricsPanelView.OnlyMode onlyMode, Paint basePaint, boolean isRightAligned) {
        final Paint.FontMetricsInt baseFm = new Paint.FontMetricsInt();
        basePaint.getFontMetricsInt(baseFm);
        String original = line.text();

        // A reading drawn over each word sits where the word's own highlight is painted, so the
        // two land on top of each other. It goes on a line of its own instead, and takes its
        // progress from the same words rather than from a second set of marks.
        final Reading reading = readingFor(line, index, romanizedLines, perWordRomaji, timings);

        final int lineHeight = baseFm.descent - baseFm.ascent;
        final int smallHeight = (int) (lineHeight * TRANSLATION_RELATIVE_SIZE + 0.5f);
        final int smallAscent = (int) (-baseFm.ascent * TRANSLATION_RELATIVE_SIZE + 0.5f);
        final int mainClear = (int) (lineHeight * MAIN_LINE_SPACING + 0.5f) - lineHeight;
        final int smallClear = 0;
        final int boundaryClear =
                (int) (smallHeight * MAIN_LINE_SPACING + 0.5f) - baseFm.descent - smallAscent;
        final Spacing spacing = new Spacing(mainClear, smallClear, boundaryClear);

        String romanization = reading.text();
        String translation = translationAt(line, index, translatedLines);

        // An only mode never drops a line: what the provider left untranslated keeps the
        // original rather than leaving a hole in the song.
        if (onlyMode == LyricsPanelView.OnlyMode.TRANS) {
            final String content = translation != null ? translation : original;
            SpannableString text = new SpannableString(content);
            final int transStart = content.isEmpty() ? -1 : 0;
            final int transEnd = content.isEmpty() ? -1 : content.length();
            attachUniformSpacing(text, spacing);
            if (!content.isEmpty()) {
                text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isRtlText(content), isRightAligned)),
                        0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return new BuildResult(text, null, transStart, transEnd, -1, -1);
        }

        if (onlyMode == LyricsPanelView.OnlyMode.ROMA) {
            final Romanized roma = romanizedWithTimings(line, index, romanizedLines, timings);
            final boolean readsNothing = roma.text() == null;
            final String content = readsNothing ? original : roma.text();
            SpannableString text = new SpannableString(content);
            attachUniformSpacing(text, spacing);
            if (content.isEmpty()) {
                return new BuildResult(text, null, -1, -1, -1, -1);
            }
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isRtlText(content), isRightAligned)),
                    0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // No bounds reported paints the whole line the lyric colour, and none of the reading's fade.
            final List<WordTiming> spans = readsNothing ? timings : roma.spans();
            return new BuildResult(text, null, -1, -1, -1, -1, spans);
        }

        // Both at once: the original goes and whichever side carries more of the line takes
        // the main slot. Decided per line, so a line left untranslated keeps its translation
        // rather than going thin.
        if (onlyMode == LyricsPanelView.OnlyMode.BOTH) {
            final DualLine mainAndSmall =
                    dualLineFor(line, timings, index, translation, original, romanizedLines);
            SpannableString dual = new SpannableString(mainAndSmall.text());
            attachUniformSpacing(dual, spacing);
            final boolean mainIsRoma = mainAndSmall.mainIsRoma();
            final int smallStart = mainAndSmall.smallStart();
            final int smallEnd = mainAndSmall.smallEnd();
            // The small run is the side that is not the main one, and the reading's when it landed there.
            final boolean smallIsRoma = mainAndSmall.smallIsRoma();
            if (smallStart >= 0) {
                // Without this the small run keeps the main size and colour, so the two lines
                // are told apart only by where they sit.
                applySpans(dual, Collections.emptyList(), 0, smallStart - 1,
                        smallIsRoma ? -1 : smallStart, smallIsRoma ? -1 : smallEnd,
                        smallIsRoma ? smallStart : -1, smallIsRoma ? smallEnd : -1,
                        isRightAligned);
            } else {
                boolean isRtl = isRtlText(dual);
                dual.setSpan(new AlignmentSpan.Standard(resolveAlignment(isRtl, isRightAligned)),
                        0, dual.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            // A small run carrying the word timings is what is sung, and keeps the reading's
            // colour, which is what tells it apart: the lyric's colour would leave the two lines
            // looking alike. A reading that never arrived leaves the words on the lyric itself,
            // and a lyric in its own colour is what makes them light up.
            final List<WordTiming> dualSpans = mainAndSmall.wordSpans();
            return new BuildResult(dual, null,
                    smallIsRoma ? -1 : smallStart,
                    smallIsRoma ? -1 : smallEnd,
                    smallIsRoma ? smallStart : -1, smallIsRoma ? smallEnd : -1,
                    dualSpans, mainAndSmall.wordsOnSmall());
        }

        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        StringBuilder builder = new StringBuilder();
        int romaStart = -1;
        int romaEnd = -1;
        int transStart = -1;
        int transEnd = -1;
        if (swap) {
            if (translation != null) {
                transStart = 0;
                builder.append(translation);
                builder.append('\n');
                transEnd = builder.length();
            }
            final int originalStart = builder.length();
            builder.append(original);
            final int originalEnd = builder.length();
            if (romanization != null) {
                builder.append('\n');
                romaStart = builder.length();
                builder.append(romanization);
                romaEnd = builder.length();
            }
            SpannableString text = new SpannableString(builder.toString());
            attachUniformSpacing(text, spacing);
            ForegroundColorSpan unsungSpan = applySpans(text, timings, originalStart, originalEnd,
                    romaStart, romaEnd, transStart, transEnd, isRightAligned);
            return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd,
                    reading.spans());
        }
        if (romanization != null) {
            romaStart = 0;
            builder.append(romanization);
            builder.append('\n');
            romaEnd = builder.length();
        }
        final int originalStart = builder.length();
        builder.append(original);
        final int originalEnd = builder.length();
        if (translation != null) {
            builder.append('\n');
            transStart = builder.length();
            builder.append(translation);
            transEnd = builder.length();
        }

        SpannableString text = new SpannableString(builder.toString());
        attachUniformSpacing(text, spacing);
        ForegroundColorSpan unsungSpan = applySpans(text, timings, originalStart, originalEnd,
                romaStart, romaEnd, transStart, transEnd, isRightAligned);
        return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd,
                reading.spans());
    }

    private static void attachUniformSpacing(SpannableString text, Spacing spacing) {
        if (text.length() <= 0) {
            return;
        }
        text.setSpan(new UniformLineSpacingSpan(spacing), 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** The line's own translation, null when it has none or it only repeats the original
     * One line of the dual mode: the text to show and where the small run sits in it. The copy
     * button reads the same call, so the two cannot disagree about which side is the main line.
     *
     * @param smallStart Where the small run starts, -1 when the line carries only one side.
     * @param wordSpans Null unless something shown here is spoken word by word; when it is not,
     *                  its offsets already measure against {@code text}.
     */
    record DualLine(String text, boolean mainIsRoma, boolean smallIsRoma, int smallStart, int smallEnd,
            @Nullable List<WordTiming> wordSpans, boolean wordsOnSmall) {
    }

    /** Both only-flags standing, so the original goes and the two swap-aware sides take over. */
    @NonNull
    static DualLine dualLineFor(LyricsLine line, @Nullable List<WordTiming> lineTimings, int index,
            @Nullable String translation, String original,
            @Nullable List<LyricsLine> romanizedLines) {
        final Romanized roma = romanizedWithTimings(line, index, romanizedLines, lineTimings);
        final String romanization = roma.text();
        final boolean mainIsRoma;
        final String main;
        // The main slot is what the reading would have held, so a line whose reading never arrived keeps
        // the original rather than a hole, and the translation, spoken as a whole and lighting nothing,
        // steps down to the small run instead of taking a line it could never light.
        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        final String small;
        if (translation != null && romanization != null) {
            mainIsRoma = !swap;
            main = mainIsRoma ? romanization : translation;
            small = mainIsRoma ? translation : romanization;
        } else if (translation != null) {
            // The original takes the reading's slot when the reading never arrived, so the swap
            // still decides which of the two that is: off puts the original above the translation,
            // on puts it below. Swapping this way hands the main slot to the translation, which is
            // what lights the line, so the words move down with the original they belong to.
            mainIsRoma = false;
            main = swap ? translation : original;
            small = swap ? original : translation;
        } else if (romanization != null) {
            mainIsRoma = true;
            main = romanization;
            small = null;
        } else {
            mainIsRoma = false;
            main = original;
            small = null;
        }
        // The words follow the reading, and fall back to the original when there is none; the
        // translation never holds them.
        final List<WordTiming> words = romanization != null ? roma.spans() : lineTimings;
        final boolean wordsOnSmall = romanization != null ? !mainIsRoma : swap;
        if (small == null) {
            return new DualLine(main, mainIsRoma, false, -1, -1, words, false);
        }
        final int smallStart = main.length() + 1;
        return new DualLine(main + "\n" + small, mainIsRoma,
                romanization != null && !mainIsRoma, smallStart,
                smallStart + small.length(),
                shiftSpans(words, wordsOnSmall ? smallStart : 0), wordsOnSmall);
    }

    private static List<WordTiming> shiftSpans(@Nullable List<WordTiming> spans, int by) {
        if (spans == null || spans.isEmpty() || by == 0) {
            return spans;
        }
        final List<WordTiming> shifted = new ArrayList<>(spans.size());
        for (WordTiming span : spans) {
            shifted.add(new WordTiming(span.start() + by, span.end() + by,
                    span.startMs(), span.endMs(), span.romaji()));
        }
        return shifted;
    }

    @Nullable
    static String translationAt(LyricsLine line, int index,
            @Nullable List<String> translatedLines) {
        if (translatedLines == null || index >= translatedLines.size()) {
            return null;
        }
        String raw = translatedLines.get(index);
        if (raw == null) {
            return null;
        }
        final String trimmed = raw.trim();
        return !trimmed.isEmpty() && !trimmed.equalsIgnoreCase(line.text().trim())
                ? trimmed : null;
    }

    /** The line's own romanization, without falling back to the per-word one. */
    @Nullable
    static String lineRomanizationAt(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        if (romanizedLines == null || index >= romanizedLines.size()) {
            return null;
        }
        String roma = romanizedLines.get(index).text().trim();
        return !roma.isEmpty() && !roma.equalsIgnoreCase(line.text().trim()) ? roma : null;
    }

    /** Text and words come back as one, so no word is lit at an offset from another string. */
    private record Romanized(@Nullable String text, @Nullable List<WordTiming> spans) {
    }

    private static Romanized romanizedWithTimings(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines,
            @Nullable List<WordTiming> lineTimings) {
        // The provider's own per-word reading comes first: it has already said which syllable
        // belongs to which word, and only a reading left whole is spread by syllable count.
        final Reading reading = readingFor(line, index, romanizedLines, true, lineTimings);
        return new Romanized(reading.text(), reading.spans());
    }

    /**
     * The words a whole-line reading is said with, measured against that reading. Needed where the
     * source left the reading whole: the line is then drawn word by word and nothing on it can be lit.
     * The timings are the karaoke pass's own, per character where the source gave none, so the
     * reading lands one syllable at a time.
     */
    @Nullable
    private static List<WordTiming> spreadOverWords(String whole, String text,
            @Nullable List<WordTiming> lineTimings) {
        if (lineTimings == null || lineTimings.isEmpty()) {
            return null;
        }
        final int[][] tokens = LocalRomanizer.readingTokens(whole);
        if (tokens.length == 0) {
            return null;
        }
        final List<Word> words = wordsAt(text, lineTimings);
        if (words.isEmpty()) {
            return null;
        }
        final int[][] spread = LocalRomanizer.spreadReading(words, whole);
        if (spread == null) {
            return null;
        }
        final List<WordTiming> spans = new ArrayList<>(tokens.length);
        for (int i = 0; i < words.size(); i++) {
            final Word word = words.get(i);
            final int from = spread[i][0];
            final int to = Math.min(spread[i][1], tokens.length);
            if (to <= from) {
                continue;
            }
            spreadWordWindow(spans, tokens, from, to, word.startMs(),
                    windowEndFor(words, i, word.endMs()));
        }
        return spans.isEmpty() ? null : spans;
    }

    /**
     * Runs to the next word's start: a silence is swept rather than stalled on, and two words
     * whose timings overlap cannot light out of order.
     */
    private static long windowEndFor(List<Word> words, int index, long wordEndMs) {
        if (index + 1 >= words.size()) {
            return wordEndMs;
        }
        final Word word = words.get(index);
        final long nextStart = words.get(index + 1).startMs();
        if (nextStart > word.startMs() && nextStart != wordEndMs) {
            return nextStart;
        }
        return wordEndMs;
    }

    /**
     * Divided by how much each syllable spells: one window for all of them lights them at once,
     * and the lit edge, being the furthest, jumps to the word's last syllable.
     */
    private static void spreadWordWindow(List<WordTiming> out, int[][] tokens,
            int from, int to, long startMs, long endMs) {
        int spelling = 0;
        for (int at = from; at < to; at++) {
            spelling += tokens[at][1] - tokens[at][0];
        }
        final long window = endMs - startMs;
        if (spelling <= 0 || window <= 0) {
            // Nothing to divide by, so the syllables share what there is.
            final long end = Math.max(endMs, startMs + 1);
            for (int at = from; at < to; at++) {
                out.add(new WordTiming(tokens[at][0], tokens[at][1], startMs, end, null));
            }
            return;
        }
        int said = 0;
        long cursor = startMs;
        for (int at = from; at < to; at++) {
            said += tokens[at][1] - tokens[at][0];
            final long share = at + 1 == to
                    ? Math.max(endMs, cursor + 1)
                    : Math.max(startMs + window * said / spelling, cursor + 1);
            out.add(new WordTiming(tokens[at][0], tokens[at][1], cursor, share, null));
            cursor = share;
        }
    }

    /** One word per timing, which is all the placement needs to know about the line. */
    private static List<Word> wordsAt(String text, List<WordTiming> lineTimings) {
        final List<Word> words = new ArrayList<>(lineTimings.size());
        for (WordTiming timing : lineTimings) {
            final int start = timing.start();
            final int end = Math.min(timing.end(), text.length());
            if (start < 0 || start >= end) {
                continue;
            }
            words.add(new Word(timing.startMs(), timing.endMs(), text.substring(start, end),
                    null, false));
        }
        return words;
    }

    /** What a romanization-only line shows: its own, else the per-word one joined up. */
    @Nullable
    static String romanizationForOnlyMode(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        return romanizedWithTimings(line, index, romanizedLines, null).text();
    }

    /**
     * The reading a line shows, laid out beside the line rather than over its words.
     *
     * <p>Only a lyric the user asked to be romanized reports per-word readings, so joining
     * those up cannot put a reading on a line the user never asked for one on.
     */
    @Nullable
    static String lineReading(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, boolean perWordRomaji) {
        return readingFor(line, index, romanizedLines, perWordRomaji, null).text();
    }

    @Nullable
    private static ForegroundColorSpan applySpans(SpannableString text, List<WordTiming> timings,
            int originalStart, int originalEnd,
            int romaStart, int romaEnd, int transStart, int transEnd,
            boolean isRightAligned) {
        ForegroundColorSpan unsungSpan = null;
        if (romaStart >= 0) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), romaStart, romaEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(LyricsColors.secondaryTextColor() | 0xFF000000),
                    romaStart, romaEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), transStart, transEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(LyricsColors.secondaryTextColor() | 0xFF000000),
                    transStart, transEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (Settings.LYRICS_WORD_SYNC.get() && !timings.isEmpty()) {
            int unsung = LyricsColors.unsungWordColor() | 0xFF000000;
            unsungSpan = new ForegroundColorSpan(unsung);
            text.setSpan(unsungSpan, originalStart, originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        if (romaStart >= 0 && romaEnd > romaStart) {
            int spanEnd = (swap && romaEnd == text.length()) ? romaEnd
                    : (romaEnd < text.length() && text.charAt(romaEnd - 1) == '\n' ? romaEnd
                    : Math.min(text.length(), romaEnd + 1));
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(false, isRightAligned)),
                    romaStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (originalStart >= 0 && originalEnd > originalStart) {
            int spanEnd = originalEnd;
            if (!swap && transStart > originalEnd) {
                spanEnd = transStart;
            } else if (swap && romaStart > originalEnd) {
                spanEnd = romaStart;
            }
            boolean isOrigRtl = isRtlText(text.subSequence(originalStart, originalEnd));
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isOrigRtl, isRightAligned)),
                    originalStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0 && transEnd > transStart) {
            int spanEnd = (swap && transEnd < text.length() && text.charAt(transEnd - 1) == '\n') ? transEnd : transEnd;
            if (!swap && transEnd < text.length() && text.charAt(transEnd - 1) != '\n') {
                spanEnd = Math.min(text.length(), transEnd + 1);
            }
            boolean isTransRtl = isRtlText(text.subSequence(transStart, transEnd));
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(isTransRtl, isRightAligned)),
                    transStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return unsungSpan;
    }

    static boolean isRtlText(@Nullable CharSequence text) {
        if (text == null || text.length() == 0) {
            return false;
        }
        return ScriptDetector.isLineRtl(text);
    }

    public static Layout.Alignment resolveAlignment(boolean isRtl, boolean isRightAligned) {
        if (isRightAligned) {
            return isRtl ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
        } else {
            return isRtl ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        }
    }
}
