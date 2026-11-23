/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4866
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Shader;
import android.os.Build;
import android.text.BoringLayout;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextDirectionHeuristic;
import android.text.TextDirectionHeuristics;
import android.text.TextPaint;

import androidx.annotation.Nullable;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Utils;

public final class WordAnimationUtils {

    private WordAnimationUtils() {
    }

    public static final int WIPE_PAST = 0;
    public static final int WIPE_UNLIT = 1;
    public static final int WIPE_BAND = 2;

    public static final float[] GRADIENT_STOPS = new float[]{0.0f, 0.1f, 0.9f, 1.0f};

    public record WordTimestamp(String text, double startTime, double endTime,
                                boolean hasTrailingSpace, boolean isBackground,
                                @Nullable String romanized, @Nullable String ruby) {
            public WordTimestamp(
                    String text,
                    double startTime,
                    double endTime,
                    boolean hasTrailingSpace,
                    boolean isBackground,
                    @Nullable String romanized) {
                this(text, startTime, endTime, hasTrailingSpace, isBackground, romanized, null);
            }

        public long startTimeMs() {
                return Math.round(startTime * 1000.0);
            }

            public long endTimeMs() {
                return Math.round(endTime * 1000.0);
            }

            public WordTimestamp copy(
                    String newText,
                    double newStartTime,
                    double newEndTime,
                    boolean newHasTrailingSpace) {
                return new WordTimestamp(newText, newStartTime, newEndTime, newHasTrailingSpace, isBackground, romanized, ruby);
            }

            public static List<WordTimestamp> fromSpanTimings(
                    String mainText,
                    @Nullable List<WordTiming> timings,
                    boolean isBg) {
                if (timings == null || timings.isEmpty() || mainText.isEmpty()) {
                    return Collections.emptyList();
                }
                int len = mainText.length();
                List<WordTimestamp> list = new ArrayList<>(timings.size());
                double fallbackStart = 0.0;
                for (int i = 0; i < timings.size(); i++) {
                    WordTiming wt = timings.get(i);
                    int s = Math.max(0, Math.min(len, wt.start()));
                    int e = Math.max(s, Math.min(len, wt.end()));
                    String rawText = mainText.substring(s, e);
                    boolean hasSpaceAfter = wt.hasTrailingSpace()
                            || (e < len && Character.isWhitespace(mainText.charAt(e)))
                            || (i + 1 < timings.size() && timings.get(i + 1).start() > e
                            && mainText.substring(e, Math.min(len, timings.get(i + 1).start())).indexOf(' ') >= 0);
                    double sSec = (wt.startMs() != LyricsLine.NO_TIME) ? (wt.startMs() / 1000.0) : fallbackStart;
                    double eSec = (wt.endMs() != LyricsLine.NO_TIME) ? (wt.endMs() / 1000.0) : sSec;
                    String rom = (wt.romaji() != null && !wt.romaji().trim().isEmpty()) ? wt.romaji().trim() : null;
                    String ruby = (wt.ruby() != null && !wt.ruby().trim().isEmpty()) ? wt.ruby().trim() : null;
                    list.add(new WordTimestamp(rawText, sSec, eSec, hasSpaceAfter, isBg, rom, ruby));
                    fallbackStart = eSec;
                }
                return list;
            }

            @Nullable
            public static List<WordTimestamp> buildRomanizedWordsFromSpanTimings(
                    String mainText,
                    @Nullable List<WordTiming> timings,
                    boolean isBg) {
                if (timings == null || timings.isEmpty() || mainText.isEmpty()) {
                    return null;
                }
                boolean anyRom = false;
                for (int i = 0; i < timings.size(); i++) {
                    String r = timings.get(i).romaji();
                    if (r != null && !r.trim().isEmpty()) {
                        anyRom = true;
                        break;
                    }
                }
                if (!anyRom) {
                    return null;
                }
                int len = mainText.length();
                List<WordTimestamp> list = new ArrayList<>(timings.size());
                double fallbackStart = 0.0;
                for (int i = 0; i < timings.size(); i++) {
                    WordTiming wt = timings.get(i);
                    int s = Math.max(0, Math.min(len, wt.start()));
                    int e = Math.max(s, Math.min(len, wt.end()));
                    boolean hasSpaceAfter = wt.hasTrailingSpace()
                            || (e < len && Character.isWhitespace(mainText.charAt(e)))
                            || (i + 1 < timings.size() && timings.get(i + 1).start() > e
                            && mainText.substring(e, Math.min(len, timings.get(i + 1).start())).indexOf(' ') >= 0);
                    double sSec = (wt.startMs() != LyricsLine.NO_TIME) ? (wt.startMs() / 1000.0) : fallbackStart;
                    double eSec = (wt.endMs() != LyricsLine.NO_TIME) ? (wt.endMs() / 1000.0) : sSec;
                    String romaji = wt.romaji();
                    String r = romaji != null ? romaji.trim() : "";
                    boolean trailingSpace = !r.isEmpty() && (hasSpaceAfter || romaji.endsWith(" "));
                    list.add(new WordTimestamp(r, sSec, eSec, trailingSpace, isBg, r, wt.ruby()));
                    fallbackStart = eSec;
                }
                return list;
            }

        }

    public record AlignedSyllable(WordTimestamp syllable, int startOffset, int endOffset) {
    }

    public record RomanTimingSegment(int charStart, int charEnd, long startMs, long endMs) {
    }

    public record RomanizedWordCluster(int wordIndex, int startIndex, int endIndex,
                                       String romanText, int lineIdx, long wordStartMs,
                                       long wordEndMs, float originalCanvasX, Layout subLayout,
                                       float romanWidth,
                                       List<RomanTimingSegment> segments) {

        public RomanizedWordCluster(
                    int wordIndex,
                    int startIndex,
                    int endIndex,
                    String romanText,
                    int lineIdx,
                    long wordStartMs,
                    long wordEndMs,
                    float originalCanvasX,
                    Layout subLayout) {
                this(
                        wordIndex,
                        startIndex,
                        endIndex,
                        romanText,
                        lineIdx,
                        wordStartMs,
                        wordEndMs,
                        originalCanvasX,
                        subLayout,
                        Math.max(
                                subLayout.getWidth(),
                                Utils.isNotEmpty(subLayout.getText())
                                        ? subLayout.getPrimaryHorizontal(subLayout.getText().length())
                                        : 0f
                        ),
                        List.of(new RomanTimingSegment(0, romanText.length(), wordStartMs, wordEndMs))
                );
            }

        public RomanizedWordCluster(
                    int wordIndex,
                    int startIndex,
                    int endIndex,
                    String romanText,
                    int lineIdx,
                    long wordStartMs,
                    long wordEndMs,
                    float originalCanvasX,
                    Layout subLayout,
                    float romanWidth) {
                this(
                        wordIndex,
                        startIndex,
                        endIndex,
                        romanText,
                        lineIdx,
                        wordStartMs,
                        wordEndMs,
                        originalCanvasX,
                        subLayout,
                        romanWidth,
                        List.of(new RomanTimingSegment(0, romanText.length(), wordStartMs, wordEndMs))
                );
            }
        }

    public record ReconstructedWord(int startIndex, int endIndex, long wordStartTimeMs,
                                    long wordEndTimeMs, int wordLen,
                                    List<AlignedSyllable> syllables) {
    }

    public static final class ClusterMeta {
        public final int start;
        public final int end;
        public final int lineIdx;
        public final float x;
        public final float y;
        public final float width;
        public boolean isHeavy = false;
        public boolean disableScale = false;
        public boolean isJoiningScript = false;
        public boolean isRtl = false;
        public long wordStartMs = 0L;
        public long wordEndMs = 0L;
        public float intensity = 0f;
        public float normX = 0f;
        public float sharedElevationCenter = -1f;
        public float charMaxScale = 1.0f;
        public float charShadowIntensity = 0f;
        public float charTranslateYPeak = 0f;
        public long charDelayMs = 0L;

        public ClusterMeta(int start, int end, int lineIdx, float x, float y, float width) {
            this.start = start;
            this.end = end;
            this.lineIdx = lineIdx;
            this.x = x;
            this.y = y;
            this.width = width;
        }
    }

    public record VisualCluster(int start, int end, int lineIdx, float x, float y, float width,
                                boolean isHeavy, boolean disableScale, boolean isJoiningScript,
                                boolean isRtl, long wordStartMs, long wordEndMs, float intensity,
                                float normX, float sharedElevationCenter, Layout subLayout,
                                float charMaxScale, float charShadowIntensity,
                                float charTranslateYPeak, long charDelayMs, int syllableIndex,
                                int shiftIndex) {
            public VisualCluster(
                    int start,
                    int end,
                    int lineIdx,
                    float x,
                    float y,
                    float width,
                    boolean isHeavy,
                    boolean disableScale,
                    boolean isJoiningScript,
                    boolean isRtl,
                    long wordStartMs,
                    long wordEndMs,
                    float intensity,
                    float normX,
                    float sharedElevationCenter,
                    Layout subLayout,
                    float charMaxScale,
                    float charShadowIntensity,
                    float charTranslateYPeak,
                    long charDelayMs,
                    int syllableIndex) {
                this(start, end, lineIdx, x, y, width, isHeavy, disableScale, isJoiningScript, isRtl,
                        wordStartMs, wordEndMs, intensity, normX, sharedElevationCenter, subLayout,
                        charMaxScale, charShadowIntensity, charTranslateYPeak, charDelayMs,
                        syllableIndex, syllableIndex);
            }

    }

    public record ActiveSylInfo(int index, int lineIdx, boolean isLastOfLine) {
    }

    public record SyllableBounds(float[] startX, float[] endX) {
    }

    public record ClusterStatics(float[] baseX, float[] baseY, float[] pivotX, float[] pivotY,
                                 boolean[] isRtlLine, float[] lineLeft, float[] lineRight,
                                 float[] lineStart, float[] lineWidth, float[] localStart,
                                 float[] localEnd) {
    }

    public static final class WipeResult {
        public int mode = WIPE_UNLIT;
        public float bandStart = 0f;
        public float bandEnd = 0f;
        public float clipEdge = 0f;

        private float cachedStart = Float.NaN;
        private float cachedEnd = Float.NaN;
        private int cachedLit = 0;
        private int cachedUnlit = 0;
        private final int[] shaderColors = new int[4];
        @Nullable
        private LinearGradient cachedShader = null;

        public LinearGradient getShader(float bStart, float bEnd, int litColor, int unlitColor) {
            if (cachedShader != null
                    && litColor == cachedLit
                    && unlitColor == cachedUnlit
                    && Math.abs(bStart - cachedStart) < 0.05f
                    && Math.abs(bEnd - cachedEnd) < 0.05f) {
                return cachedShader;
            }
            cachedStart = bStart;
            cachedEnd = bEnd;
            cachedLit = litColor;
            cachedUnlit = unlitColor;
            shaderColors[0] = litColor;
            shaderColors[1] = litColor;
            shaderColors[2] = unlitColor;
            shaderColors[3] = unlitColor;
            cachedShader = new LinearGradient(
                    bStart, 0f, bEnd, 0f,
                    shaderColors,
                    GRADIENT_STOPS,
                    Shader.TileMode.CLAMP);
            return cachedShader;
        }
    }

