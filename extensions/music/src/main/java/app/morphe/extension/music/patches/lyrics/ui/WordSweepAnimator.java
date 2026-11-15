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
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.os.SystemClock;
import android.text.Layout;
import android.text.TextPaint;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.music.patches.lyrics.ui.LyricsGlyphMaskCache.ClusterMaskCache;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ActiveSylInfo;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.AlignedSyllable;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.CharPositionMaps;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ClusterMeta;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ClusterStatics;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ColorAlphaCache;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.FormattedMainText;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ReconstructedWord;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.RomanStatics;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.RomanizedWordCluster;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.SyllableBounds;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.TintFilterCache;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.VisualCluster;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WipeResult;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WordTimestamp;
import app.morphe.extension.music.settings.Settings;

public final class WordSweepAnimator {

    public static final int EMPHASIS_SAMPLE_FPS = 60;
    public static final int EMPHASIS_TABLE_FRAMES = 61;

    private static final Interpolator FAST_OUT_SLOW_IN = new PathInterpolator(0.4f, 0f, 0.2f, 1f);
    private static final Interpolator LINEAR_OUT_SLOW_IN = new PathInterpolator(0f, 0f, 0.2f, 1f);

    private static final String EMPHASIS_TRAILING_PUNCT = "!?.,;:~…‥。、！？";

    public static final class EmphasisSample {
        public float lift = 0f;
        public float pulse = 0f;
        public float glow = 0f;
    }

    public static float emphasisSpringProgress(float tSec, float responseSec) {
        if (tSec <= 0f) return 0f;
        float i = (2f * (float) Math.PI * tSec) / Math.max(0.001f, responseSec);
        return 1f - (1f + i) * (float) Math.exp(-i);
    }

    public static float emphasisResponseSec(long wordStartMs, long wordEndMs) {
        return Math.min(3f, Math.max(0.001f, (wordEndMs - wordStartMs) / 1000f));
    }

    public static float emphasisSpanMs(long wordStartMs, long wordEndMs, float holdMs) {
        return Math.max(1f, holdMs + emphasisResponseSec(wordStartMs, wordEndMs) * 2000f);
    }

    public static float emphasisGlowOpacity(float fraction) {
        if (fraction <= 0f || fraction >= 1f) return 0f;
        if (fraction < 0.15f) {
            return 0.8f * (fraction / 0.15f);
        } else if (fraction < 0.30f) {
            return 0.8f + 0.2f * ((fraction - 0.15f) / 0.15f);
        } else if (fraction < 0.60f) {
            return 1f - 0.7f * ((fraction - 0.30f) / 0.30f);
        } else {
            return 0.3f * ((1f - fraction) / 0.40f);
        }
    }

    public static void emphasisFrame(
            long wordStartMs,
            long wordEndMs,
            float holdMs,
            int frame,
            @NonNull EmphasisSample out) {
        if (frame >= 60) {
            out.lift = 1f;
            out.pulse = 0f;
            return;
        }
        float response = emphasisResponseSec(wordStartMs, wordEndMs);
        float hold = holdMs / 1000f;
        float spanSec = emphasisSpanMs(wordStartMs, wordEndMs, holdMs) / 1000f;
        float t = spanSec * (frame / 60f);
        float sp = emphasisSpringProgress(t, response);
        out.lift = sp;
        out.pulse = sp * (1f - emphasisSpringProgress(t - hold, response));
    }

    @Nullable
    private float[] ensureEmphasisTable(int idx, @NonNull VisualCluster vc) {
        if (!vc.isHeavy) {
            return null;
        }
        float[] table = emphasisTables[idx];
        if (table == null) {
            table = buildEmphasisTable(vc);
            emphasisTables[idx] = table;
        }
        return table;
    }

    /** Whether clearing exiting syllable romanization/ruby requires remeasuring main line layout. */
    boolean needsExitLayoutRebuild() {
        return hasSyllableRomanization
                || hasRuby
                || lazyExitingSyllableRomanization
                || lazyExitingRubyActive
                || hasRomanShift
                || extraRomanHeightPx > 0
                || extraRubyHeightPx > 0;
    }

    @NonNull
    public static float[] buildEmphasisTable(@NonNull VisualCluster vc) {
        EmphasisSample tmp = new EmphasisSample();
        float[] table = new float[EMPHASIS_TABLE_FRAMES * 2];
        for (int f = 0; f < EMPHASIS_TABLE_FRAMES; f++) {
            emphasisFrame(vc.wordStartMs, vc.wordEndMs, vc.charTranslateYPeak, f, tmp);
            table[f * 2] = tmp.lift;
            table[f * 2 + 1] = tmp.pulse;
        }
        return table;
    }

    @NonNull
    public static float[] buildEmphasisTable(long wordDurationMs, long holdMs) {
        EmphasisSample tmp = new EmphasisSample();
        float[] table = new float[EMPHASIS_TABLE_FRAMES * 2];
        for (int f = 0; f < EMPHASIS_TABLE_FRAMES; f++) {
            emphasisFrame(0L, wordDurationMs, (float) holdMs, f, tmp);
            table[f * 2] = tmp.lift;
            table[f * 2 + 1] = tmp.pulse;
        }
        return table;
    }

    public static void sampleEmphasisCached(
            @NonNull float[] table,
            float fraction,
            @NonNull EmphasisSample out) {
        out.glow = emphasisGlowOpacity(fraction);
        if (fraction <= 0f) {
            out.lift = 0f;
            out.pulse = 0f;
            return;
        }
        if (fraction >= 1f) {
            out.lift = 1f;
            out.pulse = 0f;
            return;
        }
        float pos = fraction * 60f;
        int i0 = Math.max(0, Math.min(59, (int) pos));
        float f = pos - i0;
        int a = i0 * 2;
        int b = a + 2;
        out.lift = table[a] + (table[b] - table[a]) * f;
        out.pulse = table[a + 1] + (table[b + 1] - table[a + 1]) * f;
    }

    public static void sampleEmphasisCached(
            @NonNull float[] table,
            float tableDurationMs,
            float timeMs,
            @NonNull float[] out) {
        if (timeMs <= 0f || tableDurationMs <= 0f) {
            out[0] = 0f;
            out[1] = 0f;
            return;
        }
        if (timeMs >= tableDurationMs) {
            out[0] = 1f;
            out[1] = 0f;
            return;
        }
        EmphasisSample sample = new EmphasisSample();
        sampleEmphasisCached(table, timeMs / tableDurationMs, sample);
        out[0] = sample.lift;
        out[1] = sample.pulse;
    }

    public static boolean isEmphasisAttached(char ch) {
        if (EMPHASIS_TRAILING_PUNCT.indexOf(ch) >= 0) return true;
        int code = (int) ch;
        return (code >= 0x0300 && code <= 0x036F)
                || (code >= 0x3099 && code <= 0x309A)
                || code == 0x200D
                || code == 0xFE0F
                || code == 0xFE0E;
    }

    public final String mainText;
    public final String formattedMainText;
    public final List<WordTimestamp> shapedWords;
    @Nullable
    public final List<WordTimestamp> shapedRomanizedWords;
    public final boolean isBackground;
    public boolean showSyllableRomanization;
    public boolean hasSyllableRomanization;
    public final float fontSizePx;
    public final float gradientWidth;
    public final float elevationAmount;
    public final float extraPaddingPx;
    public final float emphasisLiftEm = 0.05f;
    public final float emphasisRiseEm = 0.035f;
    public final float emphasisShiftEm = 0.05f;
    public final float emphasisScaleGain = 0.1f;
    public float lineExtraHeight;
    public final float glowBlurRadius;
    public final float shadowBlurRadius;
    public final Layout.Alignment alignment;

    public final Layout textLayoutResult;
    public final int lineCount;
    public final float[] lineLefts;
    public final float[] lineRights;
    public final float[] lineStarts;
    public final float[] lineWidths;
    public final float totalWidth;
    public final boolean[] lineIsRtl;
    public final float[] lineBaselines;
    public final float[] lineBottoms;

    public final List<AlignedSyllable> alignedSyllables;
    public List<RomanizedWordCluster> romanClusters;
    public List<RomanizedWordCluster> rubyClusters;
    public boolean hasRuby;
    public float firstLineRubyOffset;
    public int extraRomanHeightPx;
    public int extraRubyHeightPx;
    public List<ReconstructedWord> reconstructedWords;
    public float[] charXMap;
    public float[] romanStartShift;
    public SyllableBounds sylBounds;
    public List<VisualCluster> clusters;
    public ClusterStatics statics;
    public List<RomanizedWordCluster>[] romanLines;
    public RomanStatics romanStatics;
    public List<RomanizedWordCluster>[] rubyLines;
    public RomanStatics rubyStatics;
    private float romaReveal = 1f;
    private float romaScaleProgress = 1f;

    public final long lineStartTime;
    public final long lineEndTime;
    public final long effectiveEndTimeMs;
    public float lineEndX;
    public final int lastSylIdx;
    public final long lineStartMs;
    public final int measuredWidth;
    public int measuredHeight;

    public final boolean isArabicOrRtl;
    public boolean[] clusterMotionOff;
    public float[][] emphasisTables;
    public float[] clusterSpanMs;

    public int[] sylGroupPos;
    public int[] sylGroupSize;
    public boolean[] sylGroupIsLast;
    public long[] sylGroupEndMs;
    public boolean[] sylIsNextOfDash;
    public int[] sylTextLen;
    public int[] clusterGroupPos;
    public int[] clusterGroupSize;
    public float[] clusterGroupSizeF;
    public float[] clusterGroupFrac;
    public float[] clusterWordDurMs;
    public boolean[] clusterInWordGroup;
    public boolean[] clusterIsNextOfDash;
    public int[] clusterSylGroupPos;
    public float[] clusterBaseImpact;

    private float[] clusterScaleX;
    private float[] clusterScaleY;
    private float[] clusterGlowAlphas;
    private boolean[] clusterIsGlowing;
    private float[] clusterDrawX;
    private float[] clusterDrawY;
    private float[] clusterLastDrawY;
    private boolean[] clusterIsYMoving;
    private float[] clusterFadeProgress;
    private float[] sylSungFactorValues;
    private float[] lineTotalPushScratch;
    private float[] lineCurrentPushScratch;

    private boolean[] sylElevTriggered;
    private float[] sylElevAnimFrom;
    private float[] sylElevAnimTarget;
    private float[] sylElevAnimDurationMs;
    private long[] sylElevAnimStartMs;
    private float[] sylElevCurrent;

    private boolean[] heavyRelTriggered;
    private int[] heavyRelMode;
    private float[] heavyRelAnimFrom;
    private float[] heavyRelAnimTarget;
    private float[] heavyRelAnimDurationMs;
    private float[] heavyRelAnimDelayMs;
    private long[] heavyRelAnimStartMs;
    private float[] clusterGrowValues;
    private float[] clusterReleaseValues;

    private boolean wasActiveLine = false;
    private boolean isFirstRun = true;
    private long deactivatedPosMs = 0L;
    private long deactivatedUptimeMs = -1L;

    private float lastActiveGX = 0f;
    private long lastActivePosMs = Long.MIN_VALUE;
    private long finishStartUptimeMs = -1L;
    private float finishStartGX = 0f;
    private float finishAlpha = 0f;

    private float curColorState = 0f;
    private float curColorVel = 0f;
    private float curSimpleState = 0f;
    private float curSimpleVel = 0f;
    private long lastDrawUptimeMs = -1L;
    private long lastRawUptimeMs = -1L;
    private boolean lastWasPlaying = false;
    private long animClockMs = 10_000L;
    private boolean prewarmMasks = false;
    private boolean stillAnimating = false;

    private final EmphasisSample sampleScratch = new EmphasisSample();
    private final WipeResult wipeScratch = new WipeResult();
    private final ColorAlphaCache litColorCache = new ColorAlphaCache();
    private final ColorAlphaCache unlitColorCache = new ColorAlphaCache();
    private final ColorAlphaCache transparentAccentColorCache = new ColorAlphaCache();
    private final TintFilterCache fadeTintCache = new TintFilterCache();
    public boolean glowPossible;
    public ClusterMaskCache maskCacheStore;

    /** Cached invariant text layouts harvested from an evicted animator across region toggles. */
    public static final class ReuseHint {
        final int widthPx;
        final float fontSizePx;
        final int accentColor;
        final boolean hasJoining;
        final Layout.Alignment alignment;
        @Nullable
        final Layout unformattedLayout;
        @Nullable
        final Map<String, Layout> subLayouts;

