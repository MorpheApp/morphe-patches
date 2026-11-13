/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ui.Dim;

/**
 * Builds the hand-laid-out view of a line whose syllables each carry a reading: each syllable's
 * reading is laid out under it, the text is broken where a syllable and its reading run out of
 * room, and the readings that are wider than their syllable push the rest of the line along.
 */
final class SyllableRomanLayout {

    private SyllableRomanLayout() {
    }

    /** A word timing and where it sits in the laid out lyric. */
    record AlignedSyllable(WordTiming syllable, int startOffset,
                           int endOffset) {
    }

    /** One syllable's reading, laid out on its own. */
    record RomanizedWordCluster(int wordIndex, int startIndex, int lineIdx,
                                StaticLayout subLayout, float romanWidth) {
    }

    /** One character of the lyric as the main layout places it. */
    record ClusterMeta(int start, int end, int lineIdx, float x, float width) {
    }

    /** A run of the lyric drawn in one piece: a whole syllable, or a character between syllables. */
    record VisualCluster(int lineIdx, float x, float y, float width, StaticLayout subLayout,
                         int syllableIndex, int shiftIndex) {
    }

    /** Where each visual cluster is drawn once the readings have pushed it along. */
    record ClusterStatics(float[] baseX, float[] baseY) {
    }

    record RomanLineStatics(List<RomanizedWordCluster> clusters, float[] drawX, float y) {
    }

    static final class SyllableRomanCache {
        final int widthPx;
        final float fontSizePx;
        /** False when the line has no syllable reading, so it is drawn as plain text. */
        final boolean hasSyllableRomanization;
        @Nullable
        final String mainText;
        final int lineCount;
        final List<AlignedSyllable> alignedSyllables;
        final int extraRomanHeightPx;
        final List<VisualCluster> clusters;
        final ClusterStatics statics;
        final RomanLineStatics[] romanLines;
        final int[] sylLine;
        final float[] sylShiftedLeadX;
        final float[] sylShiftedTrailX;
        final float[] sylProgressScratch;
        @Nullable
        final String transText;
        @Nullable
        final StaticLayout transLayout;
        /** The furigana above each line of the lyric. */
        final RomanLineStatics[] rubyLines;
        /** Where the translation and the lyric start; with the swap on the translation goes first. */
        final int transTopY;
        final int mainTopY;
        final int totalHeightPx;
        final int mainLayoutHeight;

        SyllableRomanCache(int widthPx, float fontSizePx, boolean hasSyllableRomanization,
                           @Nullable String mainText, int lineCount, List<AlignedSyllable> alignedSyllables,
                           int extraRomanHeightPx, List<VisualCluster> clusters, ClusterStatics statics,
                           RomanLineStatics[] romanLines, RomanLineStatics[] rubyLines, int[] sylLine,
                           float[] sylShiftedLeadX, float[] sylShiftedTrailX,
                           @Nullable String transText, @Nullable StaticLayout transLayout,
                           int transTopY, int mainTopY, int totalHeightPx, int mainLayoutHeight) {
            this.widthPx = widthPx;
            this.fontSizePx = fontSizePx;
            this.hasSyllableRomanization = hasSyllableRomanization;
            this.mainText = mainText;
            this.lineCount = lineCount;
            this.alignedSyllables = alignedSyllables;
            this.extraRomanHeightPx = extraRomanHeightPx;
            this.clusters = clusters;
            this.statics = statics;
            this.romanLines = romanLines;
            this.rubyLines = rubyLines;
            this.sylLine = sylLine;
            this.sylShiftedLeadX = sylShiftedLeadX;
            this.sylShiftedTrailX = sylShiftedTrailX;
            this.sylProgressScratch = new float[alignedSyllables.size()];
            this.transText = transText;
            this.transLayout = transLayout;
            this.transTopY = transTopY;
            this.mainTopY = mainTopY;
            this.totalHeightPx = totalHeightPx;
            this.mainLayoutHeight = mainLayoutHeight;
        }

        /** Remembers that a line has nothing to draw this way, so it is not worked out every frame. */
        static SyllableRomanCache none(int widthPx, float fontSizePx) {
            return new SyllableRomanCache(widthPx, fontSizePx, false, null, 0,
                    Collections.emptyList(), 0, Collections.emptyList(),
                    new ClusterStatics(new float[0], new float[0]),
                    new RomanLineStatics[0], new RomanLineStatics[0], new int[0], new float[0], new float[0],
                    null, null, 0, 0, 0, 0);
        }
    }

    /** Laid out far wider than it needs, so a syllable or its reading never wraps a character. */
    private static StaticLayout buildSingleLineLayout(String text, TextPaint paint) {
        final float desiredW = Math.max(1f, Math.max(
                paint.measureText(text),
                Layout.getDesiredWidth(text, paint)));
        final int layoutW = Math.max(16384, (int) Math.ceil(desiredW) + 64);
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, layoutW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(1)
                .build();
    }

    /** Shrinks a reading wider than its line so it doesn't run off the edge. */
    private static StaticLayout buildFittedLayout(String text, TextPaint paint, float maxWidthPx) {
        StaticLayout layout = buildSingleLineLayout(text, paint);
        if (maxWidthPx <= 0f) {
            return layout;
        }
        final float width = singleLineWidth(text, paint, layout);
        if (width <= maxWidthPx) {
            return layout;
        }
        final float scale = maxWidthPx / Math.max(1f, width);
        paint.setTextSize(Math.max(1f, paint.getTextSize() * scale));
        return buildSingleLineLayout(text, paint);
    }

    private static float singleLineWidth(String text, TextPaint paint, StaticLayout layout) {
        final float desiredW = Math.max(paint.measureText(text), Layout.getDesiredWidth(text, paint));
        final float lineW = layout.getLineCount() > 0
                ? (layout.getLineRight(0) - layout.getLineLeft(0)) : 0f;
        final float horizW = text.length() > 0 ? layout.getPrimaryHorizontal(text.length()) : 0f;
        return Math.max(1f, Math.max(desiredW, Math.max(lineW, horizW)));
    }

