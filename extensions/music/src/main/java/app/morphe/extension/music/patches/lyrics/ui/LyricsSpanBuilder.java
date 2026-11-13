/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Paint;
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

import app.morphe.extension.music.patches.lyrics.LyricsPresentation;
import app.morphe.extension.music.patches.lyrics.model.LyricsBracketSplitter;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.LyricsTextFormatter;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

public final class LyricsSpanBuilder {
    static final float TRANSLATION_RELATIVE_SIZE = 0.7f;
    /** Furigana is drawn at half the size of the kanji it reads. */
    static final float RUBY_RELATIVE_SIZE = 0.5f;
    static final float MAIN_LINE_SPACING = 1.22f;

    /**
     * @param showSyllableRomanization The reading is drawn under each syllable by the view, and
     *                                 {@code readingSpans} carry it.
     * @param originalStart            Where the original starts in {@code text}, which the word
     *                                 offsets are measured from. Zero when an only mode drops it.
     */
    record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                       int transStart, int transEnd, int romaStart, int romaEnd,
                       @Nullable List<WordTiming> readingSpans, boolean smallRunHoldsWords,
                       boolean showSyllableRomanization, int originalStart) {

        @SuppressWarnings("SameParameterValue")
        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                    int transStart, int transEnd, int romaStart, int romaEnd) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, null);
        }

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                    int transStart, int transEnd, int romaStart, int romaEnd,
                    @Nullable List<WordTiming> readingSpans) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd, readingSpans, false);
        }

        BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
                    int transStart, int transEnd, int romaStart, int romaEnd,
                    @Nullable List<WordTiming> readingSpans, boolean smallRunHoldsWords) {
            this(text, unsungSpan, transStart, transEnd, romaStart, romaEnd,
                    readingSpans, smallRunHoldsWords, false, 0);
        }
    }

    private record Spacing(int mainClear, int smallClear, int boundaryClear) {
    }

    record PresentedLine(BuildResult display, List<WordTiming> words, int originalStart) {
    }

    /**
     * Applies the lyrics text filters to a built line, and moves its spans and word offsets to
     * where they are in the filtered text.
     *
     * @param words Word offsets relative to the original's start.
     */
    static PresentedLine present(BuildResult raw, List<WordTiming> words) {
        final int originalStart = raw.originalStart();
        if (!LyricsPresentation.isFormattingEnabled()) {
            return new PresentedLine(raw, words, originalStart);
        }
        LyricsTextFormatter.Result formatted = LyricsPresentation.format(raw.text().toString());
        if (formatted.text().contentEquals(raw.text())) {
            return new PresentedLine(raw, words, originalStart);
        }
        SpannableString text = new SpannableString(formatted.text());
        for (Object span : raw.text().getSpans(0, raw.text().length(), Object.class)) {
            final int start = formatted.offset(raw.text().getSpanStart(span));
            final int end = formatted.offset(raw.text().getSpanEnd(span));
            if (end > start) text.setSpan(span, start, end, raw.text().getSpanFlags(span));
        }
        final int base = formatted.offset(originalStart);
        List<WordTiming> mapped = new ArrayList<>(words.size());
        for (WordTiming word : words) {
            final int start = formatted.offset(word.start() + originalStart) - base;
            final int end = formatted.offset(word.end() + originalStart) - base;
            if (end > start) mapped.add(new WordTiming(start, end, word.startMs(), word.endMs(),
                    word.romaji(), word.hasTrailingSpace(), word.ruby(), word.averaged()));
        }
        return new PresentedLine(new BuildResult(text, raw.unsungSpan(),
                formatted.offset(raw.transStart()), formatted.offset(raw.transEnd()),
                formatted.offset(raw.romaStart()), formatted.offset(raw.romaEnd()),
                raw.readingSpans(), raw.smallRunHoldsWords(), raw.showSyllableRomanization(), base),
                mapped, base);
    }

    private static final class UniformLineSpacingSpan implements LineHeightSpan {
        private final Spacing spacing;
        private final int blockSplitOffset;
        private int baseDescent;
        private int lastWritten = Integer.MIN_VALUE;
        private int previousEnd = -1;

        UniformLineSpacingSpan(Spacing spacing, int blockSplitOffset) {
            this.spacing = spacing;
            this.blockSplitOffset = blockSplitOffset;
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
            final boolean small = LyricsTextUtils.isSmallAt(text, start);
            // StaticLayout hands `end` as the exclusive line end, which for a line that ends
            // in '\n' points at the newline itself, not at the run after it. Step past
            // newlines so the span standing behind it and any block boundary get seen.
            final int nextOffset = LyricsTextUtils.skipNewlines(text, end);
            final boolean nextSmall = LyricsTextUtils.isSmallAt(text, nextOffset);
            final int clearance;
            if (small) {
                final boolean crossBlock = blockSplitOffset > 0
                        && start < blockSplitOffset && nextOffset >= blockSplitOffset;
                clearance = (nextSmall && !crossBlock) ? spacing.smallClear() : spacing.boundaryClear();
            } else {
                clearance = nextSmall ? -Math.round(from * 0.40f) : spacing.mainClear();
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

    static List<WordTiming> computeWordTimings(List<LyricsLine> lines, int index,
                                               boolean splitWordlessLine) {
        return LyricsWordTimingCalc.computeWordTimings(lines, index, splitWordlessLine);
    }

    static int usableHit(String text, int textLength, int offset, String token,
                         boolean wordTrailingSpace) {
        return LyricsWordTimingCalc.usableHit(text, textLength, offset, token, wordTrailingSpace);
    }

    /**
     * Builds the displayed text for a line, adding the translation and reading (when shown) in a
     * smaller, dimmer style and coloring each word sung or unsung for the karaoke highlight.
     *
     * <p>A fresh {@link SpannableString} is returned on every call so that
     * {@link android.widget.TextView#setText(CharSequence)} performs a full re-layout
     * and repaint. Mutating an existing Spannable in place was not reliably redrawn by
     * this TextView, which left the highlight invisible.
     */
    static BuildResult buildLineText(LyricsLine line, List<WordTiming> timings, int index,
                                     boolean perWordRomaji, List<LyricsLine> romanizedLines,
                                     List<String> translatedLines,
                                     LyricsPanelView.OnlyMode onlyMode, Paint basePaint,
                                     boolean isRightAligned, boolean embeddedReading) {
        Paint.FontMetricsInt baseFm = new Paint.FontMetricsInt();
        basePaint.getFontMetricsInt(baseFm);
        String original = LyricsTextUtils.displayLineText(line);
        final boolean centered = line.isCentered();

        final int lineHeight = baseFm.descent - baseFm.ascent;
        final int mainClear = Math.max(0, (int) (lineHeight * MAIN_LINE_SPACING + 0.5f) - lineHeight);
        final int smallClear = Math.max(0, (int) (lineHeight * TRANSLATION_RELATIVE_SIZE * 0.18f + 0.5f));
        final int boundaryClear = Math.max(Dim.dp(1), Math.round(lineHeight * 0.08f));
        Spacing spacing = new Spacing(mainClear, smallClear, boundaryClear);

        String translation = translationAt(line, index, translatedLines);

        // An only mode never drops a line: what the provider left untranslated keeps the
        // original rather than leaving a hole in the song.
        if (onlyMode == LyricsPanelView.OnlyMode.TRANS) {
            String content = translation != null ? translation : original;
            SpannableString text = new SpannableString(content);
            final int transStart = content.isEmpty() ? -1 : 0;
            final int transEnd = content.isEmpty() ? -1 : content.length();
            attachUniformSpacing(text, spacing);
            if (!content.isEmpty()) {
                text.setSpan(new AlignmentSpan.Standard(
                                resolveAlignment(isRtlText(content), isRightAligned, centered)),
                        0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            return new BuildResult(text, null, transStart, transEnd, -1, -1);
        }

        if (onlyMode == LyricsPanelView.OnlyMode.ROMA) {
            LyricsReadingCalc.Romanized roma = LyricsReadingCalc.romanizedWithTimings(
                    line, index, romanizedLines, timings);
            final boolean readsNothing = roma.text() == null;
            String content = readsNothing ? original : roma.text();
            SpannableString text = new SpannableString(content);
            attachUniformSpacing(text, spacing);
            if (content.isEmpty()) {
                return new BuildResult(text, null, -1, -1, -1, -1);
            }
            text.setSpan(new AlignmentSpan.Standard(
                            resolveAlignment(isRtlText(content), isRightAligned, centered)),
                    0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            // No bounds reported paints the whole line the lyric color, and none of the reading's fade.
            List<WordTiming> spans = readsNothing ? timings : roma.spans();
            return new BuildResult(text, null, -1, -1, -1, -1, spans);
        }

        // Both at once: the original goes and whichever side carries more of the line takes
        // the main slot. Decided per line, so a line left untranslated keeps its translation
        // rather than going thin.
        if (onlyMode == LyricsPanelView.OnlyMode.BOTH) {
            DualLine mainAndSmall =
                    dualLineFor(line, timings, index, translation, original, romanizedLines);
            SpannableString dual = new SpannableString(mainAndSmall.text());
            final int smallStart = mainAndSmall.smallStart();
            final int smallEnd = mainAndSmall.smallEnd();
            // The small run is the side that is not the main one, and the readings when it landed there.
            final boolean smallIsRoma = mainAndSmall.smallIsRoma();
            if (smallStart >= 0) {
                // Without this the small run keeps the main size and color, so the two lines
                // are told apart only by where they sit.
                applySpans(dual, Collections.emptyList(), 0, smallStart - 1,
                        smallIsRoma ? -1 : smallStart, smallIsRoma ? -1 : smallEnd,
                        smallIsRoma ? smallStart : -1, smallIsRoma ? smallEnd : -1,
                        isRightAligned, centered, false);
            } else {
                boolean isRtl = isRtlText(dual);
                dual.setSpan(new AlignmentSpan.Standard(
                                resolveAlignment(isRtl, isRightAligned, centered)),
                        0, dual.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            attachUniformSpacing(dual, spacing);
            // A small run carrying the word timings is what is sung, and keeps the reading's
            // color, which is what tells it apart: the lyric's color would leave the two lines
            // looking alike. A reading that never arrived leaves the words on the lyric itself,
            // and a lyric in its own color is what makes them light up.
            List<WordTiming> dualSpans = mainAndSmall.wordSpans();
            return new BuildResult(dual, null,
                    smallIsRoma ? -1 : smallStart,
                    smallIsRoma ? -1 : smallEnd,
                    smallIsRoma ? smallStart : -1, smallIsRoma ? smallEnd : -1,
                    dualSpans, mainAndSmall.wordsOnSmall());
        }

        // A reading drawn over each word sits where the word's own highlight is painted, so the
        // two land on top of each other. With word sync on, the view draws each syllable's reading
        // under it instead; otherwise the reading goes on a line of its own, and takes its progress
        // from the same words rather than from a second set of marks. Furigana is always drawn
        // over its own word.
        List<WordTiming> syllableRomanTimings = (Settings.LYRICS_WORD_SYNC.get()
                || LyricsReadingCalc.hasAnyRuby(line))
                ? buildSyllableRomanizedTimings(line, index, romanizedLines, perWordRomaji, timings,
                original, embeddedReading)
                : null;
        final boolean showSyllableRomanization = syllableRomanTimings != null;
        LyricsReadingCalc.Reading reading = showSyllableRomanization
                ? new LyricsReadingCalc.Reading(null, syllableRomanTimings)
                : LyricsReadingCalc.readingFor(line, index, romanizedLines, perWordRomaji, timings);
        String romanization = reading.text();

        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        // The reading is drawn over each word when syllable romanization is active, so a
        // separate ruby line would repeat it. Only the line-level ruby goes here.
        final String rubyText = showSyllableRomanization ? null : LyricsReadingCalc.wholeLineRuby(line);
        StringBuilder builder = new StringBuilder();
        int romaStart = -1;
        int romaEnd = -1;
        int rubyStart = -1;
        int rubyEnd = -1;
        int transStart = -1;
        int transEnd = -1;
        if (translation != null && swap) {
            builder.append(translation);
            transStart = 0;
            transEnd = builder.length();
            builder.append('\n');
        }
        if (rubyText != null) {
            rubyStart = builder.length();
            builder.append(rubyText);
            rubyEnd = builder.length();
            builder.append('\n');
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
        if (translation != null && !swap) {
            builder.append('\n');
            transStart = builder.length();
            builder.append(translation);
            transEnd = builder.length();
        }

        SpannableString text = new SpannableString(builder.toString());
        ForegroundColorSpan unsungSpan = applySpans(text,
                showSyllableRomanization ? syllableRomanTimings : timings,
                originalStart, originalEnd, romaStart, romaEnd, transStart, transEnd,
                isRightAligned, centered, line.hasWordTimings());
        final int splitOffset = (!swap && romaStart >= 0 && transStart > romaStart) ? transStart : -1;
        attachUniformSpacing(text, spacing, splitOffset);
        if (rubyStart >= 0 && rubyEnd > rubyStart) {
            text.setSpan(new ForegroundColorSpan(LyricsColors.secondaryTextColor()),
                    rubyStart, rubyEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd,
                reading.spans(), false, showSyllableRomanization, originalStart);
    }

    private static void attachUniformSpacing(SpannableString text, Spacing spacing) {
        attachUniformSpacing(text, spacing, -1);
    }

    private static void attachUniformSpacing(SpannableString text, Spacing spacing, int blockSplitOffset) {
        if (text.length() <= 0) {
            return;
        }
        text.setSpan(new UniformLineSpacingSpan(spacing, blockSplitOffset), 0, text.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * One line of the dual mode: the text to show and where the small run sits in it. The copy
     * button reads the same call, so the two cannot disagree about which side is the main line.
     *
     * @param smallStart Where the small run starts, -1 when the line carries only one side.
     * @param wordSpans  Null unless something shown here is spoken word by word; when it is not,
     *                   its offsets already measure against {@code text}.
     */
    record DualLine(String text, boolean mainIsRoma, boolean smallIsRoma, int smallStart,
                    int smallEnd,
                    @Nullable List<WordTiming> wordSpans, boolean wordsOnSmall) {
    }

    /** Both only-flags standing, so the original goes and the two swap-aware sides take over. */
    @NonNull
    static DualLine dualLineFor(LyricsLine line, @Nullable List<WordTiming> lineTimings, int index,
                                @Nullable String translation, String original,
                                @Nullable List<LyricsLine> romanizedLines) {
        LyricsReadingCalc.Romanized roma = LyricsReadingCalc.romanizedWithTimings(
                line, index, romanizedLines, lineTimings);
        String romanization = roma.text();
        final boolean mainIsRoma;
        String main;
        // The main slot is what the reading would have held, so a line whose reading never arrived keeps
        // the original rather than a hole, and the translation, spoken as a whole and lighting nothing,
        // steps down to the small run instead of taking a line it could never light.
        final boolean swap = Settings.LYRICS_SWAP_TRANS_ROMA.get();
        String small;
        if (translation != null && romanization != null) {
            mainIsRoma = !swap;
            main = mainIsRoma ? romanization : translation;
            small = mainIsRoma ? translation : romanization;
        } else if (translation != null) {
            mainIsRoma = false;
            main = original;
            small = translation;
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
        List<WordTiming> words = romanization != null ? roma.spans() : lineTimings;
        final boolean wordsOnSmall = romanization != null && !mainIsRoma;
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
        List<WordTiming> shifted = new ArrayList<>(spans.size());
        for (WordTiming span : spans) {
            shifted.add(new WordTiming(span.start() + by, span.end() + by,
                    span.startMs(), span.endMs(), span.romaji(), span.hasTrailingSpace(),
                    span.ruby(), span.averaged()));
        }
        return shifted;
    }

    /** The line's own translation, null when it has none, or it only repeats the original. */
    @Nullable
    public static String translationAt(LyricsLine line, int index,
                                       @Nullable List<String> translatedLines) {
        if (translatedLines == null || index >= translatedLines.size()) {
            return null;
        }
        String raw = translatedLines.get(index);
        if (raw == null) {
            return null;
        }
        if (LyricsTextUtils.isSecondaryLine(line)) {
            raw = LyricsBracketSplitter.stripAllBrackets(raw);
            // The lead's translation carried down onto its background vocal would only repeat it.
            if (index > 0) {
                String prevRaw = translatedLines.get(index - 1);
                if (prevRaw != null && prevRaw.trim().equalsIgnoreCase(raw.trim())) {
                    return null;
                }
            }
        }
        String trimmed = raw.trim();
        String origTrimmed = LyricsTextUtils.displayLineText(line).trim();
        return !trimmed.isEmpty() && !trimmed.equalsIgnoreCase(origTrimmed)
                ? trimmed : null;
    }

    @Nullable
    private static ForegroundColorSpan applySpans(SpannableString text, List<WordTiming> timings,
                                                  int originalStart, int originalEnd,
                                                  int romaStart, int romaEnd, int transStart, int transEnd,
                                                  boolean isRightAligned, boolean centered, boolean applyKaraoke) {
        ForegroundColorSpan unsungSpan = null;
        if (romaStart >= 0 && romaEnd > romaStart) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), romaStart, romaEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(LyricsColors.lineTextColor() | 0xFF000000),
                    romaStart, romaEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0 && transEnd > transStart) {
            text.setSpan(new RelativeSizeSpan(TRANSLATION_RELATIVE_SIZE), transStart, transEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            text.setSpan(new ForegroundColorSpan(LyricsColors.lineTextColor() | 0xFF000000),
                    transStart, transEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // The karaoke sweep lights words one by one, but a line that carries no word timings from
        // its source has nothing real to sweep through. Synthesised timings still fill the word
        // spans for snap alignment and romanization, but they must not drive the highlight.
        if (Settings.LYRICS_WORD_SYNC.get() && !timings.isEmpty() && applyKaraoke) {
            int unsung = LyricsColors.unsungWordColor() | 0xFF000000;
            unsungSpan = new ForegroundColorSpan(unsung);
            text.setSpan(unsungSpan, originalStart, originalEnd,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        if (originalStart >= 0 && originalEnd > originalStart) {
            int spanEnd = originalEnd < text.length() && text.charAt(originalEnd) == '\n'
                    ? originalEnd + 1 : originalEnd;
            boolean isOrigRtl = isRtlText(text.subSequence(originalStart, originalEnd));
            text.setSpan(new AlignmentSpan.Standard(
                            resolveAlignment(isOrigRtl, isRightAligned, centered)),
                    originalStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (romaStart >= 0 && romaEnd > romaStart) {
            int spanEnd = romaEnd < text.length() && text.charAt(romaEnd) == '\n'
                    ? romaEnd + 1 : romaEnd;
            text.setSpan(new AlignmentSpan.Standard(resolveAlignment(false, isRightAligned, centered)),
                    romaStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (transStart >= 0 && transEnd > transStart) {
            int spanEnd = transEnd < text.length() && text.charAt(transEnd) == '\n'
                    ? transEnd + 1 : transEnd;
            boolean isTransRtl = isRtlText(text.subSequence(transStart, transEnd));
            text.setSpan(new AlignmentSpan.Standard(
                            resolveAlignment(isTransRtl, isRightAligned, centered)),
                    transStart, spanEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        return unsungSpan;
    }

    @Nullable
    static List<WordTiming> buildSyllableRomanizedTimings(
            LyricsLine line, int index,
            @Nullable List<LyricsLine> romanizedLines,
            boolean perWordRomaji,
            @Nullable List<WordTiming> timings,
            String mainText,
            boolean embeddedReading) {
        return LyricsReadingCalc.buildSyllableRomanizedTimings(line, index, romanizedLines,
                perWordRomaji, timings, mainText, embeddedReading);
    }

    @Nullable
    public static String romanizationForOnlyMode(LyricsLine line, int index,
                                                 @Nullable List<LyricsLine> romanizedLines) {
        return LyricsReadingCalc.romanizationForOnlyMode(line, index, romanizedLines);
    }

    static boolean isRtlText(@Nullable CharSequence text) {
        //noinspection SizeReplaceableByIsEmpty
        if (text == null || text.length() == 0) {
            return false;
        }
        return ScriptDetector.isLineRtl(text);
    }

    /**
     * @param forceCenter The line's own agent says it is centred ({@code v1000}/{@code v2000}), so
     *                    the "Always center lyrics" setting does not decide it.
     */
    public static Layout.Alignment resolveAlignment(boolean isRtl, boolean isRightAligned,
                                                    boolean forceCenter) {
        if (forceCenter || Settings.LYRICS_FORCE_CENTER.get()) {
            return Layout.Alignment.ALIGN_CENTER;
        }
        if (isRightAligned) {
            return isRtl ? Layout.Alignment.ALIGN_NORMAL : Layout.Alignment.ALIGN_OPPOSITE;
        } else {
            return isRtl ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
        }
    }
}