        ReuseHint(
                int widthPx,
                float fontSizePx,
                int accentColor,
                boolean hasJoining,
                @NonNull Layout.Alignment alignment,
                @Nullable Layout unformattedLayout,
                @Nullable Map<String, Layout> subLayouts) {
            this.widthPx = widthPx;
            this.fontSizePx = fontSizePx;
            this.accentColor = accentColor;
            this.hasJoining = hasJoining;
            this.alignment = alignment;
            this.unformattedLayout = unformattedLayout;
            this.subLayouts = subLayouts;
        }
    }

    @Nullable
    private TextPaint lazyBasePaint;
    @Nullable
    private final String lazyRomanizedText;
    private final int lazyExpressiveAccent;
    private final boolean lazyHasJoining;
    private boolean lazyExitingSyllableRomanization;
    private boolean lazyExitingRubyActive;
    @Nullable
    private Layout unromanLayoutCache;
    @Nullable
    private ReuseHint pendingReuseHint;
    private boolean renderStateBuilt = false;
    private boolean unromanPositionsBuilt = false;
    private boolean hasRomanShift = false;
    @Nullable
    private float[] unromanBaseX;
    @Nullable
    private float[] unromanBaseY;
    private boolean everActivated = false;
    @Nullable
    private Bitmap staticMainMask;
    private float staticMainMaskOriginX;
    private float staticMainMaskOriginY;
    private boolean staticMainMaskAttempted = false;
    private final Paint staticMainMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    public WordSweepAnimator(
            @NonNull String mainText,
            @NonNull List<WordTimestamp> rawWords,
            @Nullable List<WordTimestamp> rawRomanizedWords,
            @Nullable String romanizedText,
            boolean showSyllableRomanization,
            boolean isBackground,
            @Nullable Long adjustedEndTimeMs,
            @NonNull TextPaint basePaint,
            @NonNull Layout.Alignment alignment,
            int widthPx,
            int expressiveAccent,
            boolean prewarmMasks) {
        this(mainText, rawWords, rawRomanizedWords, romanizedText,
                showSyllableRomanization, false, isBackground, adjustedEndTimeMs,
                basePaint, alignment, widthPx, expressiveAccent, prewarmMasks);
    }

    public WordSweepAnimator(
            @NonNull String mainText,
            @NonNull List<WordTimestamp> rawWords,
            @Nullable List<WordTimestamp> rawRomanizedWords,
            @Nullable String romanizedText,
            boolean showSyllableRomanization,
            boolean rubyActive,
            boolean isBackground,
            @Nullable Long adjustedEndTimeMs,
            @NonNull TextPaint basePaint,
            @NonNull Layout.Alignment alignment,
            int widthPx,
            int expressiveAccent,
            boolean prewarmMasks) {
        this(mainText, rawWords, rawRomanizedWords, romanizedText,
                showSyllableRomanization, rubyActive, false, false, isBackground,
                adjustedEndTimeMs, basePaint, alignment, widthPx, expressiveAccent, prewarmMasks);
    }

    public WordSweepAnimator(
            @NonNull String mainText,
            @NonNull List<WordTimestamp> rawWords,
            @Nullable List<WordTimestamp> rawRomanizedWords,
            @Nullable String romanizedText,
            boolean showSyllableRomanization,
            boolean rubyActive,
            boolean exitingSyllableRomanization,
            boolean exitingRubyActive,
            boolean isBackground,
            @Nullable Long adjustedEndTimeMs,
            @NonNull TextPaint basePaint,
            @NonNull Layout.Alignment alignment,
            int widthPx,
            int expressiveAccent,
            boolean prewarmMasks) {
        this(mainText, rawWords, rawRomanizedWords, romanizedText,
                showSyllableRomanization, rubyActive, exitingSyllableRomanization, exitingRubyActive,
                isBackground, adjustedEndTimeMs, basePaint, alignment, widthPx, expressiveAccent,
                prewarmMasks, null);
    }

    public WordSweepAnimator(
            @NonNull String mainText,
            @NonNull List<WordTimestamp> rawWords,
            @Nullable List<WordTimestamp> rawRomanizedWords,
            @Nullable String romanizedText,
            boolean showSyllableRomanization,
            boolean rubyActive,
            boolean exitingSyllableRomanization,
            boolean exitingRubyActive,
            boolean isBackground,
            @Nullable Long adjustedEndTimeMs,
            @NonNull TextPaint basePaint,
            @NonNull Layout.Alignment alignment,
            int widthPx,
            int expressiveAccent,
            boolean prewarmMasks,
            @Nullable ReuseHint reuseHint) {
        this.mainText = mainText;
        this.isBackground = isBackground;
        this.alignment = alignment;
        this.fontSizePx = basePaint.getTextSize();

        this.gradientWidth = fontSizePx * 1.0f;
        this.extraPaddingPx = fontSizePx * 0.4f;
        this.elevationAmount = fontSizePx * 0.05f;
        this.glowBlurRadius = fontSizePx * 0.20f;
        this.shadowBlurRadius = fontSizePx * 0.15f;

        List<int[]> ranges = WordAnimationUtils.shapingWordRanges(rawWords);
        this.shapedWords = WordAnimationUtils.mergeShapingWords(rawWords, ranges);
        if (rawRomanizedWords != null) {
            this.shapedRomanizedWords = (rawRomanizedWords.size() == rawWords.size())
                    ? WordAnimationUtils.mergeShapingWords(rawRomanizedWords, ranges)
                    : rawRomanizedWords;
        } else {
            this.shapedRomanizedWords = null;
        }
        this.showSyllableRomanization = showSyllableRomanization;

        FormattedMainText fmt = WordAnimationUtils.formatMainTextWithResult(
                mainText, shapedWords, shapedRomanizedWords, romanizedText,
                showSyllableRomanization, rubyActive, basePaint, widthPx);
        this.formattedMainText = fmt.text;

        boolean isMainRtl = LyricsSpanBuilder.isRtlText(formattedMainText);
        if (formattedMainText.equals(mainText) && reuseHint != null
                && reuseHint.unformattedLayout != null
                && reuseHint.widthPx == widthPx
                && Math.abs(reuseHint.fontSizePx - fontSizePx) < 0.5f
                && reuseHint.alignment == alignment
                && reuseHint.unformattedLayout.getText().toString().equals(mainText)) {
            this.textLayoutResult = reuseHint.unformattedLayout;
        } else {
            this.textLayoutResult = WordAnimationUtils.createStaticLayout(
                    formattedMainText, basePaint, widthPx, alignment, isMainRtl);
        }

        this.lineCount = textLayoutResult.getLineCount();
        this.lineLefts = new float[lineCount];
        this.lineRights = new float[lineCount];
        this.lineStarts = new float[lineCount];
        this.lineWidths = new float[lineCount];
        this.lineIsRtl = new boolean[lineCount];
        this.lineBaselines = new float[lineCount];
        this.lineBottoms = new float[lineCount];

        float cum = 0f;
        for (int i = 0; i < lineCount; i++) {
            lineLefts[i] = textLayoutResult.getLineLeft(i);
            lineRights[i] = textLayoutResult.getLineRight(i);
            float lw = Math.max(1f, lineRights[i] - lineLefts[i]);
            lineStarts[i] = cum;
            lineWidths[i] = lw;
            cum += lw;
            lineIsRtl[i] = textLayoutResult.getParagraphDirection(i) == Layout.DIR_RIGHT_TO_LEFT;
            lineBaselines[i] = textLayoutResult.getLineBaseline(i);
            lineBottoms[i] = textLayoutResult.getLineBottom(i);
        }
        this.totalWidth = cum;

        this.alignedSyllables = (fmt.alignedIfUnchanged != null && formattedMainText.equals(mainText))
                ? fmt.alignedIfUnchanged
                : WordAnimationUtils.alignSyllablesForFormatting(shapedWords, formattedMainText);
        this.hasSyllableRomanization = showSyllableRomanization
                && WordAnimationUtils.hasAnyRomanizedSyllables(
                        alignedSyllables, shapedRomanizedWords, romanizedText, formattedMainText);
        this.hasRuby = rubyActive
                && WordAnimationUtils.hasAnyRubySyllables(true, alignedSyllables, formattedMainText);

        this.lineStartTime = shapedWords.isEmpty() ? 0L : (long) (shapedWords.get(0).startTime * 1000.0);
        this.lineEndTime = shapedWords.isEmpty() ? 0L : (long) (shapedWords.get(shapedWords.size() - 1).endTime * 1000.0);
        this.effectiveEndTimeMs = (adjustedEndTimeMs != null && adjustedEndTimeMs != Long.MAX_VALUE)
                ? adjustedEndTimeMs
                : lineEndTime;

        this.lastSylIdx = alignedSyllables.size() - 1;
        this.lineStartMs = alignedSyllables.isEmpty()
                ? Long.MAX_VALUE
                : (long) (alignedSyllables.get(0).syllable.startTime * 1000.0);

        this.isArabicOrRtl = LyricsSpanBuilder.isRtlText(mainText)
                || LyricsTextUtils.containsJoiningScript(mainText);
        this.prewarmMasks = prewarmMasks;

        this.lazyBasePaint = basePaint;
        this.lazyRomanizedText = romanizedText;
        this.lazyExpressiveAccent = expressiveAccent;
        this.lazyHasJoining = LyricsTextUtils.containsJoiningScript(formattedMainText);
        this.lazyExitingSyllableRomanization = exitingSyllableRomanization;
        this.lazyExitingRubyActive = exitingRubyActive;
        this.measuredWidth = widthPx;
        if (formattedMainText.equals(mainText)) {
            this.unromanLayoutCache = this.textLayoutResult;
        } else if (reuseHint != null
                && reuseHint.unformattedLayout != null
                && reuseHint.widthPx == widthPx
                && Math.abs(reuseHint.fontSizePx - fontSizePx) < 0.5f
                && reuseHint.alignment == alignment
                && reuseHint.unformattedLayout.getText().toString().equals(mainText)) {
            this.unromanLayoutCache = reuseHint.unformattedLayout;
        }
        if (reuseHint != null
                && reuseHint.accentColor == expressiveAccent
                && reuseHint.hasJoining == lazyHasJoining
                && Math.abs(reuseHint.fontSizePx - fontSizePx) < 0.5f) {
            this.pendingReuseHint = reuseHint;
        }
        recomputeVerticalMetrics();
    }

    private void recomputeVerticalMetrics() {
        float lineRomanExtra = hasSyllableRomanization ? (fontSizePx * 0.80f) : 0f;
        float lineRubyExtra = hasRuby ? (fontSizePx * 0.70f) : 0f;
        this.firstLineRubyOffset = lineRubyExtra;
        this.lineExtraHeight = lineRomanExtra + (lineCount > 1 ? lineRubyExtra : 0f);
        int bottomMargin = hasSyllableRomanization ? (int) (fontSizePx * 0.10f) : 0;
        this.extraRomanHeightPx = Math.round(hasSyllableRomanization ? (lineCount * (fontSizePx * 0.80f) + bottomMargin) : 0f);
        this.extraRubyHeightPx = Math.round(hasRuby ? (lineCount * (fontSizePx * 0.70f)) : 0f);
        int totalExtraPx = (int) (lineCount * lineExtraHeight + firstLineRubyOffset);
        this.measuredHeight = textLayoutResult.getHeight() + totalExtraPx + bottomMargin;
    }

    /**
     * Harvests invariant sub-layouts so a follow-up animator built after toggling romanization
     * or ruby avoids re-shaping every character/word cluster and the base main layout.
     */
    @NonNull
    public ReuseHint extractReuseHint() {
        Map<String, Layout> map = null;
        if (renderStateBuilt && clusters != null && !clusters.isEmpty()) {
            map = new HashMap<>(clusters.size() * 2 + 1);
            int textLen = formattedMainText.length();
            for (int i = 0; i < clusters.size(); i++) {
                VisualCluster vc = clusters.get(i);
                if (vc.subLayout == null || vc.start < 0 || vc.end > textLen || vc.start >= vc.end) {
                    continue;
                }
                map.putIfAbsent(formattedMainText.substring(vc.start, vc.end), vc.subLayout);
            }
        } else if (pendingReuseHint != null && pendingReuseHint.subLayouts != null) {
            map = pendingReuseHint.subLayouts;
        }
        return new ReuseHint(
                measuredWidth, fontSizePx, lazyExpressiveAccent, lazyHasJoining,
                alignment, unromanLayoutCache, map);
    }