    static SyllableRomanCache buildSyllableCache(int widthPx, float fontSizePx, String fullStr,
                                                 int origStart, boolean isRightAligned, boolean isCentered,
                                                 List<WordTiming> wordTimings, boolean rubyActive,
                                                 int transStart, int transEnd, TextPaint basePaint) {
        final int newline = fullStr.indexOf('\n', origStart);
        final int origEnd = newline >= 0 ? newline : fullStr.length();
        String rawMainText = fullStr.substring(origStart, origEnd);
        // The readings are placed left to right, which a right-to-left script would scramble.
        if (rawMainText.trim().isEmpty() || ScriptDetector.containsRtl(rawMainText)) {
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        Layout.Alignment mainAlign =
                LyricsSpanBuilder.resolveAlignment(false, isRightAligned, isCentered);

        TextPaint mainPaint = new TextPaint(basePaint);
        mainPaint.setTextSize(fontSizePx);
        LyricsViewSupport.applyLyricsBoldPaint(mainPaint);
        mainPaint.setLetterSpacing(LyricsViewSupport.lineLetterSpacing(
                rawMainText, Settings.LYRICS_TEXT_SIZE.get()));

        TextPaint romPaint = new TextPaint(mainPaint);
        romPaint.setTextSize(fontSizePx * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
        LyricsViewSupport.applyLyricsBoldPaint(romPaint);
        romPaint.setLetterSpacing(0f);
        TextPaint rubyPaint = new TextPaint(mainPaint);
        rubyPaint.setTextSize(fontSizePx * LyricsSpanBuilder.RUBY_RELATIVE_SIZE);
        LyricsViewSupport.applyLyricsBoldPaint(rubyPaint);
        rubyPaint.setLetterSpacing(0f);

        String formattedMainText = buildFormattedMainText(
                rawMainText, wordTimings, mainPaint, romPaint, rubyPaint, widthPx, fontSizePx,
                rubyActive);

        StaticLayout mainLayout = StaticLayout.Builder.obtain(
                        formattedMainText, 0, formattedMainText.length(), mainPaint, widthPx)
                .setAlignment(mainAlign)
                .setIncludePad(false)
                .setLineSpacing(0f, LyricsSpanBuilder.MAIN_LINE_SPACING)
                .build();

        final int lineCount = mainLayout.getLineCount();
        float[] lineLefts = new float[lineCount];
        float[] lineStarts = new float[lineCount];
        float[] lineBaselines = new float[lineCount];
        float[] lineBottoms = new float[lineCount];
        boolean[] lineIsRtl = new boolean[lineCount];

        float totalW = 0f;
        for (int i = 0; i < lineCount; i++) {
            lineLefts[i] = mainLayout.getLineLeft(i);
            lineStarts[i] = totalW;
            totalW += Math.max(1f, mainLayout.getLineRight(i) - lineLefts[i]);
            lineBaselines[i] = mainLayout.getLineBaseline(i);
            lineBottoms[i] = mainLayout.getLineBottom(i);
            lineIsRtl[i] = mainLayout.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT;
        }

        List<AlignedSyllable> alignedSyllables =
                alignSyllablesForFormatting(wordTimings, rawMainText, formattedMainText);
        List<RomanizedWordCluster> romanClusters =
                buildRomanizedWordClusters(alignedSyllables, formattedMainText, mainLayout, romPaint,
                        widthPx);
        List<RomanizedWordCluster> rubyClusters = rubyActive
                ? buildRubyClusters(alignedSyllables, formattedMainText, mainLayout, rubyPaint,
                widthPx)
                : Collections.emptyList();
        if (romanClusters.isEmpty() && rubyClusters.isEmpty()) {
            return SyllableRomanCache.none(widthPx, fontSizePx);
        }

        final boolean hasRuby = !rubyClusters.isEmpty();
        final float lineRubyExtraHeight = hasRuby ? (fontSizePx * 0.90f + Dim.dp(6)) : 0f;
        final int extraRubyHeightPx = Math.round(lineRubyExtraHeight);

        final boolean hasRoman = !romanClusters.isEmpty();
        final float lineRomanExtraHeight = hasRoman ? (fontSizePx * 0.70f) : 0f;
        final float lineExtraHeight = lineRomanExtraHeight + (lineCount > 1 ? lineRubyExtraHeight : 0f);
        final int bottomMargin = hasRoman ? Math.round(fontSizePx * 0.08f) : 0;
        final int extraRomanHeightPx = Math.round(
                (hasRoman ? lineCount * lineRomanExtraHeight : 0f)
                        + Math.max(0, lineCount - 1) * lineRubyExtraHeight) + bottomMargin;

        float[] charXMap = buildCharXMap(
                formattedMainText, mainLayout, lineCount, lineIsRtl, lineStarts);
        float[] romanStartShift = buildRomanStartShift(
                formattedMainText, charXMap, mainLayout, lineCount,
                alignedSyllables, romanClusters, rubyClusters, mainAlign);

        List<VisualCluster> clusters = buildVisualClusters(
                formattedMainText, mainLayout, alignedSyllables, lineBaselines, mainPaint);
        ClusterStatics statics = buildClusterStatics(
                clusters, romanStartShift, lineExtraHeight);
        List<RomanizedWordCluster>[] romanLines =
                groupRomanClustersByLine(romanClusters, lineCount);
        RomanLineStatics[] romanStatics = hasRoman
                ? buildRomanStatics(romanLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight, totalW) : new RomanLineStatics[0];
        RomanLineStatics[] rubyStatics = hasRuby
                ? buildRubyStatics(groupRomanClustersByLine(rubyClusters, lineCount), charXMap,
                romanStartShift, lineBaselines, lineLefts, lineStarts, lineCount, fontSizePx,
                lineExtraHeight, totalW)
                : new RomanLineStatics[0];

        final int sylCount = alignedSyllables.size();
        int[] sylLine = new int[sylCount];
        float[] sylShiftedLeadX = new float[sylCount];
        float[] sylShiftedTrailX = new float[sylCount];
        Arrays.fill(sylShiftedLeadX, Float.NaN);
        Arrays.fill(sylShiftedTrailX, Float.NaN);

        for (int idx = 0; idx < clusters.size(); idx++) {
            VisualCluster vc = clusters.get(idx);
            final int sIdx = vc.syllableIndex();
            if (sIdx >= 0 && sIdx < sylCount) {
                sylLine[sIdx] = vc.lineIdx();
                final float leftX = statics.baseX()[idx];
                final float rightX = leftX + vc.width();
                if (Float.isNaN(sylShiftedLeadX[sIdx]) || leftX < sylShiftedLeadX[sIdx]) {
                    sylShiftedLeadX[sIdx] = leftX;
                }
                if (Float.isNaN(sylShiftedTrailX[sIdx]) || rightX > sylShiftedTrailX[sIdx]) {
                    sylShiftedTrailX[sIdx] = rightX;
                }
            }
        }
        final int maxCharIdx = Math.max(0, formattedMainText.length() - 1);
        for (int sIdx = 0; sIdx < sylCount; sIdx++) {
            if (Float.isNaN(sylShiftedLeadX[sIdx])) {
                AlignedSyllable syl = alignedSyllables.get(sIdx);
                final int li = mainLayout.getLineForOffset(Math.max(0, Math.min(syl.startOffset(), maxCharIdx)));
                sylLine[sIdx] = li;
                final float shift = calculateGapShift(sIdx, romanStartShift);
                final float sx = mainLayout.getPrimaryHorizontal(Math.max(0, Math.min(syl.startOffset(), formattedMainText.length()))) + shift;
                final float ex = mainLayout.getPrimaryHorizontal(Math.max(0, Math.min(syl.endOffset(), formattedMainText.length()))) + shift;
                sylShiftedLeadX[sIdx] = Math.min(sx, ex);
                sylShiftedTrailX[sIdx] = Math.max(sx + 1f, ex);
            }
        }

        String transText = null;
        StaticLayout transLayout = null;
        final int mainSectionH = mainLayout.getHeight() + extraRomanHeightPx + extraRubyHeightPx;
        int transTopY = 0;
        int mainTopY = extraRubyHeightPx;
        int totalH = mainSectionH;

        if (transStart >= 0 && transEnd > transStart && transStart < fullStr.length()) {
            transText = fullStr.substring(transStart, Math.min(transEnd, fullStr.length())).trim();
            if (!transText.isEmpty()) {
                TextPaint transPaint = new TextPaint(basePaint);
                transPaint.setTextSize(fontSizePx * LyricsSpanBuilder.TRANSLATION_RELATIVE_SIZE);
                LyricsViewSupport.applyLyricsBoldPaint(transPaint);
                transPaint.setLetterSpacing(0f);
                final boolean isTransRtl = LyricsSpanBuilder.isRtlText(transText);
                Layout.Alignment transAlign =
                        LyricsSpanBuilder.resolveAlignment(isTransRtl, isRightAligned, isCentered);
                transLayout = StaticLayout.Builder.obtain(
                                transText, 0, transText.length(), transPaint, widthPx)
                        .setAlignment(transAlign)
                        .setIncludePad(false)
                        .setLineSpacing(0f, 1.18f)
                        .build();
                final int transH = transLayout.getHeight();
                if (transStart < origStart) {
                    mainTopY = transH + Dim.dp4 + extraRubyHeightPx;
                } else {
                    transTopY = mainSectionH + Dim.dp4;
                }
                totalH = mainSectionH + Dim.dp4 + transH;
            }
        }

        return new SyllableRomanCache(widthPx, fontSizePx, true, rawMainText, lineCount,
                alignedSyllables, extraRomanHeightPx, clusters, statics,
                romanStatics, rubyStatics, sylLine,
                sylShiftedLeadX, sylShiftedTrailX, transText, transLayout, transTopY, mainTopY, totalH,
                mainLayout.getHeight());
    }

    /** Finds each word timing in the laid out text, which may carry bidi marks and added breaks. */
    private static List<AlignedSyllable> alignSyllablesForFormatting(
            List<WordTiming> words,
            String rawMainText,
            String targetMainText) {
        if (words.isEmpty() || targetMainText.isEmpty()) {
            return Collections.emptyList();
        }
        List<Integer> strippedToOriginal = new ArrayList<>(targetMainText.length() + 1);
        StringBuilder strippedBuilder = new StringBuilder(targetMainText.length());
        for (int i = 0; i < targetMainText.length(); i++) {
            final char c = targetMainText.charAt(i);
            if (!LyricsTextUtils.isBidiOrInvisible(c)) {
                strippedBuilder.append(c);
                strippedToOriginal.add(i);
            }
        }
        strippedToOriginal.add(targetMainText.length());
        String strippedMain = strippedBuilder.toString();
        String strippedLower = strippedMain.toLowerCase(Locale.ROOT);

        int totalSylLen = 0;
        String[] sylTexts = new String[words.size()];
        for (int i = 0; i < words.size(); i++) {
            WordTiming wt = words.get(i);
            final int s = Math.max(0, Math.min(wt.start(), rawMainText.length()));
            final int e = Math.max(s, Math.min(wt.end(), rawMainText.length()));
            String st = LyricsTextUtils.stripBidiControls(rawMainText.substring(s, e)).trim();
            sylTexts[i] = st;
            totalSylLen += st.length();
        }
        totalSylLen = Math.max(1, totalSylLen);

        int searchIdx = 0;
        List<AlignedSyllable> out = new ArrayList<>(words.size());
        for (int i = 0; i < words.size(); i++) {
            WordTiming syl = words.get(i);
            String sylText = sylTexts[i];
            String sylLower = sylText.toLowerCase(Locale.ROOT);
            while (searchIdx < strippedMain.length()
                    && Character.isWhitespace(strippedMain.charAt(searchIdx))) {
                searchIdx++;
            }
            final int found = sylLower.isEmpty() ? -1 : strippedLower.indexOf(sylLower, searchIdx);
            final int si;
            final int ei;
            if (found != -1) {
                si = strippedToOriginal.get(found);
                final int endIdx = Math.min(found + sylText.length(), strippedToOriginal.size() - 1);
                ei = strippedToOriginal.get(endIdx);
                searchIdx = found + sylText.length();
            } else {
                final int fallbackStripped = Math.min(searchIdx, strippedMain.length());
                final int propLen = Math.max(1,
                        (int) (((double) sylText.length() / totalSylLen) * strippedMain.length()));
                final int endStripped = Math.min(fallbackStripped + propLen, strippedMain.length());
                si = strippedToOriginal.get(Math.min(fallbackStripped, strippedToOriginal.size() - 1));
                ei = strippedToOriginal.get(Math.min(endStripped, strippedToOriginal.size() - 1));
                searchIdx = endStripped;
            }
            out.add(new AlignedSyllable(syl, si, ei));
        }
        return out;
    }

    /**
     * Breaks the lyric where its syllables, each as wide as the wider of itself and its reading,
     * run out of room, so that no syllable is split from its reading by a wrap.
     */
    private static String buildFormattedMainText(
            String mainText,
            List<WordTiming> words,
            TextPaint mainPaint,
            TextPaint romPaint,
            @Nullable TextPaint rubyPaint,
            int widthPx,
            float fontSizePx,
            boolean rubyActive) {
        if (mainText.isEmpty() || widthPx <= 0 || words.isEmpty()) {
            return mainText;
        }
        List<AlignedSyllable> alignedSyllables =
                alignSyllablesForFormatting(words, mainText, mainText);
        if (alignedSyllables.isEmpty()) {
            return mainText;
        }

        final int unconstrainedWidth = Math.max(widthPx * 16, 16384);
        StaticLayout unconstrained = StaticLayout.Builder.obtain(
                        mainText, 0, mainText.length(), mainPaint, unconstrainedWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build();

        List<RomanizedWordCluster> romanClusters =
                buildRomanizedWordClusters(alignedSyllables, mainText, unconstrained, romPaint, widthPx);
        List<RomanizedWordCluster> rubyClusters = (rubyActive && rubyPaint != null)
                ? buildRubyClusters(alignedSyllables, mainText, unconstrained, rubyPaint, widthPx)
                : Collections.emptyList();
        if (romanClusters.isEmpty() && rubyClusters.isEmpty()) {
            return mainText;
        }

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2);
        for (RomanizedWordCluster rc : romanClusters) {
            clusterMap.put(rc.wordIndex(), rc);
        }
        Map<Integer, RomanizedWordCluster> rubyMap = new HashMap<>(rubyClusters.size() * 2);
        for (RomanizedWordCluster rc : rubyClusters) {
            rubyMap.put(rc.wordIndex(), rc);
        }

        float[] sylSlotWidths = new float[alignedSyllables.size()];
        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            RomanizedWordCluster rubyCluster = rubyMap.get(sylIdx);
            final int startOff = Math.max(0, Math.min(syl.startOffset(), mainText.length()));
            final int nextStartOff = (sylIdx + 1 < alignedSyllables.size())
                    ? Math.max(syl.endOffset(), Math.min(alignedSyllables.get(sylIdx + 1).startOffset(), mainText.length()))
                    : Math.max(startOff, Math.min(syl.endOffset(), mainText.length()));
            final boolean hasNewlineInGap = mainText.substring(
                    Math.max(0, Math.min(syl.endOffset(), mainText.length())), nextStartOff).indexOf('\n') >= 0;
            final int slotEndOff = hasNewlineInGap
                    ? Math.max(startOff, Math.min(syl.endOffset(), mainText.length()))
                    : nextStartOff;
            final float startX = unconstrained.getPrimaryHorizontal(startOff);
            final float endX = unconstrained.getPrimaryHorizontal(slotEndOff);
            final float slotMainWidth = Math.max(1f, endX - startX);
            final float romWidth = cluster != null ? cluster.romanWidth() : 0f;
            final float rubyWidth = rubyCluster != null ? rubyCluster.romanWidth() : 0f;
            sylSlotWidths[sylIdx] = Math.max(Math.max(slotMainWidth, romWidth), rubyWidth);
        }

        List<Integer> breakIndices = new ArrayList<>();
        Set<Integer> spaceReplaced = new HashSet<>();

        float currentLineSlotSum = 0f;
        int syllablesOnCurrentLine = 0;
        int currentLineStartOffset = 0;

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            final float slotW = sylSlotWidths[sylIdx];
            AlignedSyllable prevSyl = sylIdx > 0 ? alignedSyllables.get(sylIdx - 1) : null;

            if (prevSyl != null) {
                final int gs = Math.max(0, Math.min(prevSyl.endOffset(), mainText.length()));
                final int ge = Math.max(gs, Math.min(syl.startOffset(), mainText.length()));
                if (mainText.substring(gs, ge).indexOf('\n') >= 0) {
                    currentLineSlotSum = 0f;
                    syllablesOnCurrentLine = 0;
                    currentLineStartOffset = Math.max(0, Math.min(syl.startOffset(), mainText.length()));
                }
            }

            final float breakThreshold = widthPx - fontSizePx * 0.1f;
            if (syllablesOnCurrentLine > 0 && currentLineSlotSum + slotW > breakThreshold) {
                final int rawBreakAt = Math.max(0, Math.min(syl.startOffset(), mainText.length()));
                Integer breakAt = findSafeBreakOffset(mainText, rawBreakAt, currentLineStartOffset);
                if (breakAt != null && breakAt > 0) {
                    breakIndices.add(breakAt);
                    if (breakAt - 1 < mainText.length() && mainText.charAt(breakAt - 1) == ' ') {
                        spaceReplaced.add(breakAt - 1);
                    }
                    currentLineStartOffset = breakAt;
                    currentLineSlotSum = slotW;
                    syllablesOnCurrentLine = 1;
                } else {
                    currentLineSlotSum += slotW;
                    syllablesOnCurrentLine++;
                }
            } else {
                currentLineSlotSum += slotW;
                syllablesOnCurrentLine++;
            }
        }

        if (breakIndices.isEmpty()) {
            return mainText;
        }

        StringBuilder sb = new StringBuilder(mainText.length() + breakIndices.size());
        int prev = 0;
        for (int bIdx : breakIndices) {
            final int safeBIdx = Math.max(0, Math.min(bIdx, mainText.length()));
            final int safePrev = Math.max(0, Math.min(prev, mainText.length()));
            if (safeBIdx > safePrev) {
                final int spaceIdx = safeBIdx - 1;
                if (spaceReplaced.contains(spaceIdx)
                        && spaceIdx >= 0 && spaceIdx < mainText.length()
                        && mainText.charAt(spaceIdx) == ' ') {
                    sb.append(mainText, safePrev, spaceIdx);
                    sb.append('\n');
                    prev = safeBIdx;
                } else {
                    sb.append(mainText, safePrev, safeBIdx);
                    sb.append('\n');
                    prev = safeBIdx;
                }
            }
        }
        final int finalPrev = Math.max(0, Math.min(prev, mainText.length()));
        if (finalPrev < mainText.length()) {
            sb.append(mainText.substring(finalPrev));
        }
        return sb.toString();
    }

