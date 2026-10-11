/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.ReplacementSpan;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.settings.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

final class LyricsSpanBuilder {
    /** Translation/romanization size relative to the lyrics line it belongs to. */
    private static final float TRANSLATION_RELATIVE_SIZE = 0.7f;
    /** Per-word romanization size relative to the lyrics line it belongs to. */
    private static final float ROMAJI_RELATIVE_SIZE = 0.7f;

record WordTiming(int start, int end, long startMs, long endMs,
            @Nullable String romaji) {
    }

    record BuildResult(Spannable text, @Nullable ForegroundColorSpan unsungSpan,
            int transStart, int transEnd, int romaStart, int romaEnd) {
    }

    private static final class RomajiSpan extends ReplacementSpan {
        private final String romaji;
        private final int color;
        private final float relativeSize;
        private final Paint romajiPaint = new Paint();
        private final Paint.FontMetrics romajiFm = new Paint.FontMetrics();
        private final Paint.FontMetrics wordFm = new Paint.FontMetrics();
        private float cachedRomajiWidth = -1f;
        private float cachedWordWidth = -1f;
        private boolean wordFmCached;

        RomajiSpan(String romaji, int color, float relativeSize) {
            this.romaji = romaji;
            this.color = color;
            this.relativeSize = relativeSize;
        }

        @Override
        public int getSize(Paint paint, CharSequence text, int start, int end,
                Paint.FontMetricsInt fm) {
            cachedWordWidth = (int) Math.ceil(paint.measureText(text, start, end));
            wordFmCached = false;
            if (fm != null) {
                romajiPaint.set(paint);
                romajiPaint.setTextSize(paint.getTextSize() * relativeSize);
                romajiPaint.getFontMetrics(romajiFm);
                final int romajiHeight = (int) Math.ceil(romajiFm.descent - romajiFm.ascent);
                final int gap = Math.max(1, (int) (paint.getTextSize() * 0.1f));
                final int reserve = romajiHeight + gap;
                fm.ascent -= reserve;
                fm.top -= reserve;
            }
            return (int) cachedWordWidth;
        }

        @Override
        public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end,
                float x, int top, int y, int bottom, @NonNull Paint paint) {
            romajiPaint.set(paint);
            romajiPaint.setTextSize(paint.getTextSize() * relativeSize);
            romajiPaint.setColor(color);

            if (cachedRomajiWidth < 0f) {
                cachedRomajiWidth = romajiPaint.measureText(romaji);
            }
            final float wordWidth = cachedWordWidth >= 0f
                    ? cachedWordWidth : paint.measureText(text, start, end);
            final float romajiX = x + Math.max(0f, (wordWidth - cachedRomajiWidth) / 2f);

            if (!wordFmCached) {
                romajiPaint.getFontMetrics(romajiFm);
                paint.getFontMetrics(wordFm);
                wordFmCached = true;
            }
            final int gap = Math.max(1, (int) (paint.getTextSize() * 0.1f));
            final float romajiBaseline = y + wordFm.ascent - romajiFm.descent - gap;

            canvas.drawText(romaji, romajiX, romajiBaseline, romajiPaint);
            canvas.drawText(text, start, end, x, y, paint);
        }
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
        final int charCount = text.codePointCount(0, text.length());
        if (charCount < 2 || endMs <= startMs) {
            return Collections.emptyList();
        }
        final List<WordTiming> timings = new ArrayList<>(charCount);
        int offset = 0;
        for (int i = 0; i < charCount; i++) {
            final int next = offset + Character.charCount(text.codePointAt(offset));
            final long charStart = startMs + (endMs - startMs) * i / charCount;
            final long charEnd = i + 1 == charCount
                    ? endMs
                    : startMs + (endMs - startMs) * (i + 1) / charCount;
            timings.add(new WordTiming(offset, next, charStart, charEnd, null));
            offset = next;
        }
        return timings;
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
            LyricsPanelView.OnlyMode onlyMode) {
        String original = line.text();
        String originalTrimmed = original.trim();

        final boolean usePerWord = perWordRomaji && line.hasWords() && lineHasWordRomaji(line);

        String romanization = null;
        if (!usePerWord && romanizedLines != null && index < romanizedLines.size()) {
            String roma = romanizedLines.get(index).text().trim();
            if (!roma.isEmpty() && !roma.equalsIgnoreCase(originalTrimmed)) {
                romanization = roma;
            }
        }

        List<String> translated = translatedLines;
        String translation = null;
        if (translated != null && index < translated.size()) {
            String raw = translated.get(index);
            String t = raw == null ? "" : raw.trim();
            if (!t.isEmpty() && !t.equalsIgnoreCase(originalTrimmed)) {
                translation = t;
            }
        }

        if (onlyMode == LyricsPanelView.OnlyMode.TRANS) {
            final String content = translation != null ? translation : "";
            SpannableString text = new SpannableString(content);
            final int transStart = content.isEmpty() ? -1 : 0;
            final int transEnd = content.isEmpty() ? -1 : content.length();
            return new BuildResult(text, null, transStart, transEnd, -1, -1);
        }

        if (onlyMode == LyricsPanelView.OnlyMode.ROMA) {
            String content = null;
            if (romanizedLines != null && index < romanizedLines.size()) {
                String roma = romanizedLines.get(index).text().trim();
                if (!roma.isEmpty() && !roma.equalsIgnoreCase(originalTrimmed)) {
                    content = roma;
                }
            }
            if (content == null && line.hasWords()) {
                content = joinWordRomaji(line);
            }
            if (content == null) {
                content = "";
            }
            SpannableString text = new SpannableString(content);
            final int romaStart = content.isEmpty() ? -1 : 0;
            final int romaEnd = content.isEmpty() ? -1 : content.length();
            return new BuildResult(text, null, -1, -1, romaStart, romaEnd);
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
            ForegroundColorSpan unsungSpan = applySpans(text, timings, originalStart, originalEnd,
                    romaStart, romaEnd, transStart, transEnd, usePerWord);
            return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd);
        }
        if (romanization != null) {
            romaStart = 0;
            builder.append(romanization);
            romaEnd = builder.length();
            builder.append('\n');
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
        ForegroundColorSpan unsungSpan = applySpans(text, timings, originalStart, originalEnd,
                romaStart, romaEnd, transStart, transEnd, usePerWord);
        return new BuildResult(text, unsungSpan, transStart, transEnd, romaStart, romaEnd);
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
            int romaStart, int romaEnd, int transStart, int transEnd,
            boolean usePerWord) {
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

        if (usePerWord) {
            final int romajiColor = LyricsColors.secondaryTextColor() | 0xFF000000;
            for (WordTiming timing : timings) {
                if (timing.romaji() != null && !timing.romaji().isEmpty()) {
                    text.setSpan(new RomajiSpan(timing.romaji(), romajiColor, ROMAJI_RELATIVE_SIZE),
                            originalStart + timing.start(), originalStart + timing.end(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        return unsungSpan;
    }

    static boolean lineHasWordRomaji(LyricsLine line) {
        if (!line.hasWords()) {
            return false;
        }
        for (Word word : line.words()) {
            if (word.romaji() != null && !word.romaji().isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