    private static final Paint STATIC_MASK_PAINT = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public static final class RomanLineStatics {
        public final List<RomanizedWordCluster> clusters;
        public final float[] drawX;
        public final float[] mainStart;
        public final float[] mainEnd;
        public final float[] localStart;
        public final float[] localEnd;
        public final float[] romWidth;
        public final float[] segStartX;
        public final float[] segEndX;
        public final long[] segStartMs;
        public final long[] segEndMs;
        public final float firstMainStart;
        public final float lastMainEnd;
        public final float lastRomEnd;
        public final float y;
        public final boolean[] hasWordIndex;
        public final boolean isRtl;
        @Nullable
        public Bitmap staticMask;
        public float staticMaskOriginX;
        private boolean staticMaskAttempted = false;

        public RomanLineStatics(
                List<RomanizedWordCluster> clusters,
                float[] drawX,
                float[] mainStart,
                float[] mainEnd,
                float[] localStart,
                float[] localEnd,
                float[] romWidth,
                float[] segStartX,
                float[] segEndX,
                long[] segStartMs,
                long[] segEndMs,
                float firstMainStart,
                float lastMainEnd,
                float lastRomEnd,
                float y,
                boolean[] hasWordIndex,
                boolean isRtl) {
            this.clusters = clusters;
            this.drawX = drawX;
            this.mainStart = mainStart;
            this.mainEnd = mainEnd;
            this.localStart = localStart;
            this.localEnd = localEnd;
            this.romWidth = romWidth;
            this.segStartX = segStartX;
            this.segEndX = segEndX;
            this.segStartMs = segStartMs;
            this.segEndMs = segEndMs;
            this.firstMainStart = firstMainStart;
            this.lastMainEnd = lastMainEnd;
            this.lastRomEnd = lastRomEnd;
            this.y = y;
            this.hasWordIndex = hasWordIndex;
            this.isRtl = isRtl;
        }

        public RomanLineStatics withY(float newY) {
            return new RomanLineStatics(
                    clusters, drawX, mainStart, mainEnd, localStart, localEnd, romWidth,
                    segStartX, segEndX, segStartMs, segEndMs,
                    firstMainStart, lastMainEnd, lastRomEnd, newY, hasWordIndex, isRtl);
        }

        public boolean ensureStaticMask() {
            if (staticMask != null) {
                return !staticMask.isRecycled();
            }
            if (staticMaskAttempted || clusters.isEmpty()) {
                return false;
            }
            staticMaskAttempted = true;
            int n = clusters.size();
            float minX = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            int maxH = 0;
            for (int i = 0; i < n; i++) {
                Layout sl = clusters.get(i).subLayout;
                if (sl == null) continue;
                float x = drawX[i];
                if (x < minX) minX = x;
                float right = x + sl.getWidth();
                if (right > maxX) maxX = right;
                int h = sl.getHeight();
                if (h > maxH) maxH = h;
            }
            if (minX == Float.MAX_VALUE || maxH == 0) {
                return false;
            }
            float originX = (float) Math.floor(minX) - 2f;
            int bmpW = (int) Math.ceil(maxX - originX) + 2;
            int bmpH = maxH + 4;
            if (bmpW <= 0 || bmpH <= 0 || bmpW > LyricsGlyphMaskCache.MAX_MASK_SIDE_PX || bmpH > LyricsGlyphMaskCache.MAX_MASK_SIDE_PX) {
                return false;
            }
            Bitmap bmp = LyricsGlyphMaskCache.createAlphaMask(bmpW, bmpH);
            if (bmp == null) {
                return false;
            }
            Canvas c = new Canvas(bmp);
            for (int i = 0; i < n; i++) {
                Layout sl = clusters.get(i).subLayout;
                if (sl != null) {
                    drawLayoutWithPaint(c, sl, drawX[i] - originX, 2f, Color.WHITE, null);
                }
            }
            staticMask = bmp;
            staticMaskOriginX = originX;
            return true;
        }

        @Nullable
        public Bitmap getExistingStaticMask() {
            return (staticMask != null && !staticMask.isRecycled()) ? staticMask : null;
        }

        public void releaseStaticMask() {
            if (staticMask != null) {
                if (!staticMask.isRecycled()) {
                    staticMask.recycle();
                }
                staticMask = null;
            }
            staticMaskAttempted = false;
        }
    }

    public record RomanStatics(RomanLineStatics[] lines, float extraPaddingPx) {

        public boolean isEmpty() {
            for (RomanLineStatics line : lines) {
                if (line != null) return false;
            }
            return true;
        }

        public void releaseStaticMasks() {
            for (RomanLineStatics line : lines) {
                if (line != null) {
                    line.releaseStaticMask();
                }
            }
        }
    }

    public record CharPositionMaps(float[] charXMap, float[] romanStartShift, float[] lineTotalExtra) {
    }

    public static final class ColorAlphaCache {
        private int baseColor = 0;
        private float alpha = -1f;
        private int cachedColor = Color.TRANSPARENT;

        public int get(int base, float a) {
            if (base == baseColor && a == alpha) return cachedColor;
            int newAlpha = Math.max(0, Math.min(255, Math.round(Color.alpha(base) * a)));
            int c = Color.argb(newAlpha, Color.red(base), Color.green(base), Color.blue(base));
            baseColor = base;
            alpha = a;
            cachedColor = c;
            return c;
        }
    }

    public static final class TintFilterCache {
        private int color = 0;
        @Nullable
        private ColorFilter filter = null;

        public ColorFilter get(int target) {
            ColorFilter cached = filter;
            if (cached != null && target == color) return cached;
            ColorFilter created = new PorterDuffColorFilter(target, PorterDuff.Mode.SRC_IN);
            filter = created;
            color = target;
            return created;
        }
    }

    /**
     * Returns true when a word consists purely of CJK characters (Han, Hiragana, Katakana, Hangul),
     * which should use clean syllable elevation (-0.05em) rather than per-letter horizontal wave distortion.
     */
    public static boolean isNeedSplitOnly(@Nullable String text) {
        if (text == null) return false;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return false;
        int i = 0;
        boolean hasCjk = false;
        do {
            int cp = trimmed.codePointAt(i);
            int charCount = (cp > 0xFFFF) ? 2 : 1;
            if (Character.isWhitespace(cp) || isEmphasisAttached((char) cp)) {
                i += charCount;
                continue;
            }
            if ((cp >= 0x4E00 && cp <= 0x9FFF)
                    || (cp >= 0x3400 && cp <= 0x4DBF)
                    || (cp >= 0x20000 && cp <= 0x2A6DF)
                    || (cp >= 0x3040 && cp <= 0x309F)
                    || (cp >= 0x30A0 && cp <= 0x30FF)
                    || (cp >= 0xAC00 && cp <= 0xD7AF)
                    || (cp >= 0x1100 && cp <= 0x11FF)
                    || (cp >= 0x3130 && cp <= 0x318F)) {
                hasCjk = true;
            } else {
                return false;
            }
            i += charCount;
        } while (i < trimmed.length());
        return hasCjk;
    }

    private static boolean isEmphasisAttached(char ch) {
        if ("!?.,;:~…‥。、！？".indexOf(ch) >= 0) return true;
        return ((int) ch >= 0x0300 && (int) ch <= 0x036F)
                || ((int) ch >= 0x3099 && (int) ch <= 0x309A)
                || (int) ch == 0x200D
                || (int) ch == 0xFE0F
                || (int) ch == 0xFE0E;
    }

    public static boolean isCJKChar(char c) {
        return ((int) c >= 0x4E00 && (int) c <= 0x9FFF)
                || ((int) c >= 0x3400 && (int) c <= 0x4DBF)
                || ((int) c >= 0x3040 && (int) c <= 0x309F)
                || ((int) c >= 0x30A0 && (int) c <= 0x30FF)
                || ((int) c >= 0xAC00 && (int) c <= 0xD7AF);
    }

    public static boolean isProhibitedLineStart(char c) {
        return switch (c) {
            case '、', '。', '！', '？', ')', ']', '}', '”', '’', '…', '—', '）', '】', '』', '」', '≫',
                 '》', '〉', '＞', '›', '〕', '〛', ',', '.', '!', '?', ':', ';' -> true;
            default -> false;
        };
    }

    @Nullable
    public static Integer findSafeBreakOffsetOrNull(String mainText, int rawBreakAt, int lineStartOffset) {
        if (rawBreakAt <= lineStartOffset || rawBreakAt > mainText.length()) return null;

        Character before = (rawBreakAt - 1 >= 0) ? mainText.charAt(rawBreakAt - 1) : null;
        Character at = (rawBreakAt >= 0 && rawBreakAt < mainText.length())
                ? mainText.charAt(rawBreakAt) : null;

        boolean atCjkBoundary =
                (before == null || before == ' ' || isCJKChar(before))
                        && (at == null || at == ' ' || isCJKChar(at) || (before != null && isCJKChar(before)));

        int candidate = rawBreakAt;
        if (!atCjkBoundary) {
            int i = rawBreakAt;
            do {
                char c = mainText.charAt(i - 1);
                Character next = (i < mainText.length()) ? mainText.charAt(i) : null;
                if (c == ' ' || isCJKChar(c) || (next != null && isCJKChar(next))) {
                    candidate = i;
                    break;
                }
                i--;
            } while (i > lineStartOffset);
            if (i <= lineStartOffset) return null;
        }

        while (candidate > lineStartOffset
                && candidate < mainText.length()
                && isProhibitedLineStart(mainText.charAt(candidate))) {
            do {
                candidate--;
            } while (candidate > lineStartOffset
                    && !(mainText.charAt(candidate - 1) == ' ' || isCJKChar(mainText.charAt(candidate - 1))));
        }

        return (candidate > lineStartOffset) ? candidate : null;
    }

    public static int findSafeBreakOffset(String mainText, int rawBreakAt, int lineStartOffset) {
        Integer res = findSafeBreakOffsetOrNull(mainText, rawBreakAt, lineStartOffset);
        return res != null ? res : rawBreakAt;
    }

    public static boolean isDash(char c) {
        return switch (c) {
            case '-', '—', '–', '−', '―', '‐', '‑', '~', '〜', '〰', '－' -> true;
            default -> false;
        };
    }

    public static List<int[]> shapingWordRanges(@Nullable List<WordTimestamp> words) {
        if (words == null || words.isEmpty()) return Collections.emptyList();
        List<int[]> out = new ArrayList<>(words.size());
        int start = 0;
        int lastIndex = words.size() - 1;
        do {
            int end = start;
            while (end < lastIndex
                    && !words.get(end).hasTrailingSpace
                    && !endsWithWhitespace(words.get(end).text)
                    && !startsWithWhitespace(words.get(end + 1).text)
                    && words.get(end).isBackground == words.get(end + 1).isBackground) {
                end++;
            }
            if (end > start) {
                StringBuilder joined = new StringBuilder();
                for (int k = start; k <= end; k++) {
                    joined.append(words.get(k).text);
                }
                if (LyricsTextUtils.containsJoiningScript(joined.toString())) {
                    out.add(new int[]{start, end});
                } else {
                    for (int idx = start; idx <= end; idx++) {
                        out.add(new int[]{idx, idx});
                    }
                }
            } else {
                out.add(new int[]{start, start});
            }
            start = end + 1;
        } while (start < words.size());
        return out;
    }

    private static boolean endsWithWhitespace(@Nullable String s) {
        return s != null && !s.isEmpty() && Character.isWhitespace(s.charAt(s.length() - 1));
    }

    private static boolean startsWithWhitespace(@Nullable String s) {
        return s != null && !s.isEmpty() && Character.isWhitespace(s.charAt(0));
    }

    public static List<WordTimestamp> mergeShapingWords(
            List<WordTimestamp> words,
            List<int[]> ranges) {
        if (ranges.size() == words.size()) {
            boolean allSingle = true;
            for (int i = 0; i < ranges.size(); i++) {
                int[] r = ranges.get(i);
                if (r[0] != r[1]) {
                    allSingle = false;
                    break;
                }
            }
            if (allSingle) return words;
        }

        List<WordTimestamp> out = new ArrayList<>(ranges.size());
        for (int rIdx = 0; rIdx < ranges.size(); rIdx++) {
            int[] range = ranges.get(rIdx);
            int first = range[0];
            int last = range[1];
            if (first == last) {
                out.add(words.get(first));
            } else {
                WordTimestamp wFirst = words.get(first);
                WordTimestamp wLast = words.get(last);
                StringBuilder sb = new StringBuilder();
                double maxEnd = wFirst.endTime;
                for (int k = first; k <= last; k++) {
                    WordTimestamp wk = words.get(k);
                    sb.append(wk.text);
                    if (wk.endTime > maxEnd) maxEnd = wk.endTime;
                }
                out.add(wFirst.copy(sb.toString(), wFirst.startTime, maxEnd, wLast.hasTrailingSpace));
            }
        }
        return out;
    }

    public static List<AlignedSyllable> alignSyllablesForFormatting(
            List<WordTimestamp> words,
            String mainText) {
        if (words.isEmpty()) return Collections.emptyList();

        int[] strippedToOriginal = new int[mainText.length() + 1];
        StringBuilder strippedBuilder = new StringBuilder(mainText.length());
        int strippedLen = 0;
        for (int i = 0; i < mainText.length(); i++) {
            char c = mainText.charAt(i);
            if (!LyricsTextUtils.isBidiOrInvisible(c)) {
                strippedBuilder.append(c);
                strippedToOriginal[strippedLen++] = i;
            }
        }
        strippedToOriginal[strippedLen] = mainText.length();
        String strippedMain = strippedBuilder.toString();

        int totalSylLen = 0;
        for (int i = 0; i < words.size(); i++) {
            totalSylLen += LyricsTextUtils.stripBidiControls(words.get(i).text).length();
        }
        totalSylLen = Math.max(1, totalSylLen);

        List<AlignedSyllable> out = new ArrayList<>(words.size());
        int searchIdx = 0;
        for (int i = 0; i < words.size(); i++) {
            WordTimestamp syl = words.get(i);
            String sylText = LyricsTextUtils.stripBidiControls(syl.text);
            int found = sylText.isEmpty() ? -1 : LyricsTextUtils.indexOfIgnoreCase(strippedMain, sylText, searchIdx);
            if (found == -1 && !sylText.trim().isEmpty() && sylText.trim().length() != sylText.length()) {
                String trimmed = sylText.trim();
                found = LyricsTextUtils.indexOfIgnoreCase(strippedMain, trimmed, searchIdx);
                if (found != -1) {
                    sylText = trimmed;
                }
            }
            int si;
            int ei;
            if (found != -1) {
                si = strippedToOriginal[Math.min(found, strippedLen)];
                int endIdx = Math.min(found + sylText.length(), strippedLen);
                ei = strippedToOriginal[endIdx];
                searchIdx = found + sylText.length();
            } else {
                int fallbackStripped = Math.min(searchIdx, strippedLen);
                int propLen = Math.max(1, (int) (((double) sylText.length() / totalSylLen) * strippedLen));
                int endStripped = Math.min(fallbackStripped + propLen, strippedLen);
                si = strippedToOriginal[fallbackStripped];
                ei = strippedToOriginal[endStripped];
                searchIdx = endStripped;
            }
            out.add(new AlignedSyllable(syl, si, ei));
        }
        return out;
    }