    /** Punctuation a line must not start with. */
    private static boolean isProhibitedLineStart(char c) {
        return switch (c) {
            case '、', '。', '！', '？', ')', ']', '}', '”', '’', '…', '—',
                 '）', '】', '』', '」', '≫', '》', '〉', '＞', '›', '〕', '〛',
                 ',', '.', '!', '?', ':', ';', ' ' -> true;
            default -> false;
        };
    }

    @Nullable
    private static Integer findSafeBreakOffset(String mainText, int rawBreakAt, int lineStartOffset) {
        if (rawBreakAt <= lineStartOffset || rawBreakAt > mainText.length()) {
            return null;
        }
        Character before = rawBreakAt - 1 >= 0 && rawBreakAt - 1 < mainText.length()
                ? mainText.charAt(rawBreakAt - 1) : null;
        Character at = rawBreakAt >= 0 && rawBreakAt < mainText.length()
                ? mainText.charAt(rawBreakAt) : null;

        // Korean words are spaced, so a break inside one moves back to the last space.
        final int lastSpaceOnLine = mainText.substring(lineStartOffset, rawBreakAt).lastIndexOf(' ');
        if (lastSpaceOnLine >= 0 && (before == null || before != ' ')) {
            final boolean isInsideHangulWord = before != null && at != null
                    && before >= 0xAC00 && before <= 0xD7AF
                    && at >= 0xAC00 && at <= 0xD7AF;
            if (isInsideHangulWord) {
                final int spaceBreak = lineStartOffset + lastSpaceOnLine + 1;
                if (spaceBreak > lineStartOffset && spaceBreak < mainText.length()
                        && !isProhibitedLineStart(mainText.charAt(spaceBreak))) {
                    return spaceBreak;
                }
            }
        }

        final boolean atUnspacedBoundary =
                (before == null || before == ' ' || LyricsWordBoundaries.isUnspacedCodePoint(before))
                        && (at == null || at == ' ' || LyricsWordBoundaries.isUnspacedCodePoint(at) || LyricsWordBoundaries.isUnspacedCodePoint(before));

        int candidate = rawBreakAt;
        if (!atUnspacedBoundary) {
            int i = rawBreakAt;
            while (i > lineStartOffset) {
                final char c = mainText.charAt(i - 1);
                Character next = i < mainText.length() ? mainText.charAt(i) : null;
                if (c == ' ' || LyricsWordBoundaries.isUnspacedCodePoint(c) || (next != null && LyricsWordBoundaries.isUnspacedCodePoint(next))) {
                    candidate = i;
                    break;
                }
                i--;
            }
            if (i <= lineStartOffset) {
                return null;
            }
        }

        while (candidate > lineStartOffset
                && candidate < mainText.length()
                && isProhibitedLineStart(mainText.charAt(candidate))) {
            candidate--;
            while (candidate > lineStartOffset
                    && !(mainText.charAt(candidate - 1) == ' ' || LyricsWordBoundaries.isUnspacedCodePoint(mainText.charAt(candidate - 1)))) {
                candidate--;
            }
        }

        return candidate > lineStartOffset ? candidate : null;
    }

