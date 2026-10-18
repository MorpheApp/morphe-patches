/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Paint;
import android.icu.text.BreakIterator;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.LineHeightSpan;
import android.text.style.RelativeSizeSpan;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.settings.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LyricsSpanBuilder {
    private static final float TRANSLATION_RELATIVE_SIZE = 0.7f;
    private static final float MAIN_LINE_SPACING = 1.3f;

record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji) {
    }

    record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
            int transStart, int transEnd, int romaStart, int romaEnd,
            @Nullable List<WordTiming> readingSpans) {

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                int transStart, int transEnd, int romaStart, int romaEnd) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, null);
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

    static Reading readingFor(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines, boolean perWordRomaji) {
        if (perWordRomaji && line.hasWords()) {
            final StringBuilder text = new StringBuilder();
            final List<WordTiming> spans = new ArrayList<>(line.words().size());
            for (Word word : line.words()) {
                final String reading = word.romaji();
                if (reading == null || reading.isEmpty()) {
                    continue;
                }
                if (text.length() > 0) {
                    text.append(' ');
                }
                final int start = text.length();
                text.append(reading);
                spans.add(new WordTiming(start, text.length(), word.startMs(), word.endMs(), null));
            }
            if (!spans.isEmpty()) {
                return new Reading(text.toString(), closeReadingGaps(spans, line));
            }
        }
        return new Reading(lineRomanizationAt(line, index, romanizedLines), null);
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

    static List<WordTiming> computeWordTimings(List<LyricsLine> lines, int index) {
        final LyricsLine line = lines.get(index);
        if (isSingleWord(line.text())) {
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
        }
        return computeWordTimingsFromWords(line);
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

    private static boolean isSingleWord(String text) {
        return countWords(text) == 1;
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
        if (!line.hasWords()) {
            return Collections.emptyList();
        }

        if (line.words().size() == 1) {
            Word single = line.words().get(0);
            if (isWholeLineWord(single.text(), line.text())) {
                return Collections.emptyList();
            }
            if (single.endMs() <= single.startMs()) {
                return Collections.emptyList();
            }
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

    private static boolean isWholeLineWord(String wordText, String lineText) {
        return compactText(wordText).equalsIgnoreCase(compactText(lineText));
    }

    private static String compactText(String value) {
        final StringBuilder builder = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            if (!Character.isWhitespace(c)) {
                builder.append(c);
            }
        }
        return builder.toString();
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
            LyricsPanelView.OnlyMode onlyMode, Paint basePaint) {
        final Paint.FontMetricsInt baseFm = new Paint.FontMetricsInt();
        basePaint.getFontMetricsInt(baseFm);
        String original = line.text();

        // A reading drawn over each word sits where the word's own highlight is painted, so the
        // two land on top of each other. It goes on a line of its own instead, and takes its
        // progress from the same words rather than from a second set of marks.
        final Reading reading = readingFor(line, index, romanizedLines, perWordRomaji);

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
            return new BuildResult(text, null, transStart, transEnd, -1, -1);
        }

        if (onlyMode == LyricsPanelView.OnlyMode.ROMA) {
            String content = romanizationForOnlyMode(line, index, romanizedLines);
            if (content == null) {
                content = original;
            }
            SpannableString text = new SpannableString(content);
            final int romaStart = content.isEmpty() ? -1 : 0;
            final int romaEnd = content.isEmpty() ? -1 : content.length();
            attachUniformSpacing(text, spacing);
            return new BuildResult(text, null, -1, -1, romaStart, romaEnd);
        }

        // Both at once: the original goes and whichever side carries more of the line takes
        // the main slot. Decided per line, so a line left untranslated keeps its translation
        // rather than going thin.
        if (onlyMode == LyricsPanelView.OnlyMode.BOTH) {
            final DualLine mainAndSmall =
                    dualLineFor(line, index, translation, original, romanizedLines);
            SpannableString dual = new SpannableString(mainAndSmall.text());
            attachUniformSpacing(dual, spacing);
            final boolean mainIsRoma = mainAndSmall.mainIsRoma();
            final int smallStart = mainAndSmall.smallStart();
            final int smallEnd = mainAndSmall.smallEnd();
            if (smallStart >= 0) {
                // Without this the small run keeps the main size and colour, so the two lines
                // are told apart only by where they sit.
                applySpans(dual, Collections.emptyList(), 0, smallStart - 1,
                        mainIsRoma ? -1 : smallStart, mainIsRoma ? -1 : smallEnd,
                        mainIsRoma ? smallStart : -1, mainIsRoma ? smallEnd : -1);
            }
            return new BuildResult(dual, null,
                    mainIsRoma ? smallStart : -1,
                    mainIsRoma ? smallEnd : -1,
                    mainIsRoma ? -1 : smallStart, mainIsRoma ? -1 : smallEnd);
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
                    romaStart, romaEnd, transStart, transEnd);
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
                romaStart, romaEnd, transStart, transEnd);
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

    @SuppressWarnings("SizeReplaceableByIsEmpty")
    /** The line's own translation, null when it has none or it only repeats the original. */
    /**
     * One line of the dual mode: the text to show and where the small run sits in it. The copy
     * button reads the same call, so the two cannot disagree about which side is the main line.
     *
     * @param smallStart Where the small run starts, -1 when the line carries only one side.
     */
    record DualLine(String text, boolean mainIsRoma, int smallStart, int smallEnd) {
    }

    /** Both only-flags standing, so the original goes and the two swap-aware sides take over. */
    @NonNull
    static DualLine dualLineFor(LyricsLine line, int index, @Nullable String translation,
            String original, @Nullable List<LyricsLine> romanizedLines) {
        final String romanization = romanizationForOnlyMode(line, index, romanizedLines);
        final boolean mainIsRoma;
        final String main;
        if (translation != null && romanization != null) {
            mainIsRoma = !Settings.LYRICS_SWAP_TRANS_ROMA.get();
            main = mainIsRoma ? romanization : translation;
        } else if (translation != null) {
            mainIsRoma = false;
            main = translation;
        } else if (romanization != null) {
            mainIsRoma = true;
            main = romanization;
        } else {
            mainIsRoma = true;
            main = original;
        }
        final String small = translation != null && romanization != null
                ? (mainIsRoma ? translation : romanization) : null;
        if (small == null) {
            return new DualLine(main, mainIsRoma, -1, -1);
        }
        return new DualLine(main + "\n" + small, mainIsRoma, main.length() + 1,
                main.length() + 1 + small.length());
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

    /** What a romanization-only line shows: its own, else the per-word one joined up. */
    @Nullable
    static String romanizationForOnlyMode(LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines) {
        final String roma = lineRomanizationAt(line, index, romanizedLines);
        if (roma != null) {
            return roma;
        }
        return line.hasWords() ? joinWordRomaji(line) : null;
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
        return readingFor(line, index, romanizedLines, perWordRomaji).text();
    }

    @Nullable
    private static String joinWordRomaji(LyricsLine line) {
        if (!line.hasWords()) {
            return null;
        }
        StringBuilder sb = null;
        for (Word word : line.words()) {
            final String romaji = word.romaji();
            if (romaji == null || romaji.isEmpty()) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder();
            } else if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(romaji);
        }
        return sb == null || sb.length() == 0 ? null : sb.toString();
    }

    @Nullable
    private static ForegroundColorSpan applySpans(SpannableString text, List<WordTiming> timings,
            int originalStart, int originalEnd,
            int romaStart, int romaEnd, int transStart, int transEnd) {
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

        return unsungSpan;
    }
}