    public static String romanizedSyllableText(WordTimestamp word, boolean hasMainTrailingSpace) {
        String clean = LyricsTextUtils.stripBidiControls(word.text);
        if (clean.isEmpty()) return "";
        return clean + ((word.hasTrailingSpace || hasMainTrailingSpace) ? " " : "");
    }

    public static Layout createStaticLayout(
            CharSequence text,
            TextPaint paint,
            int width,
            Layout.Alignment alignment,
            boolean isRtl) {
        LyricsViewSupport.applyLyricsBoldPaint(paint);
        int safeW = Math.max(1, width);
        StaticLayout.Builder builder = StaticLayout.Builder
                .obtain(text, 0, text.length(), paint, safeW)
                .setAlignment(alignment)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            builder.setUseLineSpacingFromFallbacks(true);
        }
        TextDirectionHeuristic dir = isRtl
                ? TextDirectionHeuristics.FIRSTSTRONG_RTL
                : TextDirectionHeuristics.FIRSTSTRONG_LTR;
        builder.setTextDirection(dir);
        return builder.build();
    }

    private static final String NON_JOINING_FONT_FEATURES = "\"liga\" 0, \"clig\" 0, \"dlig\" 0";

    /**
     * Aligns ligature/shaping features between full-line layout paints and split cluster sub-layouts
     * so character-level advances stay consistent on non-joining scripts.
     */
    public static void applyClusterFontFeatures(TextPaint paint, boolean hasJoiningScript) {
        if (hasJoiningScript) {
            paint.setLetterSpacing(0f);
            paint.setFontFeatureSettings(null);
        } else {
            paint.setFontFeatureSettings(NON_JOINING_FONT_FEATURES);
        }
    }

    /**
     * Returns a configured {@link TextPaint} for cluster measurement so callers building many runs
     * on one line avoid repeated {@link TextPaint} copies and HarfBuzz font-feature parsing.
     */
    public static TextPaint createConfiguredRunPaint(
            TextPaint basePaint,
            int color,
            boolean hasJoiningScript) {
        TextPaint runPaint = new TextPaint(basePaint);
        LyricsViewSupport.applyLyricsBoldPaint(runPaint);
        runPaint.setColor(color);
        applyClusterFontFeatures(runPaint, hasJoiningScript);
        return runPaint;
    }

    private static float measureRunWidth(
            String text,
            TextPaint configuredPaint) {
        return Math.max(1f, (float) Math.ceil(Layout.getDesiredWidth(text, configuredPaint)));
    }

    /** Splits text into non-newline grapheme ranges so combining marks stay attached to their base glyph. */
    public static List<int[]> graphemeRanges(String text) {
        int len = text.length();
        List<int[]> ranges = new ArrayList<>(len);
        if (len == 0) {
            return ranges;
        }
        BreakIterator it = BreakIterator.getCharacterInstance();
        it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            int nl = text.indexOf('\n', start);
            if (nl >= 0 && nl < end) {
                for (int k = start; k < end; k++) {
                    if (text.charAt(k) != '\n' && text.charAt(k) != '\r') {
                        ranges.add(new int[]{k, k + 1});
                    }
                }
            } else {
                ranges.add(new int[]{start, end});
            }
        }
        return ranges;
    }

    /** Measures a single cluster/word run using a pre-configured {@link TextPaint}. */
    public static Layout measureRunWithConfiguredPaint(
            String text,
            TextPaint runPaint,
            boolean hasJoiningScript) {
        boolean rtl = LyricsSpanBuilder.isRtlText(text);
        int desiredW = Math.max(1, (int) Math.ceil(Layout.getDesiredWidth(text, runPaint)));
        if (!rtl && !hasJoiningScript) {
            BoringLayout.Metrics boring = BoringLayout.isBoring(text, runPaint);
            if (boring != null) {
                int bw = Math.max(desiredW, boring.width);
                return BoringLayout.make(text, runPaint, bw, Layout.Alignment.ALIGN_NORMAL, 1.0f, 0f, boring, false);
            }
        }
        int width = hasJoiningScript ? desiredW + 2 : desiredW;
        Layout layout = createStaticLayout(text, runPaint, width, Layout.Alignment.ALIGN_NORMAL, rtl);
        if (text.indexOf('\n') < 0 && layout.getLineCount() > 1) {
            Layout wide = createStaticLayout(
                    text, runPaint, LyricsGlyphMaskCache.MAX_MASK_SIDE_PX, Layout.Alignment.ALIGN_NORMAL, rtl);
            int tightW = Math.max(
                    width + 2,
                    (int) Math.ceil(Math.max(wide.getLineMax(0), wide.getLineWidth(0))) + 4);
            layout = createStaticLayout(text, runPaint, tightW, Layout.Alignment.ALIGN_NORMAL, rtl);
            if (layout.getLineCount() > 1 && tightW < LyricsGlyphMaskCache.MAX_MASK_SIDE_PX) {
                layout = wide;
            }
        }
        return layout;
    }

    public static TextPaint createRomanPaint(TextPaint lyricPaint, int color) {
        TextPaint romPaint = new TextPaint(lyricPaint);
        romPaint.setTextSize(lyricPaint.getTextSize() * 0.7f);
        LyricsViewSupport.applyLyricsBoldPaint(romPaint);
        romPaint.setLetterSpacing(0f);
        romPaint.setColor(color);
        return romPaint;
    }

    public static TextPaint createRubyPaint(TextPaint lyricPaint, int color) {
        TextPaint rubyPaint = new TextPaint(lyricPaint);
        rubyPaint.setTextSize(lyricPaint.getTextSize() * 0.5f);
        LyricsViewSupport.applyLyricsBoldPaint(rubyPaint);
        rubyPaint.setLetterSpacing(0f);
        rubyPaint.setColor(color);
        return rubyPaint;
    }

    public static boolean hasAnyRubySyllables(
            boolean rubyActive,
            List<AlignedSyllable> alignedSyllables,
            String mainText) {
        if (!rubyActive || alignedSyllables.isEmpty() || mainText.isEmpty()) {
            return false;
        }
        for (int i = 0; i < alignedSyllables.size(); i++) {
            String r = alignedSyllables.get(i).syllable.ruby;
            if (r != null && !r.trim().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public static List<RomanizedWordCluster> buildRubyClusters(
            boolean rubyActive,
            List<AlignedSyllable> alignedSyllables,
            String mainText,
            Layout textLayoutResult,
            TextPaint lyricPaint,
            int expressiveAccent) {
        if (!rubyActive || alignedSyllables.isEmpty() || mainText.isEmpty()) {
            return Collections.emptyList();
        }
        boolean anyRuby = false;
        for (int i = 0; i < alignedSyllables.size(); i++) {
            String r = alignedSyllables.get(i).syllable.ruby;
            if (r != null && !r.trim().isEmpty()) {
                anyRuby = true;
                break;
            }
        }
        if (!anyRuby) {
            return Collections.emptyList();
        }
        TextPaint rubyPaint = createConfiguredRunPaint(createRubyPaint(lyricPaint, expressiveAccent), expressiveAccent, false);
        List<RomanizedWordCluster> result = new ArrayList<>(alignedSyllables.size());
        int maxCharIdx = mainText.length() - 1;
        for (int idx = 0; idx < alignedSyllables.size(); idx++) {
            AlignedSyllable syl = alignedSyllables.get(idx);
            String text = syl.syllable.ruby;
            if (text != null && !text.trim().isEmpty()) {
                int safeStart = Math.max(0, Math.min(maxCharIdx, syl.startOffset));
                int li = textLayoutResult.getLineForOffset(safeStart);
                Layout subLayout = measureRunWithConfiguredPaint(text.trim(), rubyPaint, false);
                float hPos = textLayoutResult.getPrimaryHorizontal(Math.min(syl.startOffset, mainText.length()));
                result.add(new RomanizedWordCluster(
                        idx,
                        syl.startOffset,
                        syl.endOffset,
                        text.trim(),
                        li,
                        syl.syllable.startTimeMs(),
                        syl.syllable.endTimeMs(),
                        hPos,
                        subLayout
                ));
            }
        }
        return result;
    }

    /** Holds formatted text alongside its pre-aligned syllables when no line breaks were inserted. */
    public static final class FormattedMainText {
        public final String text;
        @Nullable
        public final List<AlignedSyllable> alignedIfUnchanged;

        FormattedMainText(String text, @Nullable List<AlignedSyllable> alignedIfUnchanged) {
            this.text = text;
            this.alignedIfUnchanged = alignedIfUnchanged;
        }
    }

    public static FormattedMainText formatMainTextWithResult(
            String mainText,
            List<WordTimestamp> words,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            boolean showSyllableRomanization,
            boolean rubyActive,
            TextPaint lyricPaint,
            int widthPx) {
        if ((!showSyllableRomanization && !rubyActive) || widthPx <= 0 || LyricsSpanBuilder.isRtlText(mainText) || mainText.trim().isEmpty()) {
            return new FormattedMainText(mainText, null);
        }
        return buildFormattedMainText(
                mainText,
                words,
                romanizedWords,
                romanizedText,
                showSyllableRomanization,
                rubyActive,
                lyricPaint,
                widthPx,
                lyricPaint.getTextSize()
        );
    }

    private static float measureMainSpanWidth(
            String mainText,
            int start,
            int end,
            TextPaint lyricPaint,
            @Nullable Layout unconstrainedResult) {
        final int safeStart = Math.max(0, Math.min(mainText.length(), start));
        final int safeEnd = Math.max(safeStart, Math.min(mainText.length(), end));
        if (safeEnd <= safeStart) {
            return 0f;
        }
        if (unconstrainedResult == null) {
            return Math.max(0f, lyricPaint.getRunAdvance(
                    mainText, safeStart, safeEnd, 0, mainText.length(), false, safeEnd));
        }
        return Math.max(0f, unconstrainedResult.getPrimaryHorizontal(safeEnd)
                - unconstrainedResult.getPrimaryHorizontal(safeStart));
    }

    private static FormattedMainText buildFormattedMainText(
            String mainText,
            List<WordTimestamp> words,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            boolean showSyllableRomanization,
            boolean rubyActive,
            TextPaint lyricPaint,
            int widthPx,
            float fontSizePx) {
        if (mainText.isEmpty() || widthPx <= 0) return new FormattedMainText(mainText, null);

        List<AlignedSyllable> alignedSyllables = alignSyllablesForFormatting(words, mainText);
        if (alignedSyllables.isEmpty()) return new FormattedMainText(mainText, alignedSyllables);

        ResolvedRomanSyllables resolvedRoman = showSyllableRomanization
                ? resolveRomanSyllables(alignedSyllables, romanizedWords, romanizedText, mainText)
                : null;
        boolean anyRuby = hasAnyRubySyllables(rubyActive, alignedSyllables, mainText);
        if (resolvedRoman == null && !anyRuby) return new FormattedMainText(mainText, alignedSyllables);

        TextPaint romRunPaint = resolvedRoman != null
                ? createConfiguredRunPaint(createRomanPaint(lyricPaint, lyricPaint.getColor()), lyricPaint.getColor(), false)
                : null;
        TextPaint rubyRunPaint = anyRuby
                ? createConfiguredRunPaint(createRubyPaint(lyricPaint, lyricPaint.getColor()), lyricPaint.getColor(), false)
                : null;
        final boolean useRunAdvance = mainText.indexOf('\n') < 0
                && !LyricsSpanBuilder.isRtlText(mainText)
                && !LyricsTextUtils.containsJoiningScript(mainText);
        if (useRunAdvance) {
            LyricsViewSupport.applyLyricsBoldPaint(lyricPaint);
        }
        Layout unconstrainedResult = useRunAdvance ? null : createStaticLayout(
                mainText, lyricPaint, Integer.MAX_VALUE / 4, Layout.Alignment.ALIGN_NORMAL, false);

        final int nSyl = alignedSyllables.size();
        final float minClusterGap = Math.max(1.5f, fontSizePx * 0.05f);
        float[] sylSlotWidths = new float[nSyl];
        for (int sylIdx = 0; sylIdx < nSyl; sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            float mainWidth = Math.max(1f, measureMainSpanWidth(
                    mainText, syl.startOffset, syl.endOffset, lyricPaint, unconstrainedResult));
            float romWidth = 0f;
            if (resolvedRoman != null) {
                String rt = resolvedRoman.texts[sylIdx];
                if (rt != null && !rt.trim().isEmpty()) {
                    romWidth = measureRunWidth(rt, romRunPaint);
                    if (!rt.endsWith(" ") && !syl.syllable.hasTrailingSpace && sylIdx + 1 < nSyl) {
                        AlignedSyllable nextSyl = alignedSyllables.get(sylIdx + 1);
                        String curSlice = safeSubstring(mainText, syl.startOffset, syl.endOffset);
                        String nextSlice = safeSubstring(mainText, nextSyl.startOffset, nextSyl.endOffset);
                        String nextRt = resolvedRoman.texts[sylIdx + 1];
                        if (nextRt != null && !nextRt.trim().isEmpty()
                                && !canJoinUnspacedJapaneseSyllables(
                                        curSlice, nextSlice, syl.endOffset, nextSyl.startOffset,
                                        syl.syllable.hasTrailingSpace, rt)) {
                            romWidth += minClusterGap;
                        }
                    }
                }
            }
            float rubyWidth = 0f;
            if (anyRuby) {
                String rb = syl.syllable.ruby;
                if (rb != null && !rb.trim().isEmpty()) {
                    rubyWidth = measureRunWidth(rb.trim(), rubyRunPaint);
                    if (sylIdx + 1 < nSyl) {
                        String nextRb = alignedSyllables.get(sylIdx + 1).syllable.ruby;
                        if (nextRb != null && !nextRb.trim().isEmpty()) {
                            rubyWidth += minClusterGap;
                        }
                    }
                }
            }
            sylSlotWidths[sylIdx] = Math.max(Math.max(mainWidth, romWidth), rubyWidth);
        }

        List<Integer> breakIndices = new ArrayList<>();
        Set<Integer> spaceReplaced = new HashSet<>();

        float currentLineSlotSum = 0f;
        int syllablesOnCurrentLine = 0;
        int currentLineStartOffset = 0;
        float breakThreshold = widthPx - fontSizePx * 0.1f;

        for (int sylIdx = 0; sylIdx < alignedSyllables.size(); sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            float slotW = sylSlotWidths[sylIdx];
            AlignedSyllable prevSyl = (sylIdx > 0) ? alignedSyllables.get(sylIdx - 1) : null;
            int sylStart = Math.max(0, Math.min(mainText.length(), syl.startOffset));

            if (prevSyl != null) {
                int gs = Math.max(0, Math.min(mainText.length(), prevSyl.endOffset));
                int ge = Math.max(gs, sylStart);
                String gapText = mainText.substring(gs, ge);
                if (gapText.indexOf('\n') >= 0) {
                    currentLineSlotSum = 0f;
                    syllablesOnCurrentLine = 0;
                    currentLineStartOffset = sylStart;
                } else {
                    float gapW = measureMainSpanWidth(mainText, gs, ge, lyricPaint, unconstrainedResult);
                    currentLineSlotSum += gapW;
                }
            }

            if (syllablesOnCurrentLine > 0 && currentLineSlotSum + slotW > breakThreshold) {
                Integer breakAt = findSafeBreakOffsetOrNull(mainText, sylStart, currentLineStartOffset);
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

        if (breakIndices.isEmpty()) return new FormattedMainText(mainText, alignedSyllables);

        return new FormattedMainText(insertLineBreaks(mainText, breakIndices, spaceReplaced), null);
    }

    /**
     * Inserts a line break at each break index. A space before a break that is in {@code spaceReplaced}
     * is replaced by the line break.
     */
    private static String insertLineBreaks(String mainText, List<Integer> breakIndices, Set<Integer> spaceReplaced) {
        StringBuilder sb = new StringBuilder(mainText.length() + breakIndices.size());
        int prev = 0;
        for (int i = 0; i < breakIndices.size(); i++) {
            int safeBIdx = Math.max(0, Math.min(mainText.length(), breakIndices.get(i)));
            int safePrev = Math.min(mainText.length(), prev);
            if (safeBIdx > safePrev) {
                int spaceIdx = safeBIdx - 1;
                boolean replaceSpace = spaceReplaced.contains(spaceIdx)
                        && spaceIdx < mainText.length()
                        && mainText.charAt(spaceIdx) == ' ';
                sb.append(mainText, safePrev, replaceSpace ? spaceIdx : safeBIdx);
                sb.append('\n');
                prev = safeBIdx;
            }
        }
        int finalPrev = Math.min(mainText.length(), prev);
        if (finalPrev < mainText.length()) {
            sb.append(mainText.substring(finalPrev));
        }
        return sb.toString();
    }

    /**
     * Maps each character index in {@code formattedText} back to its source offset in {@code mainText}.
     */
    public static int[] mapFormattedToMainOffsets(String formattedText, String mainText) {
        int fmtLen = formattedText.length();
        int mainLen = mainText.length();
        int[] map = new int[fmtLen + 1];
        int maxMain = Math.max(0, mainLen - 1);
        int mi = 0;
        for (int fi = 0; fi < fmtLen; fi++) {
            char fc = formattedText.charAt(fi);
            while (mi < mainLen && mainText.charAt(mi) != fc) {
                if (fc == '\n' && mainText.charAt(mi) == ' ') {
                    mi++;
                    break;
                }
                if (Character.isWhitespace(mainText.charAt(mi))) {
                    mi++;
                } else {
                    break;
                }
            }
            map[fi] = Math.min(mi, maxMain);
            if (mi < mainLen && mainText.charAt(mi) == fc) {
                mi++;
            }
        }
        map[fmtLen] = mainLen;
        return map;
    }

    public static List<RomanizedWordCluster> buildRomanClusters(
            boolean showSyllableRomanization,
            List<AlignedSyllable> alignedSyllables,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            String mainText,
            Layout textLayoutResult,
            TextPaint lyricPaint,
            int expressiveAccent) {
        if (!showSyllableRomanization) {
            return Collections.emptyList();
        }
        TextPaint romPaint = createRomanPaint(lyricPaint, expressiveAccent);
        return buildRomanizedWordClusters(
                alignedSyllables,
                romanizedWords,
                romanizedText,
                mainText,
                textLayoutResult,
                romPaint,
                expressiveAccent
        );
    }

    private record ResolvedRomanSyllables(String[] texts, long[] startMs, long[] endMs) {
    }

    @Nullable
    private static ResolvedRomanSyllables resolveRomanSyllables(
            List<AlignedSyllable> alignedSyllables,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            String mainText) {
        if (alignedSyllables.isEmpty()) return null;

        List<WordTimestamp> rWords = null;
        if (romanizedWords != null && !romanizedWords.isEmpty()) {
            rWords = new ArrayList<>(romanizedWords.size());
            for (int i = 0; i < romanizedWords.size(); i++) {
                WordTimestamp rw = romanizedWords.get(i);
                if (!rw.isBackground) {
                    rWords.add(rw);
                }
            }
            if (rWords.isEmpty()) {
                rWords = romanizedWords;
            }
        } else if (romanizedText != null && !romanizedText.trim().isEmpty()) {
            String[] split = romanizedText.trim().split("\\s+");
            int sylSize = alignedSyllables.size();
            if (split.length == sylSize) {
                rWords = new ArrayList<>(split.length);
                for (int i = 0; i < split.length; i++) {
                    WordTimestamp orig = alignedSyllables.get(i).syllable;
                    rWords.add(new WordTimestamp(
                            split[i],
                            orig.startTime,
                            orig.endTime,
                            true,
                            orig.isBackground,
                            split[i]
                    ));
                }
            } else if (split.length > 0 && sylSize > 0) {
                rWords = new ArrayList<>(sylSize);
                if (split.length < sylSize) {
                    for (int i = 0; i < sylSize; i++) {
                        WordTimestamp orig = alignedSyllables.get(i).syllable;
                        int tokStart = (i * split.length) / sylSize;
                        int tokEnd = ((i + 1) * split.length) / sylSize;
                        String rom = "";
                        if (tokEnd > tokStart && tokStart < split.length) {
                            rom = split[tokStart];
                        }
                        rWords.add(new WordTimestamp(
                                rom,
                                orig.startTime,
                                orig.endTime,
                                !rom.isEmpty(),
                                orig.isBackground,
                                rom
                        ));
                    }
                } else {
                    for (int i = 0; i < sylSize; i++) {
                        WordTimestamp orig = alignedSyllables.get(i).syllable;
                        int tokStart = (i * split.length) / sylSize;
                        int tokEnd = (i == sylSize - 1) ? split.length : (((i + 1) * split.length) / sylSize);
                        StringBuilder sb = new StringBuilder();
                        for (int k = tokStart; k < tokEnd && k < split.length; k++) {
                            if (Utils.isNotEmpty(sb)) sb.append(" ");
                            sb.append(split[k]);
                        }
                        String rom = sb.toString();
                        rWords.add(new WordTimestamp(
                                rom,
                                orig.startTime,
                                orig.endTime,
                                !rom.isEmpty(),
                                orig.isBackground,
                                rom
                        ));
                    }
                }
            }
        }

        if (rWords == null || rWords.isEmpty()) return null;

        String cleanMainText = LyricsTextUtils.stripBidiControls(mainText).trim();
        String cleanRomanText = (romanizedText != null) ? LyricsTextUtils.stripBidiControls(romanizedText).trim() : null;
        if (cleanRomanText != null && cleanRomanText.equalsIgnoreCase(cleanMainText)) {
            return null;
        }

        int n = alignedSyllables.size();
        String[] sylTextArr = new String[n];
        long[] sylStartMsArr = new long[n];
        long[] sylEndMsArr = new long[n];
        StringBuilder assembledBuilder = new StringBuilder();
        boolean anyNonBlank = false;

        for (int i = 0; i < n; i++) {
            AlignedSyllable syl = alignedSyllables.get(i);
            WordTimestamp rw = (i < rWords.size()) ? rWords.get(i) : null;
            boolean hasMainTrailingSpace = syl.syllable.hasTrailingSpace
                    || (syl.endOffset < mainText.length() && mainText.charAt(syl.endOffset) == ' ');
            if (rw != null) {
                sylStartMsArr[i] = rw.startTimeMs();
                sylEndMsArr[i] = rw.endTimeMs();
                sylTextArr[i] = romanizedSyllableText(rw, hasMainTrailingSpace);
            } else {
                sylStartMsArr[i] = syl.syllable.startTimeMs();
                sylEndMsArr[i] = syl.syllable.endTimeMs();
                sylTextArr[i] = "";
            }
            if (!anyNonBlank && !sylTextArr[i].trim().isEmpty()) {
                anyNonBlank = true;
            }
            assembledBuilder.append(sylTextArr[i]);
        }

        if (!anyNonBlank) return null;
        String assembledRoman = LyricsTextUtils.stripBidiControls(assembledBuilder.toString()).trim();
        if (assembledRoman.isEmpty() || assembledRoman.equalsIgnoreCase(cleanMainText)) {
            return null;
        }
        return new ResolvedRomanSyllables(sylTextArr, sylStartMsArr, sylEndMsArr);
    }

    public static boolean hasAnyRomanizedSyllables(
            List<AlignedSyllable> alignedSyllables,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            String mainText) {
        return resolveRomanSyllables(alignedSyllables, romanizedWords, romanizedText, mainText) != null;
    }

    public static List<RomanizedWordCluster> buildRomanizedWordClusters(
            List<AlignedSyllable> alignedSyllables,
            @Nullable List<WordTimestamp> romanizedWords,
            @Nullable String romanizedText,
            String mainText,
            Layout textLayoutResult,
            TextPaint romPaint,
            int expressiveAccent) {
        ResolvedRomanSyllables resolved = resolveRomanSyllables(
                alignedSyllables, romanizedWords, romanizedText, mainText);
        if (resolved == null) return Collections.emptyList();

        int n = alignedSyllables.size();
        TextPaint configuredRomPaint = createConfiguredRunPaint(romPaint, expressiveAccent, false);
        List<RomanizedWordCluster> result = new ArrayList<>(n);
        int maxCharIdx = Math.max(0, mainText.length() - 1);
        for (int idx = 0; idx < n; idx++) {
            String text = resolved.texts[idx];
            if (text != null && !text.trim().isEmpty()) {
                AlignedSyllable syl = alignedSyllables.get(idx);
                int safeStart = Math.max(0, Math.min(maxCharIdx, syl.startOffset));
                int li = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(safeStart);
                int spanEndOffset = syl.endOffset;
                long spanEndMs = resolved.endMs[idx];
                StringBuilder joinedText = new StringBuilder(text);
                List<RomanTimingSegment> segments = new ArrayList<>(4);
                segments.add(new RomanTimingSegment(0, text.length(), resolved.startMs[idx], resolved.endMs[idx]));
                AlignedSyllable prevSyl = syl;
                String prevSlice = safeSubstring(mainText, syl.startOffset, syl.endOffset);

                int nextIdx = idx + 1;
                while (nextIdx < n) {
                    AlignedSyllable nextSyl = alignedSyllables.get(nextIdx);
                    if (nextSyl.startOffset != spanEndOffset) {
                        break;
                    }
                    int nextSafeStart = Math.max(0, Math.min(maxCharIdx, nextSyl.startOffset));
                    if (!mainText.isEmpty() && textLayoutResult.getLineForOffset(nextSafeStart) != li) {
                        break;
                    }
                    if (prevSyl.syllable.isBackground != nextSyl.syllable.isBackground) {
                        break;
                    }
                    String nextRom = resolved.texts[nextIdx];
                    String nextSlice = safeSubstring(mainText, nextSyl.startOffset, nextSyl.endOffset);
                    boolean nextEmpty = (nextRom == null || nextRom.trim().isEmpty());
                    if (nextEmpty) {
                        if (!LyricsWordBoundaries.hasCjkCodePoint(nextSlice)) {
                            break;
                        }
                        spanEndOffset = nextSyl.endOffset;
                        if (resolved.endMs[nextIdx] > spanEndMs) {
                            spanEndMs = resolved.endMs[nextIdx];
                            int lastSegIdx = segments.size() - 1;
                            RomanTimingSegment lastSeg = segments.get(lastSegIdx);
                            segments.set(lastSegIdx, new RomanTimingSegment(
                                    lastSeg.charStart, lastSeg.charEnd, lastSeg.startMs, spanEndMs));
                        }
                        prevSyl = nextSyl;
                        prevSlice = nextSlice;
                        nextIdx++;
                        continue;
                    }
                    // Merge adjacent unspaced Japanese Han/Kana syllables (e.g. "限"+"り" -> "kagiri",
                    // "黒"+"い" -> "kuroi", "愛"+"し"+"て" -> "aishite") into a single word cluster
                    // so kanji stems never pull away from their okurigana and romaji stays whole.
                    if (!canJoinUnspacedJapaneseSyllables(
                            prevSlice, nextSlice, spanEndOffset, nextSyl.startOffset,
                            prevSyl.syllable.hasTrailingSpace, joinedText)) {
                        break;
                    }
                    int segStartChar = joinedText.length();
                    joinedText.append(nextRom);
                    int segEndChar = joinedText.length();
                    segments.add(new RomanTimingSegment(
                            segStartChar, segEndChar, resolved.startMs[nextIdx], resolved.endMs[nextIdx]));
                    spanEndOffset = nextSyl.endOffset;
                    if (resolved.endMs[nextIdx] > spanEndMs) {
                        spanEndMs = resolved.endMs[nextIdx];
                    }
                    prevSyl = nextSyl;
                    prevSlice = nextSlice;
                    nextIdx++;
                }
                String clusterText = joinedText.toString();
                Layout subLayout = measureRunWithConfiguredPaint(clusterText, configuredRomPaint, false);
                float romanWidth = Math.max(
                        subLayout.getWidth(),
                        Utils.isNotEmpty(subLayout.getText())
                                ? subLayout.getPrimaryHorizontal(subLayout.getText().length())
                                : 0f);
                float hPos = textLayoutResult.getPrimaryHorizontal(Math.min(syl.startOffset, mainText.length()));
                result.add(new RomanizedWordCluster(
                        idx,
                        syl.startOffset,
                        spanEndOffset,
                        clusterText,
                        li,
                        resolved.startMs[idx],
                        spanEndMs,
                        hPos,
                        subLayout,
                        romanWidth,
                        Collections.unmodifiableList(segments)
                ));
                idx = nextIdx - 1;
            }
        }
        return result;
    }

    private static String safeSubstring(String text, int start, int end) {
        int s = Math.max(0, Math.min(text.length(), start));
        int e = Math.max(s, Math.min(text.length(), end));
        return text.substring(s, e);
    }

    static boolean canJoinUnspacedJapaneseSyllables(
            String prevSlice,
            String nextSlice,
            int prevEnd,
            int nextStart,
            boolean prevHasTrailingSpace,
            @Nullable CharSequence prevRomaji) {
        if (prevHasTrailingSpace || nextStart != prevEnd) {
            return false;
        }
        if (prevRomaji != null && prevRomaji.length() > 0 && prevRomaji.charAt(prevRomaji.length() - 1) == ' ') {
            return false;
        }
        return hasHanOrKana(prevSlice) && hasHanOrKana(nextSlice);
    }

    private static boolean hasHanOrKana(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (ScriptDetector.isHanOrKana(text.charAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static float[] buildCharXMap(
            String mainText,
            Layout textLayoutResult,
            int lineCount,
            boolean[] lineIsRtl,
            float[] lineStarts) {
        float[] map = new float[mainText.length() + 1];
        int maxOff = Math.max(0, mainText.length() - 1);
        for (int i = 0; i <= mainText.length(); i++) {
            int idx = Math.min(i, maxOff);
            int li = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(idx);
            if (li >= lineCount) {
                map[i] = (lineStarts.length > 0) ? lineStarts[lineStarts.length - 1] : 0f;
            } else {
                float h = textLayoutResult.getPrimaryHorizontal(i);
                float local = lineIsRtl[li]
                        ? (textLayoutResult.getLineRight(li) - h)
                        : (h - textLayoutResult.getLineLeft(li));
                map[i] = lineStarts[li] + local;
            }
        }
        return map;
    }

    public static CharPositionMaps buildCharPositionMaps(
            String mainText,
            Layout textLayoutResult,
            int lineCount,
            boolean[] lineIsRtl,
            float[] lineStarts,
            List<AlignedSyllable> alignedSyllables,
            List<RomanizedWordCluster> romanClusters,
            List<RomanizedWordCluster> rubyClusters,
            Layout.Alignment alignment) {
        float[] charXMap = buildCharXMap(mainText, textLayoutResult, lineCount, lineIsRtl, lineStarts);
        float[][] shiftAndExtra = buildRomanStartShiftPair(
                mainText, charXMap, textLayoutResult, lineCount, alignedSyllables,
                romanClusters, rubyClusters, alignment);
        return new CharPositionMaps(charXMap, shiftAndExtra[0], shiftAndExtra[1]);
    }

    private static float[][] buildRomanStartShiftPair(
            String mainText,
            float[] charXMap,
            Layout textLayoutResult,
            int lineCount,
            List<AlignedSyllable> alignedSyllables,
            List<RomanizedWordCluster> romanClusters,
            List<RomanizedWordCluster> rubyClusters,
            Layout.Alignment alignment) {
        final float fontSizePx = textLayoutResult.getPaint() != null
                ? textLayoutResult.getPaint().getTextSize()
                : 0f;
        final int nSyl = alignedSyllables.size();
        float[] shiftByWordIndex = new float[nSyl * 2 + romanClusters.size() + rubyClusters.size() + 2];
        float[] lineTotalExtra = new float[Math.max(1, lineCount)];
        if ((romanClusters.isEmpty() && rubyClusters.isEmpty()) || LyricsSpanBuilder.isRtlText(mainText) || LyricsTextUtils.containsJoiningScript(mainText)) {
            return new float[][]{shiftByWordIndex, lineTotalExtra};
        }

        Map<Integer, RomanizedWordCluster> clusterMap = new HashMap<>(romanClusters.size() * 2 + 1);
        for (int i = 0; i < romanClusters.size(); i++) {
            RomanizedWordCluster rc = romanClusters.get(i);
            clusterMap.put(rc.wordIndex, rc);
        }
        Map<Integer, RomanizedWordCluster> rubyMap = new HashMap<>(rubyClusters.size() * 2 + 1);
        for (int i = 0; i < rubyClusters.size(); i++) {
            RomanizedWordCluster rc = rubyClusters.get(i);
            rubyMap.put(rc.wordIndex, rc);
        }

        float[] deferredExtraAfterSyl = new float[nSyl];
        float runningShift = 0f;
        int currentLine = -1;
        int coveredUntilSyl = -1;
        int maxCharIdx = Math.max(0, mainText.length() - 1);
        final float minClusterGap = Math.max(1.5f, fontSizePx * 0.05f);

        for (int sylIdx = 0; sylIdx < nSyl; sylIdx++) {
            AlignedSyllable syl = alignedSyllables.get(sylIdx);
            int li = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxCharIdx, syl.startOffset)));
            if (li != currentLine) {
                currentLine = li;
                runningShift = 0f;
                coveredUntilSyl = -1;
            }

            shiftByWordIndex[sylIdx] = runningShift;

            if (sylIdx <= coveredUntilSyl) {
                runningShift += deferredExtraAfterSyl[sylIdx];
                shiftByWordIndex[nSyl + sylIdx] = runningShift;
                continue;
            }

            RomanizedWordCluster cluster = clusterMap.get(sylIdx);
            RomanizedWordCluster rubyCluster = rubyMap.get(sylIdx);
            if (cluster == null && rubyCluster == null) {
                runningShift += deferredExtraAfterSyl[sylIdx];
                shiftByWordIndex[nSyl + sylIdx] = runningShift;
                continue;
            }

            int spanEndOff = Math.max(syl.endOffset,
                    Math.max(cluster != null ? cluster.endIndex : 0, rubyCluster != null ? rubyCluster.endIndex : 0));
            int lastCoveredSyl = sylIdx;
            while (lastCoveredSyl + 1 < nSyl
                    && alignedSyllables.get(lastCoveredSyl + 1).endOffset <= spanEndOff) {
                int candLine = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(
                        Math.max(0, Math.min(maxCharIdx, alignedSyllables.get(lastCoveredSyl + 1).startOffset)));
                if (candLine != li) {
                    break;
                }
                lastCoveredSyl++;
            }

            // In ruby-only mode (or when a kanji has ruby followed by bare unspaced okurigana),
            // let ruby span headroom over adjacent unrubied kana so "限り" / "黒い" never tear apart.
            if (cluster == null && rubyCluster != null) {
                while (lastCoveredSyl + 1 < nSyl) {
                    AlignedSyllable curSyl = alignedSyllables.get(lastCoveredSyl);
                    AlignedSyllable candSyl = alignedSyllables.get(lastCoveredSyl + 1);
                    if (curSyl.syllable.hasTrailingSpace
                            || candSyl.startOffset != curSyl.endOffset
                            || rubyMap.containsKey(lastCoveredSyl + 1)
                            || clusterMap.containsKey(lastCoveredSyl + 1)) {
                        break;
                    }
                    int candLine = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(
                            Math.max(0, Math.min(maxCharIdx, candSyl.startOffset)));
                    if (candLine != li) {
                        break;
                    }
                    String candSlice = safeSubstring(mainText, candSyl.startOffset, candSyl.endOffset);
                    if (!hasHanOrKana(candSlice)) {
                        break;
                    }
                    spanEndOff = candSyl.endOffset;
                    lastCoveredSyl++;
                }
            }

            int sylEndLine = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(Math.min(spanEndOff, maxCharIdx));
            int lineEndOff = getVisibleLineEnd(textLayoutResult, mainText, li);

            int effectiveEndOff = spanEndOff;
            if (li == sylEndLine && lastCoveredSyl + 1 < nSyl) {
                AlignedSyllable nextSyl = alignedSyllables.get(lastCoveredSyl + 1);
                int nextLi = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxCharIdx, nextSyl.startOffset)));
                if (nextLi == li && nextSyl.startOffset > spanEndOff) {
                    effectiveEndOff = nextSyl.startOffset;
                }
            }

            float startX = charXMap[Math.max(0, Math.min(charXMap.length - 1, syl.startOffset))];
            float endX = (li == sylEndLine)
                    ? charXMap[Math.max(0, Math.min(charXMap.length - 1, effectiveEndOff))]
                    : charXMap[Math.max(0, Math.min(charXMap.length - 1, lineEndOff))];
            float mainWidth = Math.max(1f, endX - startX);
            float romWidth = (cluster != null) ? cluster.romanWidth : 0f;
            if (cluster != null && !cluster.romanText.endsWith(" ")
                    && lastCoveredSyl + 1 < nSyl && clusterMap.containsKey(lastCoveredSyl + 1)) {
                romWidth += minClusterGap;
            }
            float rubyWidth = 0f;
            int rubyCountInSpan = 0;
            for (int rIdx = sylIdx; rIdx <= lastCoveredSyl; rIdx++) {
                RomanizedWordCluster rc = rubyMap.get(rIdx);
                if (rc != null) {
                    if (rubyCountInSpan > 0) {
                        rubyWidth += minClusterGap;
                    }
                    rubyWidth += rc.romanWidth;
                    rubyCountInSpan++;
                }
            }
            if (rubyCountInSpan > 0 && lastCoveredSyl + 1 < nSyl && rubyMap.containsKey(lastCoveredSyl + 1)) {
                rubyWidth += minClusterGap;
            }
            float slotSubWidth = Math.max(romWidth, rubyWidth);

            if (slotSubWidth > mainWidth) {
                float extra = slotSubWidth - mainWidth;
                if (lastCoveredSyl > sylIdx) {
                    deferredExtraAfterSyl[lastCoveredSyl] += extra;
                } else {
                    runningShift += extra;
                }
                if (li >= 0 && li < lineTotalExtra.length) {
                    lineTotalExtra[li] += extra;
                }
            }
            if (lastCoveredSyl > coveredUntilSyl) {
                coveredUntilSyl = lastCoveredSyl;
            }
            runningShift += deferredExtraAfterSyl[sylIdx];
            shiftByWordIndex[nSyl + sylIdx] = runningShift;
        }

        if (alignment == Layout.Alignment.ALIGN_CENTER || alignment == Layout.Alignment.ALIGN_OPPOSITE) {
            for (int idx = 0; idx < nSyl; idx++) {
                AlignedSyllable syl = alignedSyllables.get(idx);
                int li = mainText.isEmpty() ? 0 : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxCharIdx, syl.startOffset)));
                float totalExtra = (li >= 0 && li < lineTotalExtra.length) ? lineTotalExtra[li] : 0f;
                float correction = (alignment == Layout.Alignment.ALIGN_CENTER) ? (totalExtra / 2f) : totalExtra;
                shiftByWordIndex[idx] -= correction;
                shiftByWordIndex[nSyl + idx] -= correction;
            }
        }

        return new float[][]{shiftByWordIndex, lineTotalExtra};
    }

    private static int getVisibleLineEnd(Layout layout, String text, int line) {
        int end = layout.getLineEnd(line);
        while (end > layout.getLineStart(line) && end - 1 < text.length()) {
            char ch = text.charAt(end - 1);
            if (ch == '\n' || ch == '\r' || ch == ' ' || ch == '\t') {
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    public static float calculateGapShift(int syllableIndex, float[] romanStartShift) {
        if (syllableIndex < 0 || syllableIndex >= romanStartShift.length) return 0f;
        return romanStartShift[syllableIndex];
    }

    public static SyllableBounds buildSyllableBounds(
            List<AlignedSyllable> alignedSyllables,
            float[] charXMap,
            Layout textLayoutResult,
            String formattedMainText) {
        int n = alignedSyllables.size();
        float[] startX = new float[n];
        float[] endX = new float[n];
        int maxLen = Math.max(0, formattedMainText.length() - 1);

        for (int i = 0; i < n; i++) {
            AlignedSyllable current = alignedSyllables.get(i);
            int currentLine = formattedMainText.isEmpty()
                    ? 0
                    : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxLen, current.startOffset)));
            int startOff;
            if (i > 0) {
                AlignedSyllable prev = alignedSyllables.get(i - 1);
                int prevLine = formattedMainText.isEmpty()
                        ? 0
                        : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxLen, prev.endOffset)));
                startOff = (prevLine == currentLine) ? prev.endOffset : current.startOffset;
            } else {
                startOff = current.startOffset;
            }
            startX[i] = (startOff < 0 || charXMap.length == 0)
                    ? 0f
                    : charXMap[Math.max(0, Math.min(charXMap.length - 1, startOff))];
            endX[i] = (current.endOffset < 0 || charXMap.length == 0)
                    ? 0f
                    : charXMap[Math.max(0, Math.min(charXMap.length - 1, current.endOffset))];
        }
        return new SyllableBounds(startX, endX);
    }

    public static List<ReconstructedWord> buildReconstructedWords(
            List<AlignedSyllable> alignedSyllables,
            String formattedMainText) {
        List<ReconstructedWord> result = new ArrayList<>();
        int idx = 0;
        while (idx < alignedSyllables.size()) {
            List<AlignedSyllable> group = new ArrayList<>();
            group.add(alignedSyllables.get(idx));
            idx++;
            while (idx < alignedSyllables.size()) {
                AlignedSyllable prev = group.get(group.size() - 1);
                AlignedSyllable next = alignedSyllables.get(idx);
                int gapStart = prev.endOffset;
                int gapEnd = next.startOffset;
                String gapStr = gapEnd > gapStart
                        ? formattedMainText.substring(
                                Math.max(0, Math.min(formattedMainText.length(), gapStart)),
                                Math.max(0, Math.min(formattedMainText.length(), gapEnd)))
                        : "";
                boolean hasSpaceBetween = prev.syllable.hasTrailingSpace
                        || gapStr.indexOf(' ') >= 0
                        || gapStr.indexOf('\n') >= 0
                        || isNeedSplitOnly(prev.syllable.text) != isNeedSplitOnly(next.syllable.text);
                if (hasSpaceBetween) break;
                group.add(next);
                idx++;
            }
            if (!group.isEmpty()) {
                int wordLen = 0;
                for (int k = 0; k < group.size(); k++) {
                    wordLen += group.get(k).syllable.text.length();
                }
                AlignedSyllable first = group.get(0);
                AlignedSyllable last = group.get(group.size() - 1);
                result.add(new ReconstructedWord(
                        first.startOffset,
                        last.endOffset,
                        first.syllable.startTimeMs(),
                        last.syllable.endTimeMs(),
                        wordLen,
                        group
                ));
            }
        }
        return result;
    }

    public static ClusterStatics buildClusterStatics(
            List<VisualCluster> clusters,
            float[] romanStartShift,
            float lineExtraHeight,
            float firstLineRubyOffset,
            boolean[] lineIsRtl,
            float[] lineLefts,
            float[] lineRights,
            float[] lineStarts,
            float[] lineWidths,
            float fontSizePx) {
        int n = clusters.size();
        float[] baseX = new float[n];
        float[] baseY = new float[n];
        float[] pivotX = new float[n];
        float[] pivotY = new float[n];
        boolean[] rtl = new boolean[n];
        float[] ll = new float[n];
        float[] lr = new float[n];
        float[] ls = new float[n];
        float[] lw = new float[n];
        float[] localStart = new float[n];
        float[] localEnd = new float[n];

        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            int li = vc.lineIdx;
            boolean isRtl = lineIsRtl[li];
            int shiftIdx = vc.syllableIndex >= 0 ? vc.syllableIndex : vc.shiftIndex;
            float gapShift = (vc.isRtl || isRtl || vc.isJoiningScript) ? 0f : calculateGapShift(shiftIdx, romanStartShift);
            baseX[i] = vc.x + gapShift;
            baseY[i] = vc.y + li * lineExtraHeight + firstLineRubyOffset;
            rtl[i] = isRtl;
            float lineL = lineLefts[li];
            float lineR = lineRights[li];
            ll[i] = lineL;
            lr[i] = lineR;
            ls[i] = lineStarts[li];
            lw[i] = lineWidths[li];

            float lStart = isRtl ? (lineR - (vc.x + vc.width)) : (vc.x - lineL);
            localStart[i] = lStart;
            localEnd[i] = lStart + vc.width;

            pivotX[i] = vc.width / 2f;
            float subH = vc.subLayout.getHeight();
            pivotY[i] = subH * 0.8f + fontSizePx * 0.3f;
        }

        return new ClusterStatics(
                baseX, baseY, pivotX, pivotY,
                rtl, ll, lr, ls, lw,
                localStart, localEnd);
    }

    public static void computeWipe(
            VisualCluster vc,
            boolean isRtlLine,
            float lineStart,
            float lineWidth,
            float localStart,
            float localEnd,
            int activeSylIdx,
            int activeLineIdx,
            float curGX,
            boolean isActiveSylLastOfLine,
            float activeSylProgress,
            float gradientWidth,
            WipeResult out) {
        float halfGradientWidth = gradientWidth * 0.5f;

        boolean isPartOfActiveSyl = vc.syllableIndex != -1 && vc.syllableIndex == activeSylIdx;
        int effectiveLineIdx = isPartOfActiveSyl ? vc.lineIdx : activeLineIdx;

        float rawLocalX = curGX - lineStart;
        float brushShift = (vc.lineIdx == effectiveLineIdx && isActiveSylLastOfLine)
                ? (activeSylProgress * halfGradientWidth)
                : 0f;

        float localX;
        float hardLocalX;
        if (activeSylIdx == -1) {
            localX = -gradientWidth * 2f;
            hardLocalX = -1f;
        } else if (isPartOfActiveSyl) {
            localX = rawLocalX + brushShift;
            hardLocalX = rawLocalX;
        } else if (vc.lineIdx < activeLineIdx) {
            localX = lineWidth + gradientWidth * 2f;
            hardLocalX = lineWidth + 1f;
        } else if (vc.lineIdx > activeLineIdx) {
            localX = -gradientWidth * 2f;
            hardLocalX = -1f;
        } else {
            localX = rawLocalX + brushShift;
            hardLocalX = rawLocalX;
        }

        float relHard = hardLocalX - localStart;
        out.clipEdge = isRtlLine
                ? Math.max(0f, Math.min(vc.width, vc.width - relHard))
                : Math.max(0f, Math.min(vc.width, relHard));

        if (localX >= localEnd + halfGradientWidth) {
            out.mode = WIPE_PAST;
            return;
        }
        if (localX <= localStart - halfGradientWidth) {
            out.mode = WIPE_UNLIT;
            return;
        }

        out.mode = WIPE_BAND;
        float relWiperX = localX - localStart;
        if (localX < halfGradientWidth) {
            float growProgress = Math.max(0f, Math.min(1f, localX / halfGradientWidth));
            float growWidth = Math.max(0.1f, gradientWidth * growProgress);
            if (isRtlLine) {
                out.bandStart = vc.width + localStart;
                out.bandEnd = vc.width + localStart - growWidth;
            } else {
                out.bandStart = -localStart;
                out.bandEnd = growWidth - localStart;
            }
        } else {
            if (isRtlLine) {
                out.bandStart = vc.width - (relWiperX - halfGradientWidth);
                out.bandEnd = vc.width - (relWiperX + halfGradientWidth);
            } else {
                out.bandStart = relWiperX - halfGradientWidth;
                out.bandEnd = relWiperX + halfGradientWidth;
            }
        }
    }

    public static void drawLayoutWithPaint(
            Canvas canvas,
            Layout layout,
            float topLeftX,
            float topLeftY,
            int color,
            @Nullable Shader shader) {
        TextPaint paint = layout.getPaint();
        int prevColor = paint.getColor();
        Shader prevShader = paint.getShader();
        paint.setColor(color);
        paint.setShader(shader);
        canvas.save();
        canvas.translate(topLeftX, topLeftY);
        layout.draw(canvas);
        canvas.restore();
        paint.setShader(prevShader);
        paint.setColor(prevColor);
    }

    public static void drawWipeText(
            Canvas canvas,
            VisualCluster vc,
            float topLeftX,
            float topLeftY,
            boolean isRtlLine,
            float lineStart,
            float lineWidth,
            float localStart,
            float localEnd,
            int activeSylIdx,
            int activeLineIdx,
            float curGX,
            boolean isActiveSylLastOfLine,
            float activeSylProgress,
            float gradientWidth,
            int pastColor,
            int unlitColor,
            int litColor,
            WipeResult wipe) {
        computeWipe(
                vc, isRtlLine, lineStart, lineWidth, localStart, localEnd,
                activeSylIdx, activeLineIdx, curGX, isActiveSylLastOfLine,
                activeSylProgress, gradientWidth, wipe);
        if (!Settings.LYRICS_WORD_SMOOTH_SWEEP.get()) {
            if (wipe.mode == WIPE_PAST || (!isRtlLine && wipe.clipEdge >= vc.width) || (isRtlLine && wipe.clipEdge <= 0f)) {
                drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, pastColor, null);
            } else if (wipe.mode == WIPE_UNLIT || (!isRtlLine && wipe.clipEdge <= 0f) || (isRtlLine && wipe.clipEdge >= vc.width)) {
                drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, unlitColor, null);
            } else {
                drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, unlitColor, null);
                int h = vc.subLayout.getHeight();
                canvas.save();
                if (isRtlLine) {
                    canvas.clipRect(topLeftX + wipe.clipEdge, topLeftY, topLeftX + vc.width, topLeftY + h);
                } else {
                    canvas.clipRect(topLeftX, topLeftY, topLeftX + wipe.clipEdge, topLeftY + h);
                }
                drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, litColor, null);
                canvas.restore();
            }
            return;
        }
        if (wipe.mode == WIPE_PAST) {
            drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, pastColor, null);
        } else if (wipe.mode == WIPE_UNLIT) {
            drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, unlitColor, null);
        } else {
            float bStart = wipe.bandStart;
            float bEnd = wipe.bandEnd;
            if (Math.abs(bEnd - bStart) < 0.01f) {
                bEnd = bStart + 0.01f;
            }
            LinearGradient shader = wipe.getShader(bStart, bEnd, litColor, unlitColor);
            drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, Color.WHITE, shader);
        }
    }

    public static List<RomanizedWordCluster>[] groupRomanClustersByLine(
            List<RomanizedWordCluster> romanClusters,
            int lineCount) {
        @SuppressWarnings("unchecked")
        List<RomanizedWordCluster>[] out = new List[Math.max(0, lineCount)];
        for (int i = 0; i < out.length; i++) {
            out[i] = Collections.emptyList();
        }
        if (romanClusters.isEmpty() || lineCount <= 0) return out;

        List<RomanizedWordCluster> sorted = new ArrayList<>(romanClusters);
        sorted.sort(Comparator.comparingInt(a -> a.wordIndex));
        for (int li = 0; li < lineCount; li++) {
            List<RomanizedWordCluster> bucket = null;
            for (int i = 0; i < sorted.size(); i++) {
                RomanizedWordCluster rc = sorted.get(i);
                if (rc.lineIdx == li) {
                    if (bucket == null) bucket = new ArrayList<>();
                    bucket.add(rc);
                }
            }
            if (bucket != null) {
                out[li] = bucket;
            }
        }
        return out;
    }

    private static RomanStatics buildRomanStatics(
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
            float totalWidth,
            float extraPaddingPx,
            @Nullable float[] lineRights,
            @Nullable boolean[] lineIsRtl,
            boolean isRightAligned) {
        int count = Math.min(lineCount, romanLines.length);
        RomanLineStatics[] lines = new RomanLineStatics[count];

        for (int li = 0; li < count; li++) {
            List<RomanizedWordCluster> cs = romanLines[li];
            if (cs == null || cs.isEmpty()) {
                lines[li] = null;
                continue;
            }
            boolean isRtl = (lineIsRtl != null && li < lineIsRtl.length) && lineIsRtl[li];
            float lineLeft = (li < lineLefts.length) ? lineLefts[li] : 0f;
            float lineRight = (lineRights != null && li < lineRights.length) ? lineRights[li] : 0f;
            float lineStart = (li < lineStarts.length) ? lineStarts[li] : 0f;
            int n = cs.size();
            float[] drawX = new float[n];
            float[] mainStart = new float[n];
            float[] mainEnd = new float[n];
            float[] romWidth = new float[n];
            int maxWordIdx = isRtl
                    ? populateRtlRomanLine(cs, charXMap, lineLeft, lineRight, totalWidth, isRightAligned, drawX, mainStart, mainEnd, romWidth)
                    : populateLtrRomanLine(cs, charXMap, romanStartShift, lineLeft, lineStart, totalWidth, drawX, mainStart, mainEnd, romWidth);

            float firstRomX = drawX[0];
            float[] localStart = new float[n];
            float[] localEnd = new float[n];
            int totalSegs = 0;
            for (int i = 0; i < n; i++) {
                localStart[i] = drawX[i] - firstRomX;
                localEnd[i] = localStart[i] + romWidth[i];
                List<RomanTimingSegment> segs = cs.get(i).segments;
                totalSegs += (segs != null && !segs.isEmpty()) ? segs.size() : 1;
            }

            float[] segStartX = new float[totalSegs];
            float[] segEndX = new float[totalSegs];
            long[] segStartMs = new long[totalSegs];
            long[] segEndMs = new long[totalSegs];
            flattenLineTimingSegments(cs, localStart, localEnd, segStartX, segEndX, segStartMs, segEndMs);

            boolean[] hasWordIndex = new boolean[maxWordIdx + 1];
            for (int i = 0; i < n; i++) {
                RomanizedWordCluster c = cs.get(i);
                if (c.wordIndex >= 0 && c.wordIndex < hasWordIndex.length) {
                    hasWordIndex[c.wordIndex] = true;
                }
            }

            float baseLineBottom = (li < lineBottoms.length)
                    ? lineBottoms[li]
                    : (li < lineBaselines.length ? lineBaselines[li] + fontSizePx * 0.20f : 0f);
            float y = (li < lineBaselines.length ? lineBaselines[li] + fontSizePx * 0.18f : baseLineBottom - fontSizePx * 0.05f)
                    + li * lineExtraHeight;

            lines[li] = new RomanLineStatics(
                    cs,
                    drawX,
                    mainStart,
                    mainEnd,
                    localStart,
                    localEnd,
                    romWidth,
                    segStartX,
                    segEndX,
                    segStartMs,
                    segEndMs,
                    mainStart[0],
                    mainEnd[n - 1],
                    localEnd[n - 1],
                    y,
                    hasWordIndex,
                    isRtl
            );
        }
        return new RomanStatics(lines, extraPaddingPx);
    }

    private static int populateRtlRomanLine(
            List<RomanizedWordCluster> cs,
            float[] charXMap,
            float lineLeft,
            float lineRight,
            float totalWidth,
            boolean isRightAligned,
            float[] drawX,
            float[] mainStart,
            float[] mainEnd,
            float[] romWidth) {
        int n = cs.size();
        int maxWordIdx = 0;
        float totalRomW = 0f;
        for (int i = 0; i < n; i++) {
            romWidth[i] = cs.get(i).romanWidth;
            totalRomW += romWidth[i];
            if (cs.get(i).wordIndex > maxWordIdx) {
                maxWordIdx = cs.get(i).wordIndex;
            }
        }
        float curX;
        if (isRightAligned) {
            float targetRight = lineRight > 0f ? lineRight : (lineLeft + totalWidth);
            curX = Math.max(0f, targetRight - totalRomW);
        } else {
            curX = lineLeft;
        }
        for (int i = 0; i < n; i++) {
            RomanizedWordCluster c = cs.get(i);
            drawX[i] = curX;
            curX += romWidth[i];
            mainStart[i] = clampCharX(charXMap, c.startIndex);
            mainEnd[i] = clampCharX(charXMap, c.endIndex);
        }
        return maxWordIdx;
    }

    private static int populateLtrRomanLine(
            List<RomanizedWordCluster> cs,
            float[] charXMap,
            float[] romanStartShift,
            float lineLeft,
            float lineStart,
            float totalWidth,
            float[] drawX,
            float[] mainStart,
            float[] mainEnd,
            float[] romWidth) {
        int n = cs.size();
        int maxWordIdx = 0;
        float[] baseDrawX = new float[n];
        for (int i = 0; i < n; i++) {
            RomanizedWordCluster c = cs.get(i);
            float gx = (c.startIndex < 0)
                    ? 0f
                    : ((c.startIndex >= charXMap.length) ? totalWidth : charXMap[c.startIndex]);
            float shift = (c.wordIndex >= 0 && c.wordIndex < romanStartShift.length)
                    ? romanStartShift[c.wordIndex]
                    : 0f;
            baseDrawX[i] = gx - lineStart + lineLeft + shift;
            mainStart[i] = clampCharX(charXMap, c.startIndex);
            mainEnd[i] = clampCharX(charXMap, c.endIndex);
            romWidth[i] = c.romanWidth;
            if (c.wordIndex > maxWordIdx) {
                maxWordIdx = c.wordIndex;
            }
        }
        for (int i = 0; i < n; i++) {
            RomanizedWordCluster c = cs.get(i);
            float dx = baseDrawX[i];
            float mainSpanW = mainEnd[i] - mainStart[i];
            int trimLen = c.romanText.length();
            while (trimLen > 0 && c.romanText.charAt(trimLen - 1) == ' ') {
                trimLen--;
            }
            float visRomW = (trimLen < c.romanText.length() && trimLen > 0
                    && c.subLayout.getText().length() >= trimLen)
                    ? Math.max(1f, c.subLayout.getPrimaryHorizontal(trimLen))
                    : c.romanWidth;
            if (mainSpanW > visRomW + 1f
                    && (c.endIndex - c.startIndex <= 4
                    || (c.endIndex - c.startIndex <= 10 && c.romanText.trim().indexOf(' ') < 0))) {
                float centerOffset = (mainSpanW - visRomW) * 0.5f;
                if (i + 1 < n) {
                    float maxOffset = Math.max(0f, baseDrawX[i + 1] - dx - c.romanWidth);
                    centerOffset = Math.min(centerOffset, maxOffset);
                }
                dx += Math.max(0f, centerOffset);
            }
            drawX[i] = dx;
        }
        return maxWordIdx;
    }

    private static float clampCharX(float[] charXMap, int charIndex) {
        if (charXMap.length == 0) {
            return 0f;
        }
        return charXMap[Math.max(0, Math.min(charXMap.length - 1, charIndex))];
    }

    private static void flattenLineTimingSegments(
            List<RomanizedWordCluster> cs,
            float[] localStart,
            float[] localEnd,
            float[] segStartX,
            float[] segEndX,
            long[] segStartMs,
            long[] segEndMs) {
        int sOut = 0;
        for (int i = 0; i < cs.size(); i++) {
            RomanizedWordCluster c = cs.get(i);
            List<RomanTimingSegment> segs = c.segments;
            if (segs == null || segs.isEmpty()) {
                segStartX[sOut] = localStart[i];
                segEndX[sOut] = localEnd[i];
                segStartMs[sOut] = c.wordStartMs;
                segEndMs[sOut] = c.wordEndMs;
                sOut++;
                continue;
            }
            int textLen = c.subLayout != null && c.subLayout.getText() != null
                    ? c.subLayout.getText().length() : c.romanText.length();
            for (int k = 0; k < segs.size(); k++) {
                RomanTimingSegment seg = segs.get(k);
                float sx = measureClusterCharX(c, textLen, seg.charStart);
                float ex = measureClusterCharX(c, textLen, seg.charEnd);
                if (ex < sx) ex = sx;
                segStartX[sOut] = localStart[i] + sx;
                segEndX[sOut] = localStart[i] + ex;
                segStartMs[sOut] = seg.startMs;
                segEndMs[sOut] = seg.endMs;
                sOut++;
            }
        }
    }

    private static float measureClusterCharX(RomanizedWordCluster c, int textLen, int charIdx) {
        if (charIdx <= 0 || textLen <= 0 || c.subLayout == null) {
            return 0f;
        }
        if (charIdx >= textLen) {
            return c.romanWidth;
        }
        return Math.max(0f, Math.min(c.romanWidth, c.subLayout.getPrimaryHorizontal(charIdx)));
    }

    public static RomanStatics buildRomanStaticsWithOffset(
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
            float firstLineRubyOffset,
            float totalWidth,
            float extraPaddingPx,
            @Nullable float[] lineRights,
            @Nullable boolean[] lineIsRtl,
            boolean isRightAligned) {
        RomanStatics base = buildRomanStatics(
                romanLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight,
                totalWidth, extraPaddingPx, lineRights, lineIsRtl, isRightAligned);
        if (firstLineRubyOffset == 0f || base.lines().length == 0) {
            return base;
        }
        RomanLineStatics[] shifted = new RomanLineStatics[base.lines().length];
        for (int i = 0; i < base.lines().length; i++) {
            RomanLineStatics l = base.lines()[i];
            if (l != null) {
                shifted[i] = l.withY(l.y + firstLineRubyOffset);
            }
        }
        return new RomanStatics(shifted, extraPaddingPx);
    }

    public static RomanStatics buildRubyStatics(
            List<RomanizedWordCluster>[] rubyLines,
            float[] charXMap,
            float[] romanStartShift,
            float[] lineBaselines,
            float[] lineBottoms,
            float[] lineLefts,
            float[] lineStarts,
            int lineCount,
            float fontSizePx,
            float lineExtraHeight,
            float firstLineRubyOffset,
            float totalWidth,
            float extraPaddingPx,
            @Nullable float[] lineRights,
            @Nullable boolean[] lineIsRtl,
            boolean isRightAligned) {
        RomanStatics base = buildRomanStatics(
                rubyLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight,
                totalWidth, extraPaddingPx, lineRights, lineIsRtl, isRightAligned);
        if (base.lines().length == 0) {
            return base;
        }
        RomanLineStatics[] ruby = new RomanLineStatics[base.lines().length];
        float rubyGap = Math.max(2f, fontSizePx * 0.12f);
        for (int li = 0; li < base.lines().length; li++) {
            RomanLineStatics l = base.lines()[li];
            if (l != null) {
                float maxH = 0f;
                for (int i = 0; i < l.clusters.size(); i++) {
                    maxH = Math.max(maxH, l.clusters.get(i).subLayout.getHeight());
                }
                float baseBaseline = (li < lineBaselines.length) ? lineBaselines[li] : (fontSizePx * 0.8f);
                float y = baseBaseline - fontSizePx * 0.85f - rubyGap - maxH
                        + li * lineExtraHeight + firstLineRubyOffset;
                ruby[li] = l.withY(y);
            }
        }
        return new RomanStatics(ruby, extraPaddingPx);
    }

    public static int lerpColor(int startColor, int endColor, float fraction) {
        float t = Math.max(0f, Math.min(1f, fraction));
        int a = Math.round(Color.alpha(startColor) + (Color.alpha(endColor) - Color.alpha(startColor)) * t);
        int r = Math.round(Color.red(startColor) + (Color.red(endColor) - Color.red(startColor)) * t);
        int g = Math.round(Color.green(startColor) + (Color.green(endColor) - Color.green(startColor)) * t);
        int b = Math.round(Color.blue(startColor) + (Color.blue(endColor) - Color.blue(startColor)) * t);
        return Color.argb(a, r, g, b);
    }

    /** Returns the base wipe X position along a line's romanized timeline, plus optional trailing brush progress in out[1]. */
    static void computeIndependentRomanWipe(
            float[] segStartX,
            float[] segEndX,
            long[] segStartMs,
            long[] segEndMs,
            float lastRomEnd,
            long currentPosMs,
            float subGradientWidth,
            boolean forcePast,
            float[] out) {
        if (forcePast) {
            out[0] = Float.MAX_VALUE;
            out[1] = 0f;
            return;
        }
        int m = segStartX.length;
        if (m == 0 || currentPosMs < segStartMs[0]) {
            out[0] = -subGradientWidth * 2f;
            out[1] = 0f;
            return;
        }
        long maxEndMs = segEndMs[0];
        for (int i = 1; i < m; i++) {
            if (segEndMs[i] > maxEndMs) {
                maxEndMs = segEndMs[i];
            }
        }
        if (currentPosMs > maxEndMs) {
            out[0] = Float.MAX_VALUE;
            out[1] = 0f;
            return;
        }
        float gx = segStartX[0];
        float brushProgress = 0f;
        for (int i = 0; i < m; i++) {
            long ss = segStartMs[i];
            long se = Math.max(ss, segEndMs[i]);
            if (currentPosMs >= se) {
                float endX = (i == m - 1) ? Math.max(segEndX[i], lastRomEnd) : segEndX[i];
                if (endX > gx) gx = endX;
                if (i == m - 1) brushProgress = 1f;
            } else if (currentPosMs >= ss) {
                long dur = Math.max(1L, se - ss);
                float prog = Math.max(0f, Math.min(1f, (float) (currentPosMs - ss) / (float) dur));
                float pos = segStartX[i] + (segEndX[i] - segStartX[i]) * prog;
                if (pos > gx) gx = pos;
                if (i == m - 1) brushProgress = Math.max(brushProgress, prog);
            } else if (i > 0) {
                long prevEnd = Math.max(segStartMs[i - 1], segEndMs[i - 1]);
                if (currentPosMs > prevEnd && ss > prevEnd) {
                    float gapProg = (float) (currentPosMs - prevEnd) / (float) (ss - prevEnd);
                    float pos = segEndX[i - 1] + (segStartX[i] - segEndX[i - 1]) * gapProg;
                    if (pos > gx) gx = pos;
                }
            }
        }
        out[0] = gx;
        out[1] = brushProgress * (subGradientWidth * 0.5f);
    }

    public static void drawRomanizedWordClusters(
            Canvas canvas,
            RomanStatics romanStatics,
            boolean isActiveLine,
            int activeSylIdx,
            int activeLineIdx,
            float curGX,
            boolean isActiveSylLastOfLine,
            float activeSylProgress,
            float gradientWidth,
            int pastColor,
            int unlitColor,
            int litColor,
            int lineColor,
            float curSimpleState,
            int expressiveAccent,
            float revealScaleProgress,
            float slideOffsetY,
            Layout.Alignment alignment,
            float viewWidth,
            boolean allowRasterizeMask,
            boolean independentTimeSweep,
            long currentPosMs,
            boolean forcePast,
            @Nullable float[][] lineClusterElevY) {
        RomanLineStatics[] lines = romanStatics.lines();
        if (lines.length == 0) return;

        float pad = romanStatics.extraPaddingPx();
        float subGradientWidth = Math.max(1f, gradientWidth * 0.7f);
        float halfGradientWidth = subGradientWidth * 0.5f;
        float[] indOut = independentTimeSweep ? new float[2] : null;
        int inactiveColor = Color.TRANSPARENT;
        if (!isActiveLine) {
            int baseColor = (lineColor != Color.TRANSPARENT) ? lineColor : expressiveAccent;
            inactiveColor = lerpColor(baseColor, expressiveAccent, Math.max(0f, Math.min(1f, curSimpleState)));
        }

        for (int li = 0; li < lines.length; li++) {
            RomanLineStatics line = lines[li];
            if (line == null) continue;
            List<RomanizedWordCluster> cs = line.clusters;
            int n = cs.size();
            float topY = line.y + pad;
            float[] lineElevY = (lineClusterElevY != null && li < lineClusterElevY.length)
                    ? lineClusterElevY[li]
                    : null;

            boolean scalingReveal = Math.abs(revealScaleProgress - 1f) > 0.001f;
            if (scalingReveal) {
                float scale = 0.8f + 0.2f * revealScaleProgress;
                float slideY = (1f - revealScaleProgress) * slideOffsetY;
                float pivotX;
                if (alignment == Layout.Alignment.ALIGN_CENTER) {
                    pivotX = (viewWidth > 0f ? viewWidth * 0.5f : (line.drawX[0] + line.lastRomEnd * 0.5f)) + pad;
                } else if (alignment == Layout.Alignment.ALIGN_OPPOSITE) {
                    pivotX = (viewWidth > 0f ? viewWidth : (line.drawX[0] + line.lastRomEnd)) + pad;
                } else {
                    pivotX = line.drawX[0] + pad;
                }
                canvas.save();
                canvas.translate(0f, -slideY);
                canvas.scale(scale, scale, pivotX, topY);
            }

            if (!isActiveLine) {
                boolean anyLineElev = hasNonZeroElev(lineElevY);
                Bitmap existingMask = !anyLineElev
                        ? ((allowRasterizeMask && line.ensureStaticMask()) ? line.staticMask : line.getExistingStaticMask())
                        : null;
                if (existingMask != null) {
                    STATIC_MASK_PAINT.setColor(inactiveColor);
                    canvas.drawBitmap(existingMask, line.staticMaskOriginX + pad, topY - 2f, STATIC_MASK_PAINT);
                } else {
                    for (int i = 0; i < n; i++) {
                        float drawTopY = topY + ((lineElevY != null && i < lineElevY.length) ? lineElevY[i] : 0f);
                        drawLayoutWithPaint(canvas, cs.get(i).subLayout, line.drawX[i] + pad, drawTopY, inactiveColor, null);
                    }
                }
                if (scalingReveal) {
                    canvas.restore();
                }
                continue;
            }

            boolean isLineInPast = activeLineIdx != -1 && li < activeLineIdx;
            boolean isLineInFuture = activeLineIdx != -1 && li > activeLineIdx;

            float curRomGX;
            float brushShift;
            if (independentTimeSweep && !line.isRtl) {
                computeIndependentRomanWipe(
                        line.segStartX, line.segEndX, line.segStartMs, line.segEndMs,
                        line.lastRomEnd, currentPosMs, subGradientWidth,
                        forcePast || isLineInPast, indOut);
                curRomGX = indOut[0];
                brushShift = indOut[1];
            } else {
                if (isLineInPast) {
                    curRomGX = Float.MAX_VALUE;
                } else if (isLineInFuture || activeSylIdx == -1) {
                    curRomGX = -subGradientWidth * 2f;
                } else if (line.isRtl) {
                    int activeClusterIdx = -1;
                    for (int i = 0; i < n; i++) {
                        if (cs.get(i).wordIndex == activeSylIdx) {
                            activeClusterIdx = i;
                            break;
                        }
                    }
                    if (activeClusterIdx >= 0) {
                        float prog = Math.max(0f, Math.min(1f, activeSylProgress));
                        curRomGX = line.localStart[activeClusterIdx] + prog * line.romWidth[activeClusterIdx];
                    } else {
                        int lastWordIdx = cs.get(n - 1).wordIndex;
                        int firstWordIdx = cs.get(0).wordIndex;
                        if (activeSylIdx > lastWordIdx) {
                            curRomGX = line.lastRomEnd + subGradientWidth * 2f;
                        } else if (activeSylIdx < firstWordIdx) {
                            curRomGX = -subGradientWidth * 2f;
                        } else {
                            float foundEnd = 0f;
                            for (int i = 0; i < n; i++) {
                                if (cs.get(i).wordIndex < activeSylIdx) {
                                    foundEnd = line.localEnd[i];
                                }
                            }
                            curRomGX = foundEnd;
                        }
                    }
                } else if (curGX <= line.firstMainStart) {
                    curRomGX = curGX - line.firstMainStart;
                } else if (curGX >= line.lastMainEnd) {
                    curRomGX = line.lastRomEnd + (curGX - line.lastMainEnd);
                } else {
                    float computedX = 0f;
                    boolean found = false;
                    for (int i = 0; i < n; i++) {
                        float sStart = line.mainStart[i];
                        float sEnd = line.mainEnd[i];
                        if (curGX >= sStart && curGX <= sEnd) {
                            float prog = (curGX - sStart) / Math.max(1f, sEnd - sStart);
                            computedX = line.localStart[i] + prog * line.romWidth[i];
                            found = true;
                            break;
                        }
                        if (i < n - 1) {
                            float nextStart = line.mainStart[i + 1];
                            if (curGX >= sEnd && curGX <= nextStart) {
                                float gapProg = (curGX - sEnd) / Math.max(1f, nextStart - sEnd);
                                float romEnd = line.localEnd[i];
                                computedX = romEnd + gapProg * (line.localStart[i + 1] - romEnd);
                                found = true;
                                break;
                            }
                        }
                    }
                    curRomGX = found ? computedX : (curGX - line.firstMainStart);
                }

                boolean activeWordInLine = activeSylIdx >= 0
                        && activeSylIdx < line.hasWordIndex.length
                        && line.hasWordIndex[activeSylIdx];
                brushShift = ((li == activeLineIdx || activeWordInLine) && isActiveSylLastOfLine)
                        ? (activeSylProgress * halfGradientWidth)
                        : 0f;
            }
            float localX = curRomGX + brushShift;
            final boolean smoothSweep = Settings.LYRICS_WORD_SMOOTH_SWEEP.get();

            for (int i = 0; i < n; i++) {
                RomanizedWordCluster rwc = cs.get(i);
                float topLeftX = line.drawX[i] + pad;
                float drawTopY = topY + ((lineElevY != null && i < lineElevY.length) ? lineElevY[i] : 0f);
                float localStart = line.localStart[i];
                float localEnd = line.localEnd[i];

                if (!smoothSweep) {
                    if (curRomGX >= localEnd) {
                        drawLayoutWithPaint(canvas, rwc.subLayout, topLeftX, drawTopY, pastColor, null);
                    } else if (curRomGX <= localStart) {
                        drawLayoutWithPaint(canvas, rwc.subLayout, topLeftX, drawTopY, unlitColor, null);
                    } else {
                        float clipW = Math.max(0f, Math.min(rwc.romanWidth, curRomGX - localStart));
                        drawLayoutWithPaint(canvas, rwc.subLayout, topLeftX, drawTopY, unlitColor, null);
                        int h = rwc.subLayout.getHeight();
                        canvas.save();
                        canvas.clipRect(topLeftX, drawTopY, topLeftX + clipW, drawTopY + h);
                        drawLayoutWithPaint(canvas, rwc.subLayout, topLeftX, drawTopY, litColor, null);
                        canvas.restore();
                    }
                    continue;
                }

                if (localX >= localEnd + halfGradientWidth) {
                    drawLayoutWithPaint(canvas, rwc.subLayout, topLeftX, drawTopY, pastColor, null);
                } else if (localX <= localStart - halfGradientWidth) {
                    drawLayoutWithPaint(canvas, rwc.subLayout, topLeftX, drawTopY, unlitColor, null);
                } else {
                    float relWiperX = localX - localStart;
                    float bandStartRel;
                    float bandEndRel;
                    if (localX < halfGradientWidth) {
                        float growProgress = Math.max(0f, Math.min(1f, localX / halfGradientWidth));
                        float growWidth = Math.max(0.1f, subGradientWidth * growProgress);
                        bandStartRel = -localStart;
                        bandEndRel = growWidth - localStart;
                    } else {
                        bandStartRel = relWiperX - halfGradientWidth;
                        bandEndRel = relWiperX + halfGradientWidth;
                    }
                    if (Math.abs(bandEndRel - bandStartRel) < 0.01f) {
                        bandEndRel = bandStartRel + 0.01f;
                    }
                    LinearGradient shader = new LinearGradient(
                            bandStartRel, 0f, bandEndRel, 0f,
                            new int[]{litColor, litColor, unlitColor, unlitColor},
                            GRADIENT_STOPS,
                            Shader.TileMode.CLAMP);
                    drawLayoutWithPaint(canvas, rwc.subLayout, topLeftX, drawTopY, Color.WHITE, shader);
                }
            }
            if (scalingReveal) {
                canvas.restore();
            }
        }
    }

    private static boolean hasNonZeroElev(@Nullable float[] arr) {
        if (arr == null) return false;
        for (float v : arr) {
            if (Math.abs(v) > 0.001f) return true;
        }
        return false;
    }
}