    /** Lays out each syllable's reading on its own, empty when the line has no reading of its own. */
    private static List<RomanizedWordCluster> buildRomanizedWordClusters(
            List<AlignedSyllable> alignedSyllables,
            String mainText,
            Layout textLayoutResult,
            TextPaint romPaint,
            float maxWidthPx) {
        if (alignedSyllables.isEmpty() || mainText.isEmpty()) {
            return Collections.emptyList();
        }
        String cleanMainText = LyricsTextUtils.stripBidiControls(mainText).trim();
        StringBuilder assembled = new StringBuilder();
        for (AlignedSyllable syl : alignedSyllables) {
            String r = syl.syllable().romaji();
            if (r != null) {
                assembled.append(r);
            }
        }
        String assembledRoman = LyricsTextUtils.stripBidiControls(assembled.toString()).trim();
        if (assembledRoman.isEmpty() || assembledRoman.equalsIgnoreCase(cleanMainText)) {
            return Collections.emptyList();
        }

        List<RomanizedWordCluster> result = new ArrayList<>(alignedSyllables.size());
        final int maxCharIdx = Math.max(0, mainText.length() - 1);
        for (int idx = 0; idx < alignedSyllables.size(); idx++) {
            AlignedSyllable syl = alignedSyllables.get(idx);
            String text = syl.syllable().romaji();
            if (text != null && !text.trim().isEmpty()) {
                final int clampedStart = Math.max(0, Math.min(syl.startOffset(), maxCharIdx));
                final int li = textLayoutResult.getLineForOffset(clampedStart);
                TextPaint subPaint = new TextPaint(romPaint);
                final float pad = (idx + 1 < alignedSyllables.size()) ? Dim.dp(2) : 0f;
                StaticLayout subLayout = buildFittedLayout(text, subPaint,
                        maxWidthPx > 0f ? Math.max(1f, maxWidthPx - pad) : 0f);
                final float romW = singleLineWidth(text, subPaint, subLayout) + pad;
                result.add(new RomanizedWordCluster(idx, syl.startOffset(), li, subLayout, romW));
            }
        }
        return result;
    }