    /**
     * Transitions active vs. exiting region flags in-place when cluster vertical coordinates
     * remain invariant (all single-line rows without ruby, or any row before render state built).
     */
    public boolean canTransitionInPlace(boolean newShowSylRoma, boolean newRubyActive,
                                        boolean newExitSylRoma, boolean newExitRuby) {
        boolean newHasRuby = newRubyActive
                && WordAnimationUtils.hasAnyRubySyllables(true, alignedSyllables, formattedMainText);
        if (hasRuby || newHasRuby || lazyExitingRubyActive) {
            return false;
        }
        if (!formattedMainText.equals(mainText)) {
            return false;
        }
        if (!renderStateBuilt) {
            return lineCount <= 1;
        }
        if (lineCount <= 1) {
            return true;
        }
        boolean newHasSyl = newShowSylRoma
                && WordAnimationUtils.hasAnyRomanizedSyllables(
                        alignedSyllables, shapedRomanizedWords, lazyRomanizedText, formattedMainText);
        float newExtra = newHasSyl ? (fontSizePx * 0.80f) : 0f;
        return Math.abs(newExtra - lineExtraHeight) < 0.01f;
    }

    /** Updates active/exiting flags and recomputes extra height in-place when invariant. */
    public void updateTransitionModes(boolean newShowSylRoma, boolean newRubyActive,
                                      boolean newExitSylRoma, boolean newExitRuby) {
        this.showSyllableRomanization = newShowSylRoma;
        this.hasSyllableRomanization = newShowSylRoma
                && WordAnimationUtils.hasAnyRomanizedSyllables(
                        alignedSyllables, shapedRomanizedWords, lazyRomanizedText, formattedMainText);
        this.hasRuby = newRubyActive
                && WordAnimationUtils.hasAnyRubySyllables(true, alignedSyllables, formattedMainText);
        this.lazyExitingSyllableRomanization = newExitSylRoma;
        this.lazyExitingRubyActive = newExitRuby;
        recomputeVerticalMetrics();
    }

    /**
     * Lazily builds per-cluster layouts, romanization mappings, emphasis tables, and mask/glow caches
     * on first draw or hit-test so measuring offscreen lines does not allocate StaticLayouts upfront.
     */
    public void ensureRenderState() {
        if (renderStateBuilt) {
            return;
        }
        renderStateBuilt = true;
        TextPaint basePaint = lazyBasePaint != null ? lazyBasePaint : new TextPaint();
        if (formattedMainText.equals(mainText)) {
            lazyBasePaint = null;
        }

        this.romanClusters = WordAnimationUtils.buildRomanClusters(
                showSyllableRomanization || lazyExitingSyllableRomanization,
                alignedSyllables, shapedRomanizedWords, lazyRomanizedText,
                formattedMainText, textLayoutResult, basePaint, lazyExpressiveAccent);
        this.rubyClusters = WordAnimationUtils.buildRubyClusters(
                hasRuby || lazyExitingRubyActive,
                alignedSyllables, formattedMainText, textLayoutResult, basePaint, lazyExpressiveAccent);
        this.reconstructedWords = WordAnimationUtils.buildReconstructedWords(alignedSyllables, formattedMainText);

        CharPositionMaps posMaps = WordAnimationUtils.buildCharPositionMaps(
                formattedMainText, textLayoutResult, lineCount, lineIsRtl, lineStarts,
                alignedSyllables, romanClusters, rubyClusters, alignment, fontSizePx);
        this.charXMap = posMaps.charXMap;
        this.romanStartShift = posMaps.romanStartShift;
        this.sylBounds = WordAnimationUtils.buildSyllableBounds(
                alignedSyllables, charXMap, textLayoutResult, formattedMainText);

        int sylCount = alignedSyllables.size();
        this.sylGroupPos = new int[sylCount];
        this.sylGroupSize = new int[sylCount];
        Arrays.fill(this.sylGroupSize, 1);
        this.sylGroupIsLast = new boolean[sylCount];
        this.sylGroupEndMs = new long[sylCount];
        int sylBase = 0;
        for (int w = 0; w < reconstructedWords.size(); w++) {
            ReconstructedWord word = reconstructedWords.get(w);
            int sz = word.syllables.size();
            if (sz > 1) {
                for (int pos = 0; pos < sz; pos++) {
                    int gi = sylBase + pos;
                    if (gi < sylCount) {
                        sylGroupPos[gi] = pos;
                        sylGroupSize[gi] = sz;
                        sylGroupIsLast[gi] = (pos == sz - 1);
                        sylGroupEndMs[gi] = word.wordEndTimeMs;
                    }
                }
            }
            sylBase += sz;
        }

        this.sylIsNextOfDash = new boolean[sylCount];
        this.sylTextLen = new int[sylCount];
        for (int i = 0; i < sylCount; i++) {
            AlignedSyllable cur = alignedSyllables.get(i);
            sylTextLen[i] = Math.max(1, cur.syllable.text.length());
            if (i > 0) {
                AlignedSyllable prev = alignedSyllables.get(i - 1);
                boolean prevHasDash = false;
                for (int c = 0; c < prev.syllable.text.length(); c++) {
                    if (WordAnimationUtils.isDash(prev.syllable.text.charAt(c))) {
                        prevHasDash = true;
                        break;
                    }
                }
                boolean gapHasDash = false;
                if (cur.startOffset > prev.endOffset) {
                    int gs = Math.max(0, Math.min(formattedMainText.length(), prev.endOffset));
                    int ge = Math.max(gs, Math.min(formattedMainText.length(), cur.startOffset));
                    for (int c = gs; c < ge; c++) {
                        if (WordAnimationUtils.isDash(formattedMainText.charAt(c))) {
                            gapHasDash = true;
                            break;
                        }
                    }
                }
                sylIsNextOfDash[i] = prevHasDash || gapHasDash;
            }
        }

        this.lineEndX = alignedSyllables.isEmpty()
                ? 0f
                : globalX(alignedSyllables.get(alignedSyllables.size() - 1).endOffset);
        this.romanLines = WordAnimationUtils.groupRomanClustersByLine(romanClusters, lineCount);
        this.rubyLines = WordAnimationUtils.groupRomanClustersByLine(rubyClusters, lineCount);

        this.clusters = buildClusters(basePaint, lazyExpressiveAccent, lazyHasJoining);
        this.pendingReuseHint = null;
        this.statics = WordAnimationUtils.buildClusterStatics(
                clusters, romanStartShift, lineExtraHeight, firstLineRubyOffset, lineIsRtl,
                lineLefts, lineRights, lineStarts, lineWidths, fontSizePx);

        int n = clusters.size();
        this.clusterMotionOff = new boolean[n];
        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            clusterMotionOff[i] = isArabicOrRtl || vc.isRtl || vc.isJoiningScript;
        }