    /** How far along the lyric each character starts, counting its lines as one long line. */
    private static float[] buildCharXMap(
            String mainText,
            Layout textLayoutResult,
            int lineCount,
            boolean[] lineIsRtl,
            float[] lineStarts) {
        final int len = mainText.length();
        float[] map = new float[len + 1];
        final int maxIdx = Math.max(0, len - 1);
        for (int i = 0; i <= len; i++) {
            final int idx = Math.min(i, maxIdx);
            final int li = Math.max(0, Math.min(textLayoutResult.getLineForOffset(idx), lineCount - 1));
            final float h = textLayoutResult.getPrimaryHorizontal(Math.min(i, len));
            final float local = lineIsRtl[li]
                    ? textLayoutResult.getLineRight(li) - h
                    : h - textLayoutResult.getLineLeft(li);
            map[i] = lineStarts[li] + local;
        }
        return map;
    }

    /**
     * How far each syllable moves right to make room for the readings before it that are wider
     * than their syllables, centred or right aligned again with the line.
     */
    private static float[] buildRomanStartShift(
            String mainText,
            float[] charXMap,
            Layout textLayoutResult,
            int lineCount,
            List<AlignedSyllable> alignedSyllables,
            List<RomanizedWordCluster> romanClusters,
            List<RomanizedWordCluster> rubyClusters,
            Layout.Alignment alignment) {
        final int totalClusters = romanClusters.size() + rubyClusters.size();
        float[] shiftByWordIndex = new float[totalClusters + alignedSyllables.size() + 1];
        float[] lineTotalExtra = new float[lineCount];
        if (romanClusters.isEmpty() && rubyClusters.isEmpty()) {
            return shiftByWordIndex;
        }

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2 + 1);
        for (RomanizedWordCluster rc : romanClusters) {
            clusterMap.put(rc.wordIndex(), rc);
        }
        Map<Integer, RomanizedWordCluster> rubyMap = new HashMap<>(rubyClusters.size() * 2 + 1);
        for (RomanizedWordCluster rc : rubyClusters) {
            rubyMap.put(rc.wordIndex(), rc);
        }

        float runningShift = 0f;
        int currentLine = -1;
        final int maxCharIdx = Math.max(0, mainText.length() - 1);
        final int maxMapIdx = Math.max(0, charXMap.length - 1);

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            final int li = textLayoutResult.getLineForOffset(
                    Math.max(0, Math.min(syl.startOffset(), maxCharIdx)));
            if (li != currentLine) {
                currentLine = li;
                runningShift = 0f;
            }
            if (sylIdx < shiftByWordIndex.length) {
                shiftByWordIndex[sylIdx] = runningShift;
            }

            RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            RomanizedWordCluster rubyCluster = rubyMap.get(sylIdx);
            if (cluster == null && rubyCluster == null) {
                continue;
            }
            final int sylEndLine = textLayoutResult.getLineForOffset(
                    Math.max(0, Math.min(syl.endOffset(), maxCharIdx)));
            final int lineEndOff = textLayoutResult.getLineVisibleEnd(li);

            // Include any trailing space before the next syllable on the same line so we compare
            // (word + space) in mainLayout against max(romajiWidth, rubyWidth).
            int effectiveEndOff = syl.endOffset();
            if (li == sylEndLine && sylIdx + 1 < alignedSyllables.size()) {
                AlignedSyllable nextSyl = alignedSyllables.get(sylIdx + 1);
                final int nextLi = textLayoutResult.getLineForOffset(
                        Math.max(0, Math.min(nextSyl.startOffset(), maxCharIdx)));
                if (nextLi == li && nextSyl.startOffset() > syl.endOffset()) {
                    effectiveEndOff = nextSyl.startOffset();
                }
            }

            final float startX = charXMap[Math.max(0, Math.min(syl.startOffset(), maxMapIdx))];
            final float endX = (li == sylEndLine)
                    ? charXMap[Math.max(0, Math.min(effectiveEndOff, maxMapIdx))]
                    : charXMap[Math.max(0, Math.min(lineEndOff, maxMapIdx))];
            final float mainWidth = Math.max(1f, endX - startX);
            final float romWidth = cluster != null ? cluster.romanWidth() : 0f;
            final float rubyWidth = rubyCluster != null ? rubyCluster.romanWidth() : 0f;
            final float slotSubWidth = Math.max(romWidth, rubyWidth);