        this.emphasisTables = new float[n][];
        this.clusterSpanMs = new float[n];
        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            // Defer buildEmphasisTable(vc) until a heavy cluster actually draws with emphasis
            // so toggle rebuilds do not compute 61-sample exp tables for offscreen/inactive lines.
            clusterSpanMs[i] = vc.isHeavy
                    ? emphasisSpanMs(vc.wordStartMs, vc.wordEndMs, vc.charTranslateYPeak)
                    : 1f;
        }

        this.clusterGroupPos = new int[n];
        this.clusterGroupSize = new int[n];
        Arrays.fill(this.clusterGroupSize, 1);
        int ci = 0;
        while (ci < n) {
            VisualCluster vc = clusters.get(ci);
            if (!vc.isHeavy) {
                ci++;
                continue;
            }
            int cj = ci;
            while (cj + 1 < n
                    && clusters.get(cj + 1).isHeavy
                    && clusters.get(cj + 1).wordStartMs == vc.wordStartMs
                    && clusters.get(cj + 1).wordEndMs == vc.wordEndMs) {
                cj++;
            }
            int gSize = cj - ci + 1;
            for (int k = ci; k <= cj; k++) {
                clusterGroupPos[k] = k - ci;
                clusterGroupSize[k] = gSize;
            }
            ci = cj + 1;
        }

        this.clusterGroupSizeF = new float[n];
        this.clusterGroupFrac = new float[n];
        this.clusterWordDurMs = new float[n];
        this.clusterInWordGroup = new boolean[n];
        this.clusterIsNextOfDash = new boolean[n];
        this.clusterSylGroupPos = new int[n];
        this.clusterBaseImpact = new float[n];

        for (int i = 0; i < n; i++) {
            VisualCluster vc = clusters.get(i);
            clusterGroupSizeF[i] = Math.max(1, clusterGroupSize[i]);
            clusterGroupFrac[i] = clusterGroupPos[i] / clusterGroupSizeF[i];
            clusterWordDurMs[i] = Math.max(100L, vc.wordEndMs - vc.wordStartMs);
            int si = vc.syllableIndex;
            clusterInWordGroup[i] = (si != -1 && si < sylGroupSize.length && sylGroupSize[si] > 1);
            clusterIsNextOfDash[i] = (si != -1 && si < sylIsNextOfDash.length && sylIsNextOfDash[si]);
            clusterSylGroupPos[i] = (si != -1 && si < sylGroupPos.length) ? sylGroupPos[si] : 0;

            float wDurMs = (float) (vc.wordEndMs - vc.wordStartMs);
            int len = (si != -1 && si < sylTextLen.length) ? sylTextLen[si] : Math.max(1, vc.end - vc.start);
            float impactRatio = wDurMs / len;
            float p1 = Math.max(0f, Math.min(1f, (impactRatio - 100f) / 250f)) * 0.6f;
            float p2 = Math.max(0f, Math.min(1f, (wDurMs - 300f) / 1500f)) * 0.4f;
            clusterBaseImpact[i] = Math.max(0f, Math.min(1f, p1 + p2));
        }

        this.clusterScaleX = new float[n];
        this.clusterScaleY = new float[n];
        Arrays.fill(this.clusterScaleX, 1f);
        Arrays.fill(this.clusterScaleY, 1f);
        this.clusterGlowAlphas = new float[n];
        this.clusterIsGlowing = new boolean[n];
        this.clusterDrawX = new float[n];
        this.clusterDrawY = new float[n];
        this.clusterLastDrawY = new float[n];
        Arrays.fill(this.clusterLastDrawY, Float.NaN);
        this.clusterIsYMoving = new boolean[n];
        this.clusterFadeProgress = new float[n];
        this.sylSungFactorValues = new float[sylCount];
        this.lineTotalPushScratch = new float[lineCount];
        this.lineCurrentPushScratch = new float[lineCount];

        this.sylElevTriggered = new boolean[sylCount];
        this.sylElevAnimFrom = new float[sylCount];
        this.sylElevAnimTarget = new float[sylCount];
        this.sylElevAnimDurationMs = new float[sylCount];
        Arrays.fill(this.sylElevAnimDurationMs, 1000f);
        this.sylElevAnimStartMs = new long[sylCount];
        Arrays.fill(this.sylElevAnimStartMs, -1L);
        this.sylElevCurrent = new float[sylCount];

        this.heavyRelTriggered = new boolean[n];
        this.heavyRelMode = new int[n];
        this.heavyRelAnimFrom = new float[n];
        this.heavyRelAnimTarget = new float[n];
        this.heavyRelAnimDurationMs = new float[n];
        this.heavyRelAnimDelayMs = new float[n];
        this.heavyRelAnimStartMs = new long[n];
        Arrays.fill(this.heavyRelAnimStartMs, -1L);
        this.clusterGrowValues = new float[n];
        this.clusterReleaseValues = new float[n];

        boolean anyHeavy = false;
        for (int i = 0; i < n; i++) {
            if (clusters.get(i).isHeavy) {
                anyHeavy = true;
                break;
            }
        }
        this.glowPossible = anyHeavy;
        this.maskCacheStore = new ClusterMaskCache(n, extraPaddingPx);

        boolean isRight = (alignment == Layout.Alignment.ALIGN_OPPOSITE);
        if (LyricsSpanBuilder.isRtlText(formattedMainText)) {
            isRight = (alignment == Layout.Alignment.ALIGN_NORMAL);
        }
        this.romanStatics = WordAnimationUtils.buildRomanStaticsWithOffset(
                romanLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight, firstLineRubyOffset,
                totalWidth, extraPaddingPx, lineRights, lineIsRtl, isRight);
        this.rubyStatics = (hasRuby || (!rubyClusters.isEmpty() && lazyExitingRubyActive))
                ? WordAnimationUtils.buildRubyStatics(
                        rubyLines, charXMap, romanStartShift, lineBaselines, lineBottoms,
                        lineLefts, lineStarts, lineCount, fontSizePx, lineExtraHeight, firstLineRubyOffset,
                        totalWidth, extraPaddingPx, lineRights, lineIsRtl, isRight)
                : new RomanStatics(new WordAnimationUtils.RomanLineStatics[0], extraPaddingPx);
    }

    private float transReveal = 1f;

    public void setTransReveal(float reveal) {
        this.transReveal = Math.max(0f, Math.min(1f, reveal));
    }

    public void setRomaReveal(float reveal) {
        this.romaReveal = Math.max(0f, Math.min(1f, reveal));
    }

    public void setRomaScaleProgress(float scaleProgress) {
        this.romaScaleProgress = Math.max(0f, scaleProgress);
    }

    private float globalX(int offset) {
        if (offset < 0 || charXMap.length == 0) return 0f;
        if (offset >= charXMap.length) return totalWidth;
        return charXMap[Math.max(0, Math.min(charXMap.length - 1, offset))];
    }

    public boolean isStillAnimating() {
        return stillAnimating;
    }

    private static final long OFF_WINDOW_EVICT_DELAY_MS = 600L;

    public void setPrewarmMasks(boolean prewarmMasks) {
        this.prewarmMasks = prewarmMasks;
        if (!prewarmMasks && maskCacheStore != null
                && ((!wasActiveLine && !stillAnimating)
                || lastRawUptimeMs < 0L
                || (SystemClock.uptimeMillis() - lastRawUptimeMs) > OFF_WINDOW_EVICT_DELAY_MS)) {
            maskCacheStore.evictAndClear();
        }
    }

    public void evictMasks() {
        if (maskCacheStore != null) {
            maskCacheStore.evictAndClear();
        }
    }

    private void assignSharedElev(@NonNull List<ClusterMeta> metaList, @NonNull List<AlignedSyllable> syls) {
        for (int s = 0; s < syls.size(); s++) {
            AlignedSyllable syl = syls.get(s);
            float sx = globalX(syl.startOffset);
            float ex = globalX(syl.endOffset);
            float center = sx + (ex - sx) / 2f;
            List<ClusterMeta> range = metasInRange(metaList, syl.startOffset, syl.endOffset);
            for (int m = 0; m < range.size(); m++) {
                range.get(m).sharedElevationCenter = center;
            }
        }
    }

    @NonNull
    private List<VisualCluster> buildClusters(
            @NonNull TextPaint basePaint,
            int expressiveAccent,
            boolean hasJoining) {
        List<ClusterMeta> metaList = new ArrayList<>();
        int i = 0;
        int textLen = formattedMainText.length();
        while (i < textLen) {
            int start = i;
            int end = (i + 1 < textLen
                    && Character.isHighSurrogate(formattedMainText.charAt(i))
                    && Character.isLowSurrogate(formattedMainText.charAt(i + 1)))
                    ? (i + 2) : (i + 1);
            i = end;

            if (formattedMainText.charAt(start) == '\n') continue;

            int safeStart = Math.max(0, Math.min(Math.max(0, textLen - 1), start));
            int safeEnd = Math.min(end, Math.max(0, textLen - 1));
            int li = textLayoutResult.getLineForOffset(safeStart);
            int eli = textLayoutResult.getLineForOffset(safeEnd);
            float hStart = textLayoutResult.getPrimaryHorizontal(safeStart);
            float hEnd = (li == eli)
                    ? textLayoutResult.getPrimaryHorizontal(Math.min(end, textLen))
                    : textLayoutResult.getLineRight(li);
            float x = Math.min(hStart, hEnd);
            float w = Math.abs(hEnd - hStart);
            float y = textLayoutResult.getLineTop(li);

            ClusterMeta cm = new ClusterMeta(start, end, li, x, y, w);
            cm.isRtl = lineIsRtl[li];
            metaList.add(cm);
        }

        for (int wIdx = 0; wIdx < reconstructedWords.size(); wIdx++) {
            ReconstructedWord word = reconstructedWords.get(wIdx);
            if (word.wordLen <= 0 || word.syllables.isEmpty()) continue;

            String firstSylText = word.syllables.get(0).syllable.text;
            boolean isJoining = LyricsTextUtils.containsJoiningScript(firstSylText);

            StringBuilder wordTextSb = new StringBuilder();
            for (int s = 0; s < word.syllables.size(); s++) {
                wordTextSb.append(word.syllables.get(s).syllable.text);
            }
            String wordText = wordTextSb.toString();

            List<List<ClusterMeta>> charGroups = new ArrayList<>();
            if (!isBackground && !LyricsSpanBuilder.isRtlText(wordText) && !WordAnimationUtils.isNeedSplitOnly(wordText)) {
                List<ClusterMeta> wordMetas = metasInRange(metaList, word.startIndex, word.endIndex);
                for (int m = 0; m < wordMetas.size(); m++) {
                    ClusterMeta cm = wordMetas.get(m);
                    char ch = formattedMainText.charAt(cm.start);
                    if (Character.isWhitespace(ch)) continue;
                    if (!charGroups.isEmpty() && isEmphasisAttached(ch)) {
                        charGroups.get(charGroups.size() - 1).add(cm);
                    } else {
                        List<ClusterMeta> grp = new ArrayList<>();
                        grp.add(cm);
                        charGroups.add(grp);
                    }
                }
            }

            int numChars = charGroups.size();
            float wordDurationMs = (float) (word.wordEndTimeMs - word.wordStartTimeMs);
            long gateDurationMs = 0L;
            for (int s = 0; s < word.syllables.size(); s++) {
                AlignedSyllable syl = word.syllables.get(s);
                gateDurationMs += (long) ((syl.syllable.endTime - syl.syllable.startTime) * 1000.0);
            }

            assignSharedElev(metaList, word.syllables);

            if (numChars >= 1 && numChars <= 7 && gateDurationMs >= 1000L) {
                double e = Math.max(0.001, (double) wordDurationMs / 1000.0);
                double delaySec = Math.min((e / numChars) * 0.4, 0.4);
                float holdMs = (float) (2.0 * e / numChars * 1000.0);
                float rawAmp = (float) Math.max(0.0, Math.min(1.0, e - 1.0));
                float amplitude = 0.35f + 0.65f * rawAmp;
                float rawGlow = (float) Math.max(0.0, Math.min(1.0, (e - 1.0) / 0.5));
                float glowMax = 0.30f + 0.35f * rawGlow;
                float half = Math.max(1f, (numChars - 1) / 2f);

                for (int charIdx = 0; charIdx < charGroups.size(); charIdx++) {
                    List<ClusterMeta> group = charGroups.get(charIdx);
                    long delayMs = (long) ((charIdx + 1) * delaySec * 1000.0);
                    float normX = (charIdx - (numChars - 1) / 2f) / half;
                    for (int gm = 0; gm < group.size(); gm++) {
                        ClusterMeta cm = group.get(gm);
                        cm.isHeavy = true;
                        cm.isJoiningScript = isJoining;
                        cm.wordStartMs = word.wordStartTimeMs;
                        cm.wordEndMs = word.wordEndTimeMs;
                        cm.intensity = amplitude;
                        cm.charShadowIntensity = glowMax;
                        cm.charDelayMs = delayMs;
                        cm.charTranslateYPeak = holdMs;
                        cm.normX = normX;
                    }
                }
            }
        }

        TextPaint clusterRunPaint = WordAnimationUtils.createConfiguredRunPaint(basePaint, expressiveAccent, hasJoining);
        Map<String, Layout> reusedLayouts = (pendingReuseHint != null) ? pendingReuseHint.subLayouts : null;
        int[] sylPointer = new int[]{0};
        int lastSylOnLine = -1;
        int curClusterLine = -1;
        List<VisualCluster> list = new ArrayList<>();
        int idx = 0;
        while (idx < metaList.size()) {
            ClusterMeta m = metaList.get(idx);
            if (m.lineIdx != curClusterLine) {
                curClusterLine = m.lineIdx;
                lastSylOnLine = -1;
            }
            if (m.isHeavy && !m.isJoiningScript) {
                String runText = formattedMainText.substring(
                        Math.max(0, Math.min(textLen, m.start)),
                        Math.max(0, Math.min(textLen, m.end)));
                Layout subLayout = measureClusterSubLayout(runText, clusterRunPaint, hasJoining, reusedLayouts);
                float clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0);
                int sIdx = sylIndexFor(m, sylPointer);
                if (sIdx >= 0) lastSylOnLine = sIdx;
                int shiftIdx = sIdx >= 0 ? sIdx : (lastSylOnLine >= 0 ? lastSylOnLine : sylPointer[0]);
                list.add(new VisualCluster(
                        m.start, m.end, m.lineIdx, m.x, clusterY, m.width,
                        true, m.disableScale, false, m.isRtl,
                        m.wordStartMs, m.wordEndMs, m.intensity, m.normX,
                        m.sharedElevationCenter, subLayout,
                        m.charMaxScale, m.charShadowIntensity, m.charTranslateYPeak, m.charDelayMs,
                        sIdx, shiftIdx
                ));
                idx++;
            } else if (m.isHeavy && m.isJoiningScript) {
                int j = idx;
                while (j + 1 < metaList.size()
                        && metaList.get(j + 1).isHeavy
                        && metaList.get(j + 1).isJoiningScript
                        && metaList.get(j + 1).lineIdx == m.lineIdx
                        && metaList.get(j + 1).wordStartMs == m.wordStartMs
                        && metaList.get(j + 1).wordEndMs == m.wordEndMs) {
                    j++;
                }
                List<ClusterMeta> runMetas = metaList.subList(idx, j + 1);
                float runLeft = Float.MAX_VALUE;
                float runRight = -Float.MAX_VALUE;
                for (int r = 0; r < runMetas.size(); r++) {
                    ClusterMeta rm = runMetas.get(r);
                    if (rm.x < runLeft) runLeft = rm.x;
                    if (rm.x + rm.width > runRight) runRight = rm.x + rm.width;
                }
                ClusterMeta last = metaList.get(j);
                String runText = formattedMainText.substring(
                        Math.max(0, Math.min(textLen, m.start)),
                        Math.max(0, Math.min(textLen, last.end)));
                Layout subLayout = measureClusterSubLayout(runText, clusterRunPaint, hasJoining, reusedLayouts);
                float clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0);
                int sIdx = sylIndexFor(m, sylPointer);
                if (sIdx >= 0) lastSylOnLine = sIdx;
                int shiftIdx = sIdx >= 0 ? sIdx : (lastSylOnLine >= 0 ? lastSylOnLine : sylPointer[0]);
                list.add(new VisualCluster(
                        m.start, last.end, m.lineIdx, runLeft, clusterY, runRight - runLeft,
                        true, m.disableScale, true, m.isRtl,
                        m.wordStartMs, m.wordEndMs, m.intensity, 0f,
                        m.sharedElevationCenter, subLayout,
                        m.charMaxScale, m.charShadowIntensity, m.charTranslateYPeak, 0L,
                        sIdx, shiftIdx
                ));
                idx = j + 1;
            } else {
                int runSylIdx = sylIndexFor(m, sylPointer);
                if (runSylIdx >= 0) lastSylOnLine = runSylIdx;
                int runShiftIdx = runSylIdx >= 0 ? runSylIdx : (lastSylOnLine >= 0 ? lastSylOnLine : sylPointer[0]);
                int j = idx;
                while (j + 1 < metaList.size()
                        && !metaList.get(j + 1).isHeavy
                        && metaList.get(j + 1).lineIdx == m.lineIdx
                        && sylIndexFor(metaList.get(j + 1), sylPointer) == runSylIdx) {
                    j++;
                }
                List<ClusterMeta> runMetas = metaList.subList(idx, j + 1);
                float runLeft = Float.MAX_VALUE;
                float runRight = -Float.MAX_VALUE;
                for (int r = 0; r < runMetas.size(); r++) {
                    ClusterMeta rm = runMetas.get(r);
                    if (rm.x < runLeft) runLeft = rm.x;
                    if (rm.x + rm.width > runRight) runRight = rm.x + rm.width;
                }
                ClusterMeta last = metaList.get(j);
                String runText = formattedMainText.substring(
                        Math.max(0, Math.min(textLen, m.start)),
                        Math.max(0, Math.min(textLen, last.end)));
                Layout subLayout = measureClusterSubLayout(runText, clusterRunPaint, hasJoining, reusedLayouts);
                float clusterY = lineBaselines[m.lineIdx] - subLayout.getLineBaseline(0);
                list.add(new VisualCluster(
                        m.start, last.end, m.lineIdx, runLeft, clusterY, runRight - runLeft,
                        false, m.disableScale, false, m.isRtl,
                        0L, 0L, 0f, 0f,
                        m.sharedElevationCenter, subLayout,
                        1f, 0f, 0f, 0L,
                        runSylIdx, runShiftIdx
                ));
                idx = j + 1;
            }
        }
        return list;
    }

    @NonNull
    private static Layout measureClusterSubLayout(
            @NonNull String runText,
            @NonNull TextPaint clusterRunPaint,
            boolean hasJoining,
            @Nullable Map<String, Layout> reusedLayouts) {
        if (reusedLayouts != null) {
            Layout cached = reusedLayouts.get(runText);
            if (cached != null) {
                return cached;
            }
        }
        return WordAnimationUtils.measureRunWithConfiguredPaint(runText, clusterRunPaint, hasJoining);
    }

    private static int metaLowerBound(@NonNull List<ClusterMeta> metaList, int pos) {
        int lo = 0;
        int hi = metaList.size();
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (metaList.get(mid).end <= pos) {
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return lo;
    }

    @NonNull
    private static List<ClusterMeta> metasInRange(@NonNull List<ClusterMeta> metaList, int startIdx, int endIdx) {
        int s = metaLowerBound(metaList, startIdx);
        int e = metaLowerBound(metaList, endIdx);
        if (s > e) s = e;
        return metaList.subList(s, e);
    }

    private int sylIndexFor(@NonNull ClusterMeta m, @NonNull int[] sylPointer) {
        while (sylPointer[0] < alignedSyllables.size()
                && alignedSyllables.get(sylPointer[0]).endOffset <= m.start) {
            sylPointer[0]++;
        }
        if (sylPointer[0] >= alignedSyllables.size()) return -1;
        AlignedSyllable s = alignedSyllables.get(sylPointer[0]);
        return (m.start >= s.startOffset && m.end <= s.endOffset) ? sylPointer[0] : -1;
    }

    public int findActiveSyllableIndex(long positionMs) {
        int lo = 0;
        int hi = alignedSyllables.size() - 1;
        int ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            long ss = (long) (alignedSyllables.get(mid).syllable.startTime * 1000.0);
            if (ss <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }

    @NonNull
    public ActiveSylInfo getActiveSylInfo(int activeSylIdx) {
        if (activeSylIdx < 0 || activeSylIdx >= alignedSyllables.size()) {
            return new ActiveSylInfo(-1, -1, false);
        }
        AlignedSyllable s = alignedSyllables.get(activeSylIdx);
        int maxOff = Math.max(0, formattedMainText.length() - 1);
        int lineIdx = formattedMainText.isEmpty()
                ? 0
                : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxOff, s.startOffset)));
        boolean lastOfLine;
        if (activeSylIdx == alignedSyllables.size() - 1) {
            lastOfLine = true;
        } else {
            AlignedSyllable nextSyl = alignedSyllables.get(activeSylIdx + 1);
            int nextLineIdx = formattedMainText.isEmpty()
                    ? 0
                    : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxOff, nextSyl.startOffset)));
            int sOffset = Math.max(0, Math.min(formattedMainText.length(), Math.min(s.startOffset, nextSyl.startOffset)));
            int eOffset = Math.max(sOffset, Math.min(formattedMainText.length(), Math.max(s.endOffset, nextSyl.startOffset)));
            lastOfLine = (nextLineIdx > lineIdx)
                    || formattedMainText.substring(sOffset, eOffset).indexOf('\n') >= 0;
            if (!lastOfLine) {
                boolean onlyPunctMarksLeftOnLine = true;
                for (int k = activeSylIdx + 1; k < alignedSyllables.size(); k++) {
                    AlignedSyllable rem = alignedSyllables.get(k);
                    int remLine = formattedMainText.isEmpty()
                            ? 0
                            : textLayoutResult.getLineForOffset(Math.max(0, Math.min(maxOff, rem.startOffset)));
                    if (remLine > lineIdx) {
                        break;
                    }
                    long remDur = Math.round((rem.syllable.endTime - rem.syllable.startTime) * 1000.0);
                    if (remDur > 10L) {
                        onlyPunctMarksLeftOnLine = false;
                        break;
                    }
                }
                if (onlyPunctMarksLeftOnLine) {
                    lastOfLine = true;
                }
            }
        }
        return new ActiveSylInfo(activeSylIdx, lineIdx, lastOfLine);
    }

    public float getActiveSylProgress(int activeSylIdx, long currentPosition) {
        if (activeSylIdx < 0 || activeSylIdx >= alignedSyllables.size()) return 0f;
        AlignedSyllable activeS = alignedSyllables.get(activeSylIdx);
        long ssMs = (long) (activeS.syllable.startTime * 1000.0);
        long seMs = (long) (activeS.syllable.endTime * 1000.0);
        long dur = Math.max(1L, seMs - ssMs);
        long elapsed = Math.max(0L, Math.min(dur, currentPosition - ssMs));
        return (float) elapsed / (float) dur;
    }

    private float updateGlobalX(
            int activeSylIdx,
            long currentPosition,
            boolean isActiveLine,
            boolean allSung,
            boolean reachedLineEnd,
            long nowUptimeMs,
            boolean isPlaying) {
        if (isActiveLine) {
            finishStartUptimeMs = -1L;
            finishAlpha = 0f;
            if (allSung) {
                lastActiveGX = Math.max(lastActiveGX, lineEndX);
                return lastActiveGX;
            }
            boolean backwardSeek = (lastActivePosMs != Long.MIN_VALUE
                    && currentPosition < lastActivePosMs - (isPlaying ? 500L : 400L));
            if (backwardSeek || currentPosition < lineStartMs) {
                lastActiveGX = 0f;
            }
            if (!backwardSeek && lastActivePosMs != Long.MIN_VALUE && currentPosition < lastActivePosMs) {
                currentPosition = lastActivePosMs;
            }
            lastActivePosMs = currentPosition;
            if (activeSylIdx == -1) {
                if (backwardSeek || currentPosition < lineStartMs) {
                    lastActiveGX = 0f;
                }
                return lastActiveGX;
            }
            float gx = 0f;
            for (int i = 0; i <= activeSylIdx && i < alignedSyllables.size(); i++) {
                AlignedSyllable s = alignedSyllables.get(i);
                long ssMs = (long) (s.syllable.startTime * 1000.0);
                long seMs = (long) (s.syllable.endTime * 1000.0);
                float sgStart = sylBounds.startX[i];
                float sgEnd = sylBounds.endX[i];
                if (currentPosition >= seMs) {
                    float completedX = (i == alignedSyllables.size() - 1)
                            ? Math.max(sgEnd, lineEndX)
                            : sgEnd;
                    gx = Math.max(gx, completedX);
                } else if (currentPosition > ssMs) {
                    long dur = Math.max(1L, seMs - ssMs);
                    float progress = Math.max(0f, Math.min(1f, (float) (currentPosition - ssMs) / (float) dur));
                    gx = Math.max(gx, sgStart + (sgEnd - sgStart) * progress);
                } else {
                    gx = Math.max(gx, sgStart);
                }
            }
            if (!backwardSeek && gx < lastActiveGX && currentPosition >= lineStartMs) {
                gx = lastActiveGX;
            }
            lastActiveGX = gx;
            return gx;
        } else {
            lastActivePosMs = Long.MIN_VALUE;
            if (allSung) {
                finishAlpha = 0f;
                lastActiveGX = Math.max(lastActiveGX, lineEndX);
                return lastActiveGX;
            }
            if (!reachedLineEnd) {
                finishStartUptimeMs = -1L;
                finishAlpha = 0f;
                lastActiveGX = 0f;
                return 0f;
            }
            if (lastActiveGX >= 1f && lastActiveGX <= (lineEndX - 0.5f)) {
                if (finishStartUptimeMs < 0L) {
                    finishStartUptimeMs = nowUptimeMs;
                    finishStartGX = lastActiveGX;
                }
                long elapsed = Math.max(0L, nowUptimeMs - finishStartUptimeMs);
                float tX = Math.max(0f, Math.min(1f, elapsed / 180f));
                float easedX = LINEAR_OUT_SLOW_IN.getInterpolation(tX);
                float gx = finishStartGX + (lineEndX - finishStartGX) * easedX;
                float tA = Math.max(0f, Math.min(1f, elapsed / 240f));
                finishAlpha = FAST_OUT_SLOW_IN.getInterpolation(tA);
                if (tX >= 1f && tA >= 1f) {
                    lastActiveGX = lineEndX;
                    finishAlpha = 0f;
                }
                return gx;
            }
            finishAlpha = 0f;
            return lastActiveGX;
        }
    }

    private void updateColorAndSimpleStates(long currentPosition, boolean isActiveLine, boolean allSung, long nowUptimeMs) {
        float targetCS = (!isActiveLine && !(isBackground && allSung)) ? 1f : 0f;
        float targetSS = (isActiveLine && currentPosition >= lineStartTime && currentPosition <= effectiveEndTimeMs) ? 1f : 0f;

        if (isFirstRun || lastDrawUptimeMs < 0L) {
            curColorState = targetCS;
            curColorVel = 0f;
            curSimpleState = targetSS;
            curSimpleVel = 0f;
            return;
        }
        float dt = Math.max(0.001f, Math.min(0.1f, (nowUptimeMs - lastDrawUptimeMs) / 1000f));

        float omegaCS = 14.0f;
        float errCS = curColorState - targetCS;
        if (Math.abs(errCS) > 0.0005f || Math.abs(curColorVel) > 0.005f) {
            float expCS = (float) Math.exp(-omegaCS * dt);
            float termCS = curColorVel + omegaCS * errCS;
            curColorState = targetCS + (errCS + termCS * dt) * expCS;
            curColorVel = (curColorVel - omegaCS * termCS * dt) * expCS;
        } else {
            curColorState = targetCS;
            curColorVel = 0f;
        }

        float omegaSS = 11.5f;
        float errSS = curSimpleState - targetSS;
        if (Math.abs(errSS) > 0.0005f || Math.abs(curSimpleVel) > 0.005f) {
            float expSS = (float) Math.exp(-omegaSS * dt);
            float termSS = curSimpleVel + omegaSS * errSS;
            curSimpleState = targetSS + (errSS + termSS * dt) * expSS;
            curSimpleVel = (curSimpleVel - omegaSS * termSS * dt) * expSS;
        } else {
            curSimpleState = targetSS;
            curSimpleVel = 0f;
        }
    }

    private void updateSmoothAnimationStates(
            long currentPosition,
            boolean isActiveLine,
            float safeSpeed,
            long nowUptimeMs) {
        int n = clusters.size();
        int sylCount = alignedSyllables.size();

        if (isActiveLine) {
            for (int i = 0; i < n; i++) {
                VisualCluster vc = clusters.get(i);
                if (!vc.isHeavy) continue;
                heavyRelTriggered[i] = false;
                heavyRelMode[i] = 0;
                heavyRelAnimFrom[i] = 0f;
                heavyRelAnimTarget[i] = 0f;
                heavyRelAnimDurationMs[i] = 0f;
                heavyRelAnimDelayMs[i] = 0f;
                heavyRelAnimStartMs[i] = -1L;
                clusterReleaseValues[i] = 0f;

                if (currentPosition < vc.wordStartMs) {
                    clusterGrowValues[i] = 0f;
                } else {
                    float elapsedSinceOwnStart = (float) (currentPosition - vc.wordStartMs - vc.charDelayMs);
                    if (elapsedSinceOwnStart <= 0f) {
                        clusterGrowValues[i] = 0f;
                    } else {
                        clusterGrowValues[i] = Math.max(0f, Math.min(1f, elapsedSinceOwnStart / clusterSpanMs[i]));
                    }
                }
            }
        } else if (isFirstRun && !wasActiveLine) {
            deactivatedPosMs = currentPosition;
            deactivatedUptimeMs = -1L;
            for (int i = 0; i < n; i++) {
                VisualCluster hc = clusters.get(i);
                if (!hc.isHeavy) continue;
                heavyRelTriggered[i] = true;
                heavyRelMode[i] = 0;
                heavyRelAnimFrom[i] = 1f;
                heavyRelAnimTarget[i] = 1f;
                heavyRelAnimDurationMs[i] = 0f;
                heavyRelAnimDelayMs[i] = 0f;
                heavyRelAnimStartMs[i] = -1L;
                clusterReleaseValues[i] = 1f;
                clusterGrowValues[i] = 0f;
            }
        } else {
            if (wasActiveLine) {
                deactivatedPosMs = currentPosition;
                deactivatedUptimeMs = nowUptimeMs;
                for (int i = 0; i < n; i++) {
                    VisualCluster hc = clusters.get(i);
                    if (!hc.isHeavy) continue;
                    heavyRelTriggered[i] = false;
                }
            }

            float elapsedSinceDeactivate = (deactivatedUptimeMs >= 0L)
                    ? Math.max(0f, (float) (nowUptimeMs - deactivatedUptimeMs))
                    : 0f;

            for (int i = 0; i < n; i++) {
                VisualCluster hc = clusters.get(i);
                if (!hc.isHeavy) continue;

                if (!heavyRelTriggered[i]) {
                    heavyRelTriggered[i] = true;
                    float wordRemaining = 0f;
                    for (int cIdx = 0; cIdx < n; cIdx++) {
                        VisualCluster c = clusters.get(cIdx);
                        if (c.isHeavy && c.wordStartMs == hc.wordStartMs) {
                            float rem = clusterSpanMs[cIdx] - (deactivatedPosMs - c.wordStartMs - c.charDelayMs);
                            if (rem > wordRemaining) wordRemaining = rem;
                        }
                    }
                    float ownRemaining = clusterSpanMs[i] - (deactivatedPosMs - hc.wordStartMs - hc.charDelayMs);
                    if (deactivatedPosMs >= hc.wordStartMs) {
                        if (wordRemaining > 0f && ownRemaining > 0f) {
                            heavyRelMode[i] = 1;
                            heavyRelAnimFrom[i] = clusterReleaseValues[i];
                            heavyRelAnimTarget[i] = 1f;
                            heavyRelAnimDurationMs[i] = Math.max(1f, Math.min(500f, ownRemaining) / safeSpeed);
                            heavyRelAnimDelayMs[i] = 0f;
                            heavyRelAnimStartMs[i] = nowUptimeMs;
                        } else {
                            heavyRelMode[i] = 2;
                            heavyRelAnimFrom[i] = clusterReleaseValues[i];
                            heavyRelAnimTarget[i] = 1f;
                            heavyRelAnimDurationMs[i] = 1000f;
                            heavyRelAnimDelayMs[i] = Math.max(0f, wordRemaining) / safeSpeed;
                            heavyRelAnimStartMs[i] = nowUptimeMs;
                        }
                    } else {
                        heavyRelMode[i] = 0;
                        heavyRelAnimFrom[i] = 1f;
                        heavyRelAnimTarget[i] = 1f;
                        heavyRelAnimDurationMs[i] = 0f;
                        heavyRelAnimDelayMs[i] = 0f;
                        heavyRelAnimStartMs[i] = -1L;
                        clusterReleaseValues[i] = 1f;
                        clusterGrowValues[i] = 0f;
                    }
                }

                int mode = heavyRelMode[i];
                if (mode == 1) {
                    float elapsed = Math.max(0f, (float) (nowUptimeMs - heavyRelAnimStartMs[i]) - heavyRelAnimDelayMs[i]);
                    float t = Math.max(0f, Math.min(1f, elapsed / Math.max(1f, heavyRelAnimDurationMs[i])));
                    float eased = t * t * (3f - 2f * t);
                    clusterReleaseValues[i] = heavyRelAnimFrom[i] + (heavyRelAnimTarget[i] - heavyRelAnimFrom[i]) * eased;
                } else if (mode == 2) {
                    float elapsed = (float) (nowUptimeMs - heavyRelAnimStartMs[i]) - heavyRelAnimDelayMs[i];
                    if (elapsed <= 0f) {
                        clusterReleaseValues[i] = heavyRelAnimFrom[i];
                    } else {
                        float t = Math.max(0f, Math.min(1f, elapsed / Math.max(1f, heavyRelAnimDurationMs[i])));
                        float eased = FAST_OUT_SLOW_IN.getInterpolation(t);
                        clusterReleaseValues[i] = heavyRelAnimFrom[i] + (heavyRelAnimTarget[i] - heavyRelAnimFrom[i]) * eased;
                    }
                } else {
                    clusterReleaseValues[i] = 1f;
                }

                if (deactivatedPosMs >= hc.wordStartMs && clusterGrowValues[i] < 1f) {
                    float virtualElapsed = (deactivatedPosMs - hc.wordStartMs - hc.charDelayMs)
                            + elapsedSinceDeactivate * safeSpeed;
                    if (virtualElapsed <= 0f) {
                        clusterGrowValues[i] = 0f;
                    } else {
                        clusterGrowValues[i] = Math.max(0f, Math.min(1f, virtualElapsed / clusterSpanMs[i]));
                    }
                } else if (deactivatedPosMs < hc.wordStartMs) {
                    clusterGrowValues[i] = 0f;
                }
            }
        }

        // Syllable elevation tween (1000ms FastOutSlowInEasing).
        final float targetElev = -elevationAmount;
        for (int sIdx = 0; sIdx < sylCount; sIdx++) {
            long startMs = (long) (alignedSyllables.get(sIdx).syllable.startTime * 1000.0);
            if (!isActiveLine) {
                sylElevTriggered[sIdx] = false;
                if (isFirstRun && !wasActiveLine) {
                    sylElevAnimFrom[sIdx] = 0f;
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevCurrent[sIdx] = 0f;
                    sylElevAnimDurationMs[sIdx] = 1000f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs - 1000L;
                } else if (wasActiveLine) {
                    sylElevAnimFrom[sIdx] = sylElevCurrent[sIdx];
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevAnimDurationMs[sIdx] = 1000f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs;
                }
            } else {
                if (wasActiveLine != isActiveLine && currentPosition < startMs) {
                    sylElevTriggered[sIdx] = false;
                    sylElevAnimFrom[sIdx] = 0f;
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevCurrent[sIdx] = 0f;
                    sylElevAnimDurationMs[sIdx] = 1000f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs - 1000L;
                }
                if (currentPosition >= startMs && !sylElevTriggered[sIdx]) {
                    sylElevTriggered[sIdx] = true;
                    sylElevAnimFrom[sIdx] = sylElevCurrent[sIdx];
                    sylElevAnimTarget[sIdx] = targetElev;
                    sylElevAnimDurationMs[sIdx] = 1000f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs;
                } else if (sylElevTriggered[sIdx] && sylElevAnimTarget[sIdx] != targetElev) {
                    sylElevAnimFrom[sIdx] = sylElevCurrent[sIdx];
                    sylElevAnimTarget[sIdx] = targetElev;
                    sylElevAnimDurationMs[sIdx] = 1000f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs;
                } else if (currentPosition < startMs && sylElevTriggered[sIdx]) {
                    sylElevTriggered[sIdx] = false;
                    sylElevAnimFrom[sIdx] = sylElevCurrent[sIdx];
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevAnimDurationMs[sIdx] = 1000f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs;
                } else if (sylElevAnimStartMs[sIdx] < 0L) {
                    sylElevAnimFrom[sIdx] = 0f;
                    sylElevAnimTarget[sIdx] = 0f;
                    sylElevCurrent[sIdx] = 0f;
                    sylElevAnimDurationMs[sIdx] = 1000f;
                    sylElevAnimStartMs[sIdx] = nowUptimeMs - 1000L;
                }
            }

            long elapsed = Math.max(0L, nowUptimeMs - sylElevAnimStartMs[sIdx]);
            float dur = sylElevAnimDurationMs[sIdx] > 0f ? sylElevAnimDurationMs[sIdx] : 1000f;
            if (elapsed >= (long) dur) {
                sylElevCurrent[sIdx] = sylElevAnimTarget[sIdx];
            } else {
                float t = elapsed / dur;
                float eased = FAST_OUT_SLOW_IN.getInterpolation(t);
                sylElevCurrent[sIdx] = sylElevAnimFrom[sIdx] + (sylElevAnimTarget[sIdx] - sylElevAnimFrom[sIdx]) * eased;
            }
        }
    }

    public void draw(
            @NonNull Canvas canvas,
            long currentPosition,
            boolean isActiveLine,
            boolean allSung,
            int expressiveAccent,
            int lineColor,
            float focusedAlpha,
            float playbackSpeed) {
        draw(canvas, currentPosition, isActiveLine, allSung, expressiveAccent, lineColor, focusedAlpha, playbackSpeed, true);
    }

    public void draw(
            @NonNull Canvas canvas,
            long currentPosition,
            boolean isActiveLine,
            boolean allSung,
            int expressiveAccent,
            int lineColor,
            float focusedAlpha,
            float playbackSpeed,
            boolean isPlaying) {
        ensureRenderState();
        if (isActiveLine) {
            everActivated = true;
        }
        long rawUptimeMs = SystemClock.uptimeMillis();
        if (lastRawUptimeMs >= 0L && isPlaying && lastWasPlaying) {
            long elapsed = rawUptimeMs - lastRawUptimeMs;
            if (elapsed > 0L && elapsed < 250L) {
                animClockMs += elapsed;
            }
        }
        lastRawUptimeMs = rawUptimeMs;
        lastWasPlaying = isPlaying;
        long nowUptimeMs = animClockMs;
        float safeSpeed = Math.max(0.1f, playbackSpeed);

        final boolean smoothSweep = Settings.LYRICS_WORD_SMOOTH_SWEEP.get();
        if (smoothSweep && (isActiveLine || (prewarmMasks && romaReveal >= 0.99f && transReveal >= 0.99f))) {
            maskCacheStore.prewarmStep(
                    clusters.size(),
                    1_500_000L,
                    idx -> clusters.get(idx).subLayout,
                    idx -> {
                        VisualCluster vc = clusters.get(idx);
                        if (!vc.isHeavy) return 0f;
                        return !clusterMotionOff[idx] ? glowBlurRadius : 0f;
                    }
            );
        }

        boolean reachedLineEnd = allSung || (currentPosition > 0L && currentPosition >= lineStartMs);
        int activeSylIdx = allSung ? lastSylIdx : findActiveSyllableIndex(currentPosition);
        ActiveSylInfo activeInfo = getActiveSylInfo(activeSylIdx);
        float activeSylProgress = allSung ? 1f : getActiveSylProgress(activeSylIdx, currentPosition);
        float curGX = updateGlobalX(activeSylIdx, currentPosition, isActiveLine, allSung, reachedLineEnd, nowUptimeMs, isPlaying);
        updateColorAndSimpleStates(currentPosition, isActiveLine, allSung, nowUptimeMs);

        boolean isFinishing = !isActiveLine
                && reachedLineEnd
                && lastSylIdx >= 0
                && curGX >= 1f
                && curGX <= (lineEndX - 0.5f);

        boolean isDeactivatingFade = !isActiveLine
                && reachedLineEnd
                && (curColorState < 0.995f || curSimpleState > 0.005f);

        boolean isFinishingOrDeactivating = allSung || isFinishing || isDeactivatingFade;
        int wipeSylIdx = isFinishingOrDeactivating ? lastSylIdx : activeInfo.index;
        int wipeLineIdx = isFinishingOrDeactivating ? (lineCount - 1) : activeInfo.lineIdx;
        boolean isActiveSylLastOfLine = activeInfo.isLastOfLine;
        boolean effLastOfLine = isActiveSylLastOfLine || isFinishingOrDeactivating;
        float effSylProgress = isFinishingOrDeactivating ? 1f : activeSylProgress;

        float litAlpha = 1f;
        int litColor = litColorCache.get(expressiveAccent, litAlpha);
        int baseUnlitColor = unlitColorCache.get(expressiveAccent, focusedAlpha);
        int targetPastColor = lineColor;
        int pastColor = (curColorState <= 0f)
                ? litColor
                : WordAnimationUtils.lerpColor(litColor, targetPastColor, curColorState);
        int unlitColor = (isFinishing && finishAlpha > 0f)
                ? WordAnimationUtils.lerpColor(baseUnlitColor, pastColor, finishAlpha)
                : baseUnlitColor;

        boolean showWipe = isActiveLine || isFinishing || isDeactivatingFade || (isBackground && allSung);
        int lineColorValue = showWipe ? Color.TRANSPARENT : lineColor;
        float simpleFrac = (currentPosition > effectiveEndTimeMs) ? 0f : Math.max(0f, Math.min(1f, curSimpleState));
        int inactiveColor = !showWipe
                ? WordAnimationUtils.lerpColor(lineColorValue, expressiveAccent, simpleFrac)
                : Color.TRANSPARENT;

        canvas.save();
        canvas.translate(-extraPaddingPx, -extraPaddingPx);

        float shiftProgress = Math.max(0f, Math.min(1f, romaScaleProgress));
        if (shiftProgress < 0.999f
                && (hasSyllableRomanization || hasRuby
                        || lazyExitingSyllableRomanization || lazyExitingRubyActive
                        || !formattedMainText.equals(mainText))) {
            ensureUnromanPositions();
        }
        boolean shiftActive = hasRomanShift && shiftProgress < 0.999f;

        if (!smoothSweep) {
            drawSimple(
                    canvas, showWipe, shiftActive, shiftProgress, wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress,
                    pastColor, unlitColor, litColor, lineColor, expressiveAccent);
            stillAnimating = isActiveLine || isFinishing || isDeactivatingFade;
        } else if (!isActiveLine && !showWipe && !isFinishing && !isDeactivatingFade
                && (!everActivated || (!wasActiveLine && !stillAnimating))) {
            if (!shiftActive && ensureStaticMainMask() && staticMainMask != null) {
                staticMainMaskPaint.setColor(inactiveColor);
                canvas.drawBitmap(
                        staticMainMask,
                        staticMainMaskOriginX + extraPaddingPx,
                        staticMainMaskOriginY + extraPaddingPx,
                        staticMainMaskPaint);
                drawRomanAndRuby(
                        canvas, false, wipeSylIdx, wipeLineIdx, curGX,
                        effLastOfLine, effSylProgress,
                        pastColor, unlitColor, litColor, lineColor,
                        curSimpleState, expressiveAccent);
                stillAnimating = false;
            } else if (shiftActive) {
                drawShiftedInactiveClusters(canvas, inactiveColor, shiftProgress);
                drawRomanAndRuby(
                        canvas, false, wipeSylIdx, wipeLineIdx, curGX,
                        effLastOfLine, effSylProgress,
                        pastColor, unlitColor, litColor, lineColor,
                        curSimpleState, expressiveAccent);
                stillAnimating = false;
            } else {
                updateSmoothAnimationStates(currentPosition, isActiveLine, safeSpeed, nowUptimeMs);
                drawSmooth(
                        canvas, currentPosition, isActiveLine, isFinishing, isDeactivatingFade, showWipe,
                        shiftProgress, wipeSylIdx, wipeLineIdx, curGX,
                        effLastOfLine, effSylProgress,
                        pastColor, unlitColor, litColor, inactiveColor, lineColor, expressiveAccent, nowUptimeMs);
            }
        } else {
            updateSmoothAnimationStates(currentPosition, isActiveLine, safeSpeed, nowUptimeMs);
            drawSmooth(
                    canvas, currentPosition, isActiveLine, isFinishing, isDeactivatingFade, showWipe,
                    shiftProgress, wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress,
                    pastColor, unlitColor, litColor, inactiveColor, lineColor, expressiveAccent, nowUptimeMs);
        }

        canvas.restore();

        if (!prewarmMasks && !isActiveLine && !isFinishing && !isDeactivatingFade && !stillAnimating) {
            evictMasks();
        }

        wasActiveLine = isActiveLine;
        isFirstRun = false;
        lastDrawUptimeMs = nowUptimeMs;
    }

    private void ensureUnromanPositions() {
        if (unromanPositionsBuilt || clusters == null || statics == null) {
            return;
        }
        unromanPositionsBuilt = true;
        int n = clusters.size();
        if (n == 0) {
            hasRomanShift = false;
            lazyBasePaint = null;
            return;
        }
        float[] ux = new float[n];
        float[] uy = new float[n];
        boolean anyDiff = false;

        if (formattedMainText.equals(mainText)) {
            for (int i = 0; i < n; i++) {
                VisualCluster vc = clusters.get(i);
                ux[i] = vc.x;
                uy[i] = vc.y + firstLineRubyOffset;
                if (Math.abs(ux[i] - statics.baseX[i]) > 0.25f
                        || Math.abs(uy[i] - statics.baseY[i]) > 0.25f) {
                    anyDiff = true;
                }
            }
        } else {
            TextPaint paint = lazyBasePaint != null ? lazyBasePaint : new TextPaint();
            boolean isMainRtl = LyricsSpanBuilder.isRtlText(mainText);
            Layout unromanLayout = unromanLayoutCache;
            if (unromanLayout == null) {
                unromanLayout = WordAnimationUtils.createStaticLayout(
                        mainText, paint, measuredWidth, alignment, isMainRtl);
                unromanLayoutCache = unromanLayout;
            }
            int[] fmtToMain = WordAnimationUtils.mapFormattedToMainOffsets(formattedMainText, mainText);
            int mainLen = mainText.length();
            int maxMainChar = Math.max(0, mainLen - 1);
            for (int i = 0; i < n; i++) {
                VisualCluster vc = clusters.get(i);
                if (vc.start < formattedMainText.length()
                        && Character.isWhitespace(formattedMainText.charAt(vc.start))
                        && (vc.end - vc.start) == 1) {
                    ux[i] = statics.baseX[i];
                    uy[i] = statics.baseY[i];
                    continue;
                }
                int fStart = Math.max(0, Math.min(fmtToMain.length - 1, vc.start));
                int mStart = Math.min(maxMainChar, fmtToMain[fStart]);
                int fEnd = Math.max(fStart, Math.min(fmtToMain.length - 1, vc.end));
                int mEnd = Math.min(mainLen, Math.max(mStart + 1, fmtToMain[fEnd]));
                int mLine = mainText.isEmpty() ? 0 : unromanLayout.getLineForOffset(mStart);
                float x;
                if (!vc.isRtl && unromanLayout.getParagraphDirection(mLine) != Layout.DIR_RIGHT_TO_LEFT) {
                    x = unromanLayout.getPrimaryHorizontal(mStart);
                } else {
                    int mEndLine = mainText.isEmpty()
                            ? 0
                            : unromanLayout.getLineForOffset(Math.min(maxMainChar, Math.max(mStart, mEnd - 1)));
                    float hs = unromanLayout.getPrimaryHorizontal(mStart);
                    float he = (mLine == mEndLine)
                            ? unromanLayout.getPrimaryHorizontal(Math.min(mainLen, mEnd))
                            : unromanLayout.getLineRight(mLine);
                    x = Math.min(hs, he);
                }
                int baseOff = (vc.subLayout != null) ? vc.subLayout.getLineBaseline(0) : 0;
                float y = unromanLayout.getLineBaseline(mLine) - baseOff + firstLineRubyOffset;
                ux[i] = x;
                uy[i] = y;
                if (Math.abs(x - statics.baseX[i]) > 0.25f
                        || Math.abs(y - statics.baseY[i]) > 0.25f) {
                    anyDiff = true;
                }
            }
        }
        lazyBasePaint = null;
        if (anyDiff) {
            this.unromanBaseX = ux;
            this.unromanBaseY = uy;
            this.hasRomanShift = true;
        } else {
            this.hasRomanShift = false;
        }
    }

    private float restBaseX(int idx, float shiftProgress) {
        if (shiftProgress >= 0.999f || !hasRomanShift || unromanBaseX == null) {
            return statics.baseX[idx];
        }
        float ux = unromanBaseX[idx];
        return ux + (statics.baseX[idx] - ux) * shiftProgress;
    }

    private float restBaseY(int idx, float shiftProgress) {
        if (shiftProgress >= 0.999f || !hasRomanShift || unromanBaseY == null) {
            return statics.baseY[idx];
        }
        float uy = unromanBaseY[idx];
        return uy + (statics.baseY[idx] - uy) * shiftProgress;
    }

    private void drawShiftedInactiveClusters(
            @NonNull Canvas canvas,
            int color,
            float shiftProgress) {
        int n = clusters.size();
        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            if (vc.subLayout == null) continue;
            float topLeftX = restBaseX(idx, shiftProgress) + extraPaddingPx;
            float topLeftY = restBaseY(idx, shiftProgress) + extraPaddingPx;
            WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, color, null);
        }
    }

    private boolean shouldRasterizeMaskNow() {
        return romaReveal >= 0.99f
                && transReveal >= 0.99f
                && !lazyExitingSyllableRomanization
                && !lazyExitingRubyActive
                && Math.abs(romaScaleProgress - 1f) < 0.01f;
    }

    private boolean ensureStaticMainMask() {
        if (staticMainMask != null) {
            return !staticMainMask.isRecycled();
        }
        if (!shouldRasterizeMaskNow() || staticMainMaskAttempted || clusters == null || clusters.isEmpty()) {
            return false;
        }
        staticMainMaskAttempted = true;
        int n = clusters.size();
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        for (int idx = 0; idx < n; idx++) {
            Layout sl = clusters.get(idx).subLayout;
            if (sl == null) continue;
            float x = statics.baseX[idx];
            float y = statics.baseY[idx];
            if (x < minX) minX = x;
            if (y < minY) minY = y;
            float right = x + sl.getWidth();
            float bottom = y + sl.getHeight();
            if (right > maxX) maxX = right;
            if (bottom > maxY) maxY = bottom;
        }
        if (minX == Float.MAX_VALUE || maxX <= minX || maxY <= minY) {
            return false;
        }
        float originX = (float) Math.floor(minX) - 4f;
        float originY = (float) Math.floor(minY) - 4f;
        int bmpW = (int) Math.ceil(maxX - originX) + 4;
        int bmpH = (int) Math.ceil(maxY - originY) + 4;
        if (bmpW <= 0 || bmpH <= 0 || bmpW > 2048 || bmpH > 2048) {
            return false;
        }
        try {
            Bitmap bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ALPHA_8);
            Canvas c = new Canvas(bmp);
            for (int idx = 0; idx < n; idx++) {
                Layout sl = clusters.get(idx).subLayout;
                if (sl != null) {
                    WordAnimationUtils.drawLayoutWithPaint(
                            c, sl, statics.baseX[idx] - originX, statics.baseY[idx] - originY, Color.WHITE, null);
                }
            }
            staticMainMask = bmp;
            staticMainMaskOriginX = originX;
            staticMainMaskOriginY = originY;
            return true;
        } catch (OutOfMemoryError ignored) {
            return false;
        }
    }

    private void drawRomanAndRuby(
            @NonNull Canvas canvas,
            boolean showWipe,
            int wipeSylIdx,
            int wipeLineIdx,
            float curGX,
            boolean effLastOfLine,
            float effSylProgress,
            int pastColor,
            int unlitColor,
            int litColor,
            int lineColor,
            float curSimpleState,
            int expressiveAccent) {
        final boolean allowMask = shouldRasterizeMaskNow();
        if (!romanStatics.isEmpty() && romaReveal > 0f) {
            WordAnimationUtils.drawRomanizedWordClusters(
                    canvas, romanStatics, showWipe, wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress, gradientWidth,
                    scaleAlpha(pastColor, romaReveal), scaleAlpha(unlitColor, romaReveal), scaleAlpha(litColor, romaReveal),
                    showWipe ? Color.TRANSPARENT : scaleAlpha(lineColor, romaReveal),
                    curSimpleState, scaleAlpha(expressiveAccent, romaReveal),
                    romaScaleProgress, fontSizePx * 0.14f, alignment, measuredWidth, allowMask);
        }
        if (!rubyStatics.isEmpty() && romaReveal > 0f) {
            WordAnimationUtils.drawRomanizedWordClusters(
                    canvas, rubyStatics, showWipe, wipeSylIdx, wipeLineIdx, curGX,
                    effLastOfLine, effSylProgress, gradientWidth,
                    scaleAlpha(pastColor, romaReveal), scaleAlpha(unlitColor, romaReveal), scaleAlpha(litColor, romaReveal),
                    showWipe ? Color.TRANSPARENT : scaleAlpha(lineColor, romaReveal),
                    curSimpleState, scaleAlpha(expressiveAccent, romaReveal),
                    romaScaleProgress, -fontSizePx * 0.14f, alignment, measuredWidth, allowMask);
        }
    }

    private void drawSimple(
            @NonNull Canvas canvas,
            boolean showWipe,
            boolean shiftActive,
            float shiftProgress,
            int wipeSylIdx,
            int wipeLineIdx,
            float curGX,
            boolean effLastOfLine,
            float effSylProgress,
            int pastColor,
            int unlitColor,
            int litColor,
            int lineColor,
            int expressiveAccent) {
        if (!showWipe && !shiftActive && ensureStaticMainMask() && staticMainMask != null) {
            staticMainMaskPaint.setColor(lineColor);
            canvas.drawBitmap(
                    staticMainMask,
                    staticMainMaskOriginX + extraPaddingPx,
                    staticMainMaskOriginY + extraPaddingPx,
                    staticMainMaskPaint);
        } else {
            int n = clusters.size();
            for (int idx = 0; idx < n; idx++) {
                VisualCluster vc = clusters.get(idx);
                float topLeftX = restBaseX(idx, shiftProgress) + extraPaddingPx;
                float topLeftY = restBaseY(idx, shiftProgress) + extraPaddingPx;
                if (!showWipe) {
                    WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, lineColor, null);
                } else {
                    WordAnimationUtils.drawWipeText(
                            canvas, vc, topLeftX, topLeftY,
                            statics.isRtlLine[idx], statics.lineStart[idx], statics.lineWidth[idx],
                            statics.localStart[idx], statics.localEnd[idx],
                            wipeSylIdx, wipeLineIdx, curGX,
                            effLastOfLine, effSylProgress, gradientWidth,
                            pastColor, unlitColor, litColor, wipeScratch);
                }
            }
        }

        drawRomanAndRuby(
                canvas, showWipe, wipeSylIdx, wipeLineIdx, curGX,
                effLastOfLine, effSylProgress,
                pastColor, unlitColor, litColor, lineColor,
                0f, expressiveAccent);
    }

    private void drawSmooth(
            @NonNull Canvas canvas,
            long currentPosition,
            boolean isActiveLine,
            boolean isFinishing,
            boolean isDeactivatingFade,
            boolean showWipe,
            float shiftProgress,
            int wipeSylIdx,
            int wipeLineIdx,
            float curGX,
            boolean effLastOfLine,
            float effSylProgress,
            int pastColor,
            int unlitColor,
            int litColor,
            int inactiveColor,
            int lineColor,
            int expressiveAccent,
            long nowUptimeMs) {
        long drawCurrentPos = isActiveLine ? currentPosition : 0L;
        ClusterMaskCache maskCache = (isActiveLine || isFinishing || isDeactivatingFade) ? maskCacheStore : null;
        int n = clusters.size();
        boolean anyGlow = false;
        boolean anyRunningAnim = isActiveLine || isFinishing || isDeactivatingFade;

        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            final boolean isGrowableWord = vc.isHeavy;
            float elevValue = 0f;
            int sIdx = vc.syllableIndex >= 0 ? vc.syllableIndex : vc.shiftIndex;
            if (sIdx >= 0 && sIdx < sylElevCurrent.length) {
                elevValue = sylElevCurrent[sIdx];
                float dur = sylElevAnimDurationMs[sIdx] > 0f ? sylElevAnimDurationMs[sIdx] : 1000f;
                if (nowUptimeMs - sylElevAnimStartMs[sIdx] < (long) dur || Math.abs(elevValue - sylElevAnimTarget[sIdx]) > 0.001f) {
                    anyRunningAnim = true;
                }
            }

            float scale = 1.0f;
            float shift = 0f;
            float glowAlpha = 0f;
            float lift = 0f;
            float rise = 0f;

            boolean disableGlowAndGrow = clusterMotionOff[idx];
            boolean notStartedYet = isActiveLine && drawCurrentPos < vc.wordStartMs;
            float growFraction = notStartedYet ? 0f : clusterGrowValues[idx];
            boolean isEmphasised = false;

            final float[] emphasisTable = isGrowableWord ? ensureEmphasisTable(idx, vc) : null;
            if (emphasisTable != null) {
                sampleEmphasisCached(emphasisTable, growFraction, sampleScratch);
                isEmphasised = growFraction > 0f && growFraction < 1f;
                if (isEmphasised) anyRunningAnim = true;

                float heavyRelease = clusterReleaseValues[idx];
                if (!isActiveLine && (heavyRelease < 1f || (deactivatedPosMs >= vc.wordStartMs && clusterGrowValues[idx] < 1f))) {
                    anyRunningAnim = true;
                }

                float pulse = sampleScratch.pulse * vc.intensity;

                // Apply character-by-character spring lift, wave rise, scale, and horizontal spread.
                if (!disableGlowAndGrow) {
                    lift = -emphasisLiftEm * fontSizePx * sampleScratch.lift * (1f - heavyRelease);
                    rise = -emphasisRiseEm * fontSizePx * pulse;
                    if (!vc.disableScale) {
                        scale = 1f + emphasisScaleGain * pulse;
                        shift = vc.normX * emphasisShiftEm * fontSizePx * pulse;
                    }
                    glowAlpha = Math.max(0f, Math.min(1f, vc.charShadowIntensity * sampleScratch.glow));
                }
            } else {
                lift = elevValue;
            }

            float baseX = restBaseX(idx, shiftProgress);
            float baseY = restBaseY(idx, shiftProgress);
            float drawX = baseX + shift;
            float drawY = baseY + lift + rise;
            boolean isGlowing = !disableGlowAndGrow && isGrowableWord && isEmphasised;

            clusterScaleX[idx] = scale;
            clusterScaleY[idx] = scale;
            clusterGlowAlphas[idx] = glowAlpha;
            clusterIsGlowing[idx] = isGlowing;
            clusterDrawX[idx] = drawX;
            clusterDrawY[idx] = drawY;

            float lastY = clusterLastDrawY[idx];
            boolean isYMoving = (!Float.isNaN(lastY) && Math.abs(drawY - lastY) > 0.001f)
                    || Math.abs(drawY - baseY) > 0.001f;
            clusterIsYMoving[idx] = isYMoving;
            clusterLastDrawY[idx] = drawY;
            if (isGlowing) anyGlow = true;
        }

        if (anyGlow) {
            for (int idx = 0; idx < n; idx++) {
                if (clusterIsGlowing[idx]) {
                    float glowAlpha = clusterGlowAlphas[idx];
                    if (glowAlpha <= 0f) continue;
                    VisualCluster vc = clusters.get(idx);
                    float scale = clusterScaleX[idx];
                    float drawX = clusterDrawX[idx];
                    float drawY = clusterDrawY[idx];
                    float tx = drawX + extraPaddingPx;
                    float ty = drawY + extraPaddingPx;

                    Bitmap glowMask = (maskCache != null) ? maskCache.obtainGlow(idx, vc.subLayout, glowBlurRadius) : null;
                    if (glowMask != null && maskCache != null) {
                        canvas.save();
                        canvas.translate(tx, ty);
                        if (scale != 1.0f) {
                            canvas.scale(scale, scale, statics.pivotX[idx], statics.pivotY[idx]);
                        }
                        maskCache.drawMaskTinted(
                                canvas,
                                glowMask,
                                maskCache.glowScaleOf(idx),
                                maskCache.glowFilter,
                                glowAlpha,
                                PorterDuff.Mode.SCREEN);
                        canvas.restore();
                    }
                }
            }
        }

        ColorFilter pastFilter = (maskCache != null) ? maskCache.pastTint.get(pastColor) : null;
        ColorFilter unlitFilter = (maskCache != null) ? maskCache.unlitTint.get(unlitColor) : null;
        ColorFilter inactiveFilter = (maskCache != null && !showWipe) ? maskCache.inactiveTint.get(inactiveColor) : null;

        for (int idx = 0; idx < n; idx++) {
            VisualCluster vc = clusters.get(idx);
            float cdScale = clusterScaleX[idx];
            float cdDrawX = clusterDrawX[idx];
            float cdDrawY = clusterDrawY[idx];
            boolean isRtlLine = statics.isRtlLine[idx];
            float lgs = statics.lineStart[idx];
            float lw = statics.lineWidth[idx];
            float topLeftX = cdDrawX + extraPaddingPx;
            float topLeftY = cdDrawY + extraPaddingPx;

            boolean isScaling = cdScale != 1.0f;
            boolean isElevating = clusterIsYMoving[idx];
            boolean isAnimating = (isActiveLine || isFinishing || isDeactivatingFade) && (isScaling || isElevating);
            Bitmap mask = (isAnimating && maskCache != null) ? maskCache.obtain(idx, vc.subLayout) : null;

            if (mask == null || maskCache == null) {
                if (isScaling) {
                    canvas.save();
                    canvas.translate(topLeftX, topLeftY);
                    canvas.scale(cdScale, cdScale, statics.pivotX[idx], statics.pivotY[idx]);
                    if (!showWipe) {
                        WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, 0f, 0f, inactiveColor, null);
                    } else {
                        WordAnimationUtils.drawWipeText(
                                canvas, vc, 0f, 0f, isRtlLine, lgs, lw,
                                statics.localStart[idx], statics.localEnd[idx],
                                wipeSylIdx, wipeLineIdx, curGX,
                                effLastOfLine, effSylProgress, gradientWidth,
                                pastColor, unlitColor, litColor, wipeScratch);
                    }
                    canvas.restore();
                } else {
                    if (!showWipe) {
                        WordAnimationUtils.drawLayoutWithPaint(canvas, vc.subLayout, topLeftX, topLeftY, inactiveColor, null);
                    } else {
                        WordAnimationUtils.drawWipeText(
                                canvas, vc, topLeftX, topLeftY, isRtlLine, lgs, lw,
                                statics.localStart[idx], statics.localEnd[idx],
                                wipeSylIdx, wipeLineIdx, curGX,
                                effLastOfLine, effSylProgress, gradientWidth,
                                pastColor, unlitColor, litColor, wipeScratch);
                    }
                }
            } else {
                float maskScale = maskCache.scaleOf(idx);
                canvas.save();
                canvas.translate(topLeftX, topLeftY);
                if (isScaling) {
                    canvas.scale(cdScale, cdScale, statics.pivotX[idx], statics.pivotY[idx]);
                }
                if (!showWipe && inactiveFilter != null) {
                    maskCache.drawMaskTinted(canvas, mask, maskScale, inactiveFilter, 1f, null);
                } else {
                    WordAnimationUtils.computeWipe(
                            vc, isRtlLine, lgs, lw,
                            statics.localStart[idx], statics.localEnd[idx],
                            wipeSylIdx, wipeLineIdx, curGX,
                            effLastOfLine, effSylProgress, gradientWidth, wipeScratch);
                    if (wipeScratch.mode == WordAnimationUtils.WIPE_PAST) {
                        maskCache.drawMaskTinted(canvas, mask, maskScale, pastFilter, 1f, null);
                    } else if (wipeScratch.mode == WordAnimationUtils.WIPE_UNLIT) {
                        maskCache.drawMaskTinted(canvas, mask, maskScale, unlitFilter, 1f, null);
                    } else {
                        maskCache.drawMaskBand(canvas, mask, maskScale, wipeScratch, litColor, unlitColor);
                    }
                }
                canvas.restore();
            }
        }

        drawRomanAndRuby(
                canvas, showWipe, wipeSylIdx, wipeLineIdx, curGX,
                effLastOfLine, effSylProgress,
                pastColor, unlitColor, litColor, lineColor,
                curSimpleState, expressiveAccent);

        float targetCS = (!isActiveLine || currentPosition > effectiveEndTimeMs) ? 1f : 0f;
        float targetSS = (isActiveLine && currentPosition >= lineStartTime && currentPosition <= effectiveEndTimeMs) ? 1f : 0f;
        stillAnimating = anyRunningAnim
                || isFinishing
                || isDeactivatingFade
                || (!isActiveLine && (curSimpleState > 0.002f || (currentPosition >= lineStartTime && currentPosition <= effectiveEndTimeMs)))
                || Math.abs(curColorState - targetCS) > 0.002f
                || Math.abs(curSimpleState - targetSS) > 0.002f;
    }

    private static int scaleAlpha(int color, float factor) {
        if (factor >= 0.999f) return color;
        int a = Math.max(0, Math.min(255, Math.round(Color.alpha(color) * factor)));
        return (color & 0x00FFFFFF) | (a << 24);
    }

    public void reset() {
        lastActiveGX = 0f;
        lastActivePosMs = Long.MIN_VALUE;
        finishStartUptimeMs = -1L;
        finishStartGX = 0f;
        finishAlpha = 0f;
        curColorState = 0f;
        curColorVel = 0f;
        curSimpleState = 0f;
        curSimpleVel = 0f;
        deactivatedPosMs = 0L;
        deactivatedUptimeMs = -1L;
        wasActiveLine = false;
        isFirstRun = true;
        stillAnimating = false;
        everActivated = false;
        lastDrawUptimeMs = -1L;
        lastRawUptimeMs = -1L;
        lastWasPlaying = false;
        if (!renderStateBuilt) {
            return;
        }
        Arrays.fill(clusterGrowValues, 0f);
        Arrays.fill(clusterReleaseValues, 0f);
        Arrays.fill(heavyRelTriggered, false);
        Arrays.fill(heavyRelMode, 0);
        Arrays.fill(heavyRelAnimFrom, 0f);
        Arrays.fill(heavyRelAnimTarget, 0f);
        Arrays.fill(heavyRelAnimDurationMs, 0f);
        Arrays.fill(heavyRelAnimDelayMs, 0f);
        Arrays.fill(heavyRelAnimStartMs, -1L);
        Arrays.fill(clusterScaleX, 1.0f);
        Arrays.fill(clusterScaleY, 1.0f);
        Arrays.fill(clusterGlowAlphas, 0f);
        Arrays.fill(clusterIsGlowing, false);
        Arrays.fill(clusterFadeProgress, 0f);
        Arrays.fill(sylSungFactorValues, 0f);
        Arrays.fill(sylElevTriggered, false);
        Arrays.fill(sylElevAnimStartMs, -1L);
        Arrays.fill(sylElevAnimDurationMs, 1000f);
        Arrays.fill(sylElevCurrent, 0f);
        Arrays.fill(sylElevAnimTarget, 0f);
        Arrays.fill(sylElevAnimFrom, 0f);
        Arrays.fill(clusterLastDrawY, Float.NaN);
    }

    public void release() {
        evictMasks();
        if (staticMainMask != null) {
            if (!staticMainMask.isRecycled()) {
                staticMainMask.recycle();
            }
            staticMainMask = null;
        }
        staticMainMaskAttempted = false;
        if (romanStatics != null) {
            romanStatics.releaseStaticMasks();
        }
        if (rubyStatics != null) {
            rubyStatics.releaseStaticMasks();
        }
        lazyBasePaint = null;
    }
}