            if (slotSubWidth > mainWidth) {
                final float extra = slotSubWidth - mainWidth;
                runningShift += extra;
                if (li >= 0 && li < lineTotalExtra.length) {
                    lineTotalExtra[li] += extra;
                }
            }
        }

        if (alignment == Layout.Alignment.ALIGN_CENTER || alignment == Layout.Alignment.ALIGN_OPPOSITE) {
            for (int idx = 0; idx < alignedSyllables.size() && idx < shiftByWordIndex.length; idx++) {
                AlignedSyllable syl = alignedSyllables.get(idx);
                final int li = textLayoutResult.getLineForOffset(
                        Math.max(0, Math.min(syl.startOffset(), maxCharIdx)));
                final float totalExtra = (li >= 0 && li < lineTotalExtra.length) ? lineTotalExtra[li] : 0f;
                final float correction = (alignment == Layout.Alignment.ALIGN_CENTER)
                        ? totalExtra / 2f : totalExtra;
                shiftByWordIndex[idx] -= correction;
            }
        }

        return shiftByWordIndex;
    }

    private static float calculateGapShift(int syllableIndex, float[] romanStartShift) {
        if (syllableIndex < 0 || syllableIndex >= romanStartShift.length) {
            return 0f;
        }
        return romanStartShift[syllableIndex];
    }

    private static List<VisualCluster> buildVisualClusters(
            String formattedMainText,
            Layout textLayoutResult,
            List<AlignedSyllable> alignedSyllables,
            float[] lineBaselines,
            TextPaint mainPaint) {
        List<ClusterMeta> metaList = new ArrayList<>(formattedMainText.length());
        final int len = formattedMainText.length();
        final int maxIdx = Math.max(0, len - 1);
        int i = 0;
        while (i < len) {
            final int start = i;
            final int end = (i + 1 < len
                    && Character.isHighSurrogate(formattedMainText.charAt(i))
                    && Character.isLowSurrogate(formattedMainText.charAt(i + 1)))
                    ? i + 2 : i + 1;
            i = end;

            if (formattedMainText.charAt(start) == '\n') {
                continue;
            }
            final int safeStart = Math.max(0, Math.min(start, maxIdx));
            final int safeEnd = Math.min(end, maxIdx);
            final int li = textLayoutResult.getLineForOffset(safeStart);
            final int eli = textLayoutResult.getLineForOffset(safeEnd);
            final float hStart = textLayoutResult.getPrimaryHorizontal(safeStart);
            final float hEnd = (li == eli)
                    ? textLayoutResult.getPrimaryHorizontal(Math.min(end, len))
                    : textLayoutResult.getLineRight(li);
            final float x = Math.min(hStart, hEnd);
            final float w = Math.abs(hEnd - hStart);
            metaList.add(new ClusterMeta(start, end, li, x, w));
        }

        int[] sylIndices = new int[metaList.size()];
        int[] shiftIndices = new int[metaList.size()];
        int sylPointer = 0;
        int lastSylOnLine = -1;
        int curLine = -1;
        for (int mIdx = 0; mIdx < metaList.size(); mIdx++) {
            ClusterMeta m = metaList.get(mIdx);
            if (m.lineIdx() != curLine) {
                curLine = m.lineIdx();
                lastSylOnLine = -1;
            }
            while (sylPointer < alignedSyllables.size()
                    && alignedSyllables.get(sylPointer).endOffset() <= m.start()) {
                sylPointer++;
            }
            if (sylPointer < alignedSyllables.size()) {
                AlignedSyllable s = alignedSyllables.get(sylPointer);
                if (m.start() >= s.startOffset() && m.end() <= s.endOffset()) {
                    sylIndices[mIdx] = sylPointer;
                    shiftIndices[mIdx] = sylPointer;
                    lastSylOnLine = sylPointer;
                } else {
                    sylIndices[mIdx] = -1;
                    shiftIndices[mIdx] = lastSylOnLine >= 0 ? lastSylOnLine : sylPointer;
                }
            } else {
                sylIndices[mIdx] = -1;
                shiftIndices[mIdx] = lastSylOnLine;
            }
        }

        List<VisualCluster> list = new ArrayList<>();
        int idx = 0;
        while (idx < metaList.size()) {
            ClusterMeta m = metaList.get(idx);
            final int runSylIdx = sylIndices[idx];
            final int runShiftIdx = shiftIndices[idx];
            int j = idx;
            if (runSylIdx >= 0) {
                while (j + 1 < metaList.size()
                        && metaList.get(j + 1).lineIdx() == m.lineIdx()
                        && sylIndices[j + 1] == runSylIdx) {
                    j++;
                }
            }
            float runLeft = Float.MAX_VALUE;
            float runRight = -Float.MAX_VALUE;
            for (int k = idx; k <= j; k++) {
                ClusterMeta cm = metaList.get(k);
                if (cm.x() < runLeft) runLeft = cm.x();
                if (cm.x() + cm.width() > runRight) runRight = cm.x() + cm.width();
            }
            ClusterMeta last = metaList.get(j);
            String runText = formattedMainText.substring(
                    Math.max(0, Math.min(m.start(), len)),
                    Math.max(0, Math.min(last.end(), len)));
            TextPaint subPaint = new TextPaint(mainPaint);
            StaticLayout subLayout = buildSingleLineLayout(runText, subPaint);
            final float subW = Math.max(runRight - runLeft, singleLineWidth(runText, subPaint, subLayout));
            final float clusterY = lineBaselines[m.lineIdx()] - subLayout.getLineBaseline(0);
            list.add(new VisualCluster(m.lineIdx(), runLeft, clusterY, Math.max(1f, subW),
                    subLayout, runSylIdx, runShiftIdx));
            idx = j + 1;
        }
        return list;
    }

    private static ClusterStatics buildClusterStatics(
            List<VisualCluster> clusters,
            float[] romanStartShift,
            float lineExtraHeight) {
        final int n = clusters.size();
        float[] baseX = new float[n];
        float[] baseY = new float[n];
        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            final int shiftIdx = vc.syllableIndex() >= 0 ? vc.syllableIndex() : vc.shiftIndex();
            baseX[i] = vc.x() + calculateGapShift(shiftIdx, romanStartShift);
            baseY[i] = vc.y() + vc.lineIdx() * lineExtraHeight;
        }
        return new ClusterStatics(baseX, baseY);
    }

    @SuppressWarnings("unchecked")
    private static List<RomanizedWordCluster>[] groupRomanClustersByLine(
            List<RomanizedWordCluster> romanClusters, int lineCount) {
        if (romanClusters.isEmpty()) {
            return new List[0];
        }
        List<RomanizedWordCluster>[] lines = new List[lineCount];
        for (int li = 0; li < lineCount; li++) {
            lines[li] = new ArrayList<>();
        }
        for (RomanizedWordCluster rc : romanClusters) {
            if (rc.lineIdx() >= 0 && rc.lineIdx() < lineCount) {
                lines[rc.lineIdx()].add(rc);
            }
        }
        return lines;
    }

    private static RomanLineStatics[] buildRomanStatics(
            List<RomanizedWordCluster>[] romanLines,
            float[] charXMap,
            float[] romanStartShift,
            float[] lineBaselines,
            float[] lineBottoms,
            float[] lineLefts,
            float[] lineStarts,
            int lineCount,
            float fontSizePx,
            float lineExtraHeight,
            float totalWidth) {
        final int count = Math.min(lineCount, romanLines.length);
        RomanLineStatics[] lines = new RomanLineStatics[count];

        for (int li = 0; li < count; li++) {
            List<RomanizedWordCluster> cs = romanLines[li];
            if (cs == null || cs.isEmpty()) {
                continue;
            }
            final float lineLeft = li < lineLefts.length ? lineLefts[li] : 0f;
            final float lineStart = li < lineStarts.length ? lineStarts[li] : 0f;
            final int n = cs.size();
            float[] drawX = new float[n];
            for (int i = 0; i < n; i++) {
                RomanizedWordCluster c = cs.get(i);
                final float gx = c.startIndex() < 0 ? 0f
                        : (c.startIndex() >= charXMap.length ? totalWidth : charXMap[c.startIndex()]);
                final float shift = (c.wordIndex() >= 0 && c.wordIndex() < romanStartShift.length)
                        ? romanStartShift[c.wordIndex()] : 0f;
                drawX[i] = gx - lineStart + lineLeft + shift;
            }
            final float baseline = li < lineBaselines.length ? lineBaselines[li] : 0f;
            final float y = baseline + fontSizePx * 0.12f + li * lineExtraHeight;
            lines[li] = new RomanLineStatics(cs, drawX, y);
        }
        return lines;
    }

    private static List<RomanizedWordCluster> buildRubyClusters(
            List<AlignedSyllable> alignedSyllables,
            String mainText,
            Layout textLayoutResult,
            TextPaint rubyPaint,
            float maxWidthPx) {
        if (alignedSyllables.isEmpty() || mainText.isEmpty()) {
            return Collections.emptyList();
        }
        boolean anyRuby = false;
        for (AlignedSyllable syl : alignedSyllables) {
            String r = syl.syllable().ruby();
            if (r != null && !r.trim().isEmpty()) {
                anyRuby = true;
                break;
            }
        }
        if (!anyRuby) {
            return Collections.emptyList();
        }

        List<RomanizedWordCluster> result = new ArrayList<>(alignedSyllables.size());
        final int maxCharIdx = Math.max(0, mainText.length() - 1);
        for (int idx = 0; idx < alignedSyllables.size(); idx++) {
            AlignedSyllable syl = alignedSyllables.get(idx);
            String text = syl.syllable().ruby();
            if (text != null && !text.trim().isEmpty()) {
                final int clampedStart = Math.max(0, Math.min(syl.startOffset(), maxCharIdx));
                final int li = textLayoutResult.getLineForOffset(clampedStart);
                TextPaint subPaint = new TextPaint(rubyPaint);
                StaticLayout subLayout = buildFittedLayout(text, subPaint, maxWidthPx);
                final float romW = singleLineWidth(text, subPaint, subLayout);
                result.add(new RomanizedWordCluster(idx, syl.startOffset(), li, subLayout, romW));
            }
        }
        return result;
    }

    /** Where the furigana of each line is drawn, above the words it reads. */
    private static RomanLineStatics[] buildRubyStatics(
            List<RomanizedWordCluster>[] rubyLines,
            float[] charXMap,
            float[] romanStartShift,
            float[] lineBaselines,
            float[] lineLefts,
            float[] lineStarts,
            int lineCount,
            float fontSizePx,
            float lineExtraHeight,
            float totalWidth) {
        final int count = Math.min(lineCount, rubyLines.length);
        RomanLineStatics[] lines = new RomanLineStatics[count];

        for (int li = 0; li < count; li++) {
            List<RomanizedWordCluster> cs = rubyLines[li];
            if (cs == null || cs.isEmpty()) {
                continue;
            }
            final float lineLeft = li < lineLefts.length ? lineLefts[li] : 0f;
            final float lineStart = li < lineStarts.length ? lineStarts[li] : 0f;
            final int n = cs.size();
            float[] drawX = new float[n];
            for (int i = 0; i < n; i++) {
                RomanizedWordCluster c = cs.get(i);
                final float gx = c.startIndex() < 0 ? 0f
                        : (c.startIndex() >= charXMap.length ? totalWidth : charXMap[c.startIndex()]);
                final float shift = (c.wordIndex() >= 0 && c.wordIndex() < romanStartShift.length)
                        ? romanStartShift[c.wordIndex()] : 0f;
                drawX[i] = gx - lineStart + lineLeft + shift;
            }
            float maxClusterHeight = 0;
            for (RomanizedWordCluster c : cs) {
                maxClusterHeight = Math.max(maxClusterHeight, c.subLayout().getHeight());
            }
            final float baseBaseline = li < lineBaselines.length
                    ? lineBaselines[li] : (fontSizePx * 0.8f);
            final float rubyGap = Dim.dp(4);
            // Furigana rides along with the line it reads
            final float y = baseBaseline - fontSizePx * 0.85f - rubyGap - maxClusterHeight
                    + li * lineExtraHeight;
            lines[li] = new RomanLineStatics(cs, drawX, y);
        }
        return lines;
    }
}
