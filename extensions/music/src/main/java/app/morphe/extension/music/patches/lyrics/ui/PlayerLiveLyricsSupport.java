/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5527
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector;
import app.morphe.extension.music.patches.lyrics.ui.GapLineView.GapInterval;

/**
 * Pure state, dot-timing, WordSweepAnimator-aligned word motion, and auto-pan helpers for
 * {@link PlayerLiveLyricsView}.
 */
final class PlayerLiveLyricsSupport {

    static final float FOCUS_ANCHOR_RATIO = 0.34f;
    static final long ELEV_ANIM_DURATION_MS = 1000L;
    private static final long HEAVY_MIN_DURATION_MS = 1000L;
    private static final int HEAVY_MAX_NON_SPACE_CHARS = 7;
    private static final float AUTO_PAN_RATE = 9.0f;
    private static final long POST_FINAL_LINE_GAP_DELAY_MS = 2000L;
    private static final float LINE_PAN_HOLD_START = 0.15f;
    private static final float LINE_PAN_HOLD_END = 0.85f;
    private static final long FALLBACK_LINE_DURATION_MS = 4000L;
    private static final long MIN_LINE_PAN_DURATION_MS = 700L;
    private static final long NEXT_LINE_MARGIN_MS = 120L;

    enum GapKind {
        NONE,
        INTRO,
        INTERLUDE,
        OUTRO
    }

    static final StateSnapshot HIDDEN =
            new StateSnapshot(false, false, GapKind.NONE, false, -1, 0L, 0L, 0L);

    private PlayerLiveLyricsSupport() {
    }

    record StateSnapshot(boolean visible,
                         boolean isGap,
                         GapKind gapKind,
                         boolean gapRightAligned,
                         int lineIndex,
                         long gapStartMs,
                         long gapEndMs,
                         long gapNextStartMs) {
    }

    record DotPhase(float enterFraction,
                    float breatheScale,
                    float dot0Fraction,
                    float dot1Fraction,
                    float dot2Fraction,
                    boolean exiting,
                    float exitFraction) {
    }

    static StateSnapshot resolveState(@Nullable Lyrics lyrics,
                                      @Nullable List<GapInterval> gaps,
                                      long positionMs,
                                      int hintIndex) {
        return resolveState(lyrics, gaps, positionMs, hintIndex, true);
    }

    static StateSnapshot resolveState(@Nullable Lyrics lyrics,
                                      @Nullable List<GapInterval> gaps,
                                      long positionMs,
                                      int hintIndex,
                                      boolean gapIndicatorsEnabled) {
        if (lyrics == null || !lyrics.synced() || lyrics.isEmpty()) {
            return HIDDEN;
        }

        if (gapIndicatorsEnabled && gaps != null) {
            for (GapInterval gap : gaps) {
                if (positionMs >= gap.startMs() && positionMs < gap.nextStartMs()) {
                    final GapKind kind = gap.startMs() <= 0L ? GapKind.INTRO : GapKind.INTERLUDE;
                    return new StateSnapshot(
                            true,
                            true,
                            kind,
                            gap.isRtl(),
                            gap.beforeLineIndex(),
                            gap.startMs(),
                            gap.endMs(),
                            gap.nextStartMs()
                    );
                }
            }
        }

        final List<LyricsLine> lines = lyrics.lines();
        final int index = lyrics.indexForPosition(positionMs, hintIndex);
        if (index < 0) {
            // Only show intro dots when buildGapIntervals produced an intro gap; short pre-rolls
            // show the first vocal line dimmed instead of flashing dots for under a second.
            final int firstIdx = firstVocalIndex(lines);
            if (firstIdx < 0) {
                return HIDDEN;
            }
            return new StateSnapshot(true, false, GapKind.NONE, false, firstIdx, 0L, 0L, 0L);
        }

        if (gapIndicatorsEnabled && isAfterLastLeadOutro(lines, index, positionMs)) {
            final long outroStart = lines.get(index).endTimeMs() + POST_FINAL_LINE_GAP_DELAY_MS;
            final boolean lastIsRtl = ScriptDetector.isLineRtl(lines.get(index).text());
            return new StateSnapshot(true, true, GapKind.OUTRO, lastIsRtl, index,
                    outroStart, Long.MAX_VALUE, Long.MAX_VALUE);
        }

        return new StateSnapshot(true, false, GapKind.NONE, false, index, 0L, 0L, 0L);
    }

    /**
     * Computes entrance, 4s sinusoidal breathing, per-dot fill progression, and exit anticipation.
     */
    static DotPhase computeDotPhase(long positionMs,
                                    long gapStartMs,
                                    long gapEndMs,
                                    long gapNextStartMs,
                                    long elapsedSinceEnterMs,
                                    long enterDurationMs) {
        final float enterFraction = enterDurationMs > 0
                ? clamp01(elapsedSinceEnterMs / (float) enterDurationMs)
                : 1f;

        final boolean exiting = gapNextStartMs < Long.MAX_VALUE
                && gapNextStartMs > gapEndMs
                && positionMs >= gapEndMs;
        final float exitFraction = exiting
                ? clamp01((positionMs - gapEndMs) / (float) Math.max(1L, gapNextStartMs - gapEndMs))
                : 0f;

        final long breatheElapsedMs = Math.max(0L, elapsedSinceEnterMs);
        final float breatheBlend = Math.min(1f, breatheElapsedMs / 600f) * enterFraction;
        final float cosWave = (float) Math.cos((breatheElapsedMs / 4000.0) * Math.PI);
        final float breatheScale = 1.0f + 0.10f * breatheBlend * cosWave;

        final float d0;
        final float d1;
        final float d2;
        if (gapEndMs == Long.MAX_VALUE) {
            final float pulse = 0.5f + 0.5f * (float) Math.sin((breatheElapsedMs / 1400.0) * Math.PI);
            d0 = clamp01(pulse);
            d1 = clamp01((float) Math.sin(((breatheElapsedMs - 220L) / 1400.0) * Math.PI) * 0.5f + 0.5f);
            d2 = clamp01((float) Math.sin(((breatheElapsedMs - 440L) / 1400.0) * Math.PI) * 0.5f + 0.5f);
        } else {
            final long gapDuration = Math.max(1L, gapEndMs - gapStartMs);
            final float segmentDuration = gapDuration / 3f;
            final float gapPadding = segmentDuration * 0.3f;
            final float syllableDuration = Math.max(1f, segmentDuration * 0.7f);
            d0 = dotFraction(positionMs, gapStartMs + gapPadding, syllableDuration);
            d1 = dotFraction(positionMs, gapStartMs + segmentDuration + gapPadding, syllableDuration);
            d2 = dotFraction(positionMs, gapStartMs + 2f * segmentDuration + gapPadding, syllableDuration);
        }

        return new DotPhase(enterFraction, breatheScale, d0, d1, d2, exiting, exitFraction);
    }

    private static float dotFraction(long positionMs, float sylStart, float syllableDuration) {
        if (positionMs <= sylStart) {
            return 0f;
        }
        return clamp01((positionMs - sylStart) / syllableDuration);
    }

    /**
     * Matches {@link WordSweepAnimator}'s heavy-word gate: sustained words ({@code >= 1000ms})
     * with 1..7 non-whitespace characters in non-joining, non-RTL scripts and real word timings.
     */
    static boolean isHeavyWord(@Nullable String wordText,
                               long wordStartMs,
                               long wordEndMs,
                               boolean averaged,
                               boolean motionOff) {
        if (averaged || motionOff || wordEndMs - wordStartMs < HEAVY_MIN_DURATION_MS) {
            return false;
        }
        final int chars = countNonWhitespaceChars(wordText);
        return chars >= 1 && chars <= HEAVY_MAX_NON_SPACE_CHARS;
    }

    static int countNonWhitespaceChars(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    static float heavyHoldMs(long durationMs, int nonSpaceChars) {
        final double sec = Math.max(0.001, durationMs / 1000.0);
        return (float) (2.0 * sec / Math.max(1, nonSpaceChars) * 1000.0);
    }

    private static int firstVocalIndex(List<LyricsLine> lines) {
        for (int i = 0; i < lines.size(); i++) {
            final LyricsLine line = lines.get(i);
            if (!line.isBG() && line.startTimeMs() >= 0) {
                return i;
            }
        }
        return lines.isEmpty() ? -1 : 0;
    }

    private static boolean isAfterLastLeadOutro(List<LyricsLine> lines, int index, long positionMs) {
        for (int i = index + 1; i < lines.size(); i++) {
            if (!lines.get(i).isBG()) {
                return false;
            }
        }
        final LyricsLine line = lines.get(index);
        return line.endTimeMs() > line.startTimeMs()
                && positionMs >= line.endTimeMs() + POST_FINAL_LINE_GAP_DELAY_MS;
    }

    /**
     * Replaces line breaks in-place with spaces so character indices stay aligned with {@link WordTiming}.
     */
    static String toSingleLine(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            return text;
        }
        return text.replace('\r', ' ').replace('\n', ' ');
    }

    /**
     * Computes the horizontal sweep boundary in layout coordinates for the active position.
     * In RTL lines, {@code wordStartX[i]} sits to the right of {@code wordEndX[i]}, so the sweep
     * travels leftward from {@code totalWidth} toward {@code 0}.
     */
    static float computeSweepX(float[] wordStartX,
                               float[] wordEndX,
                               long[] wordStartMs,
                               long[] wordEndMs,
                               int count,
                               float totalWidth,
                               long positionMs,
                               boolean isRtl) {
        if (count <= 0) {
            return isRtl ? 0f : totalWidth;
        }
        if (positionMs <= wordStartMs[0]) {
            return isRtl ? totalWidth : 0f;
        }

        // Advance monotonically across started words so held or overlapping durations
        // never pin the sweep backward behind a later word that already started.
        float pos = isRtl ? totalWidth : 0f;
        boolean allEnded = true;
        for (int i = 0; i < count; i++) {
            final long startMs = wordStartMs[i];
            if (positionMs < startMs) {
                allEnded = false;
                continue;
            }
            final long endMs = wordEndMs[i];
            final float candidate;
            if (positionMs >= endMs) {
                candidate = wordEndX[i];
            } else {
                allEnded = false;
                final float duration = Math.max(1L, endMs - startMs);
                final float progress = clamp01((positionMs - startMs) / duration);
                candidate = wordStartX[i] + (wordEndX[i] - wordStartX[i]) * progress;
            }
            pos = isRtl ? Math.min(pos, candidate) : Math.max(pos, candidate);
        }

        if (allEnded) {
            return isRtl ? 0f : totalWidth;
        }
        return pos;
    }

    static float computeTargetScrollX(float focusX,
                                      float totalWidth,
                                      float viewportWidth,
                                      boolean isRtl) {
        if (totalWidth <= viewportWidth || viewportWidth <= 0f) {
            return 0f;
        }
        final float maxScroll = totalWidth - viewportWidth;
        final float desired = isRtl
                ? focusX - viewportWidth * (1f - FOCUS_ANCHOR_RATIO)
                : focusX - viewportWidth * FOCUS_ANCHOR_RATIO;
        return Math.max(0f, Math.min(maxScroll, desired));
    }

    /**
     * Resolves an active singing end timestamp for line-only pan calculation, preferring the
     * line's vocal end and falling back to the next lead line's start.
     */
    static long resolveLineEndMs(List<LyricsLine> lines, int lineIndex) {
        final LyricsLine line = lines.get(lineIndex);
        final long startMs = Math.max(0L, line.startTimeMs());
        final long sungEnd = line.sungEndMs();
        if (sungEnd > startMs) {
            return sungEnd;
        }
        if (line.endTimeMs() > startMs) {
            return line.endTimeMs();
        }
        for (int i = lineIndex + 1; i < lines.size(); i++) {
            final LyricsLine next = lines.get(i);
            if (!next.isBG() && next.startTimeMs() > startMs) {
                final long nextStart = next.startTimeMs();
                return Math.max(startMs + Math.min(MIN_LINE_PAN_DURATION_MS, nextStart - startMs),
                        nextStart - NEXT_LINE_MARGIN_MS);
            }
        }
        return startMs + FALLBACK_LINE_DURATION_MS;
    }

    /**
     * Computes progressive Hold-Pan-Hold scroll for overflowing lines without word timings
     * (translated lines, line-level romanization, or plain LRC): holds at start ({@code 0..15%}),
     * pans evenly across the line's active vocal span ({@code 15..85%}), then holds at end.
     */
    static float computeLineOnlyTargetScrollX(long positionMs,
                                              long lineStartMs,
                                              long lineEndMs,
                                              float totalWidth,
                                              float viewportWidth,
                                              boolean isRtl) {
        if (totalWidth <= viewportWidth || viewportWidth <= 0f) {
            return 0f;
        }
        final float maxScroll = totalWidth - viewportWidth;
        final long spanMs = Math.max(1L, lineEndMs - lineStartMs);
        final float progress = clamp01((positionMs - lineStartMs) / (float) spanMs);
        final float panFraction;
        if (progress <= LINE_PAN_HOLD_START) {
            panFraction = 0f;
        } else if (progress >= LINE_PAN_HOLD_END) {
            panFraction = 1f;
        } else {
            panFraction = (progress - LINE_PAN_HOLD_START) / (LINE_PAN_HOLD_END - LINE_PAN_HOLD_START);
        }
        return isRtl ? maxScroll * (1f - panFraction) : maxScroll * panFraction;
    }

    static float stepScrollX(float current, float target, long deltaMs) {
        if (Math.abs(target - current) < 0.5f) {
            return target;
        }
        final float dt = Math.max(0f, Math.min(0.1f, deltaMs / 1000f));
        final float factor = 1f - (float) Math.exp(-AUTO_PAN_RATE * dt);
        return current + (target - current) * factor;
    }

    static float clamp01(float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    /** Interpolates the container height reveal fraction smoothly across reversible enter/exit runs. */
    static float computeRevealStep(float startFraction, float targetFraction, float easedProgress) {
        return clamp01(startFraction + (targetFraction - startFraction) * clamp01(easedProgress));
    }

    /**
     * Computes foreground content opacity for the container reveal: enters slightly after box
     * expansion clears vertical padding, and fades out cleanly ahead of full collapse.
     */
    static float computeContentRevealAlpha(float revealFraction, boolean collapsing) {
        if (collapsing) {
            return clamp01((revealFraction - 0.25f) / 0.75f);
        }
        return clamp01((revealFraction - 0.15f) / 0.85f);
    }

    /**
     * Picks a deterministic variation index per track, gap phase, and timestamp so seeking within
     * the same instrumental break preserves its label while different gaps and tracks rotate.
     */
    static int selectGapLabelIndex(int trackSeed, GapKind kind, long gapStartMs, int optionCount) {
        if (optionCount <= 0 || kind == null || kind == GapKind.NONE) {
            return -1;
        }
        int hash = trackSeed * 0x9E3779B9;
        hash ^= (kind.ordinal() + 1) * 0x85EBCA6B;
        hash ^= (int) (gapStartMs ^ (gapStartMs >>> 32));
        hash ^= (hash >>> 16);
        hash *= 0x7FEB352D;
        hash ^= (hash >>> 15);
        return (hash & 0x7fffffff) % optionCount;
    }

    /**
     * Computes a gentle phase-staggered vertical wave offset for each gap note indicator, damped
     * during entrance and exit so the wave glides cleanly into and out of the lyric line.
     */
    static float computeNoteWaveOffset(int noteIndex, long elapsedMs, float amplitudePx,
                                       float enterFraction, boolean exiting, float exitFraction) {
        if (amplitudePx <= 0f) {
            return 0f;
        }
        final float damping = clamp01(enterFraction) * (exiting ? (1f - clamp01(exitFraction)) : 1f);
        if (damping <= 0.001f) {
            return 0f;
        }
        final double angle = (Math.max(0L, elapsedMs) / 1800.0) * Math.PI * 2.0 - noteIndex * 1.15;
        return (float) (-Math.sin(angle) * amplitudePx * damping);
    }

    /**
     * Fills any uncovered non-whitespace runs in {@code text} (such as standalone punctuation tokens
     * omitted by reading tokenizers or untimed leading/trailing symbols) so word-masked rendering
     * never drops characters that exist in the layout.
     */
    static List<WordTiming> fillUncoveredTextSpans(@Nullable String text,
                                                   @Nullable List<WordTiming> mappedTimings) {
        if (text == null || text.isEmpty() || mappedTimings == null || mappedTimings.isEmpty()) {
            return Collections.emptyList();
        }
        final int textLen = text.length();
        final List<WordTiming> result = new ArrayList<>(mappedTimings.size() + 2);
        int cursor = 0;
        long prevEndMs = Long.MIN_VALUE;

        for (int i = 0; i < mappedTimings.size(); i++) {
            final WordTiming w = mappedTimings.get(i);
            final int start = Math.max(0, Math.min(textLen, w.start()));
            final int end = Math.max(start, Math.min(textLen, w.end()));
            if (end <= start) {
                continue;
            }
            if (start > cursor) {
                final long[] anchor = resolveGapAnchor(prevEndMs, w.startMs());
                appendGapRuns(result, text, textLen, cursor, start, anchor[0], anchor[1]);
            }
            result.add((start == w.start() && end == w.end())
                    ? w
                    : new WordTiming(start, end, w.startMs(), w.endMs(), w.romaji(),
                    w.hasTrailingSpace(), w.ruby(), w.averaged(), w.wordGroup()));
            cursor = Math.max(cursor, end);
            final long itemEndMs = w.endMs() >= w.startMs() ? w.endMs() : w.startMs();
            if (prevEndMs == Long.MIN_VALUE) {
                prevEndMs = itemEndMs;
            } else if (itemEndMs >= 0L) {
                prevEndMs = prevEndMs < 0L ? itemEndMs : Math.max(prevEndMs, itemEndMs);
            }
        }

        if (cursor < textLen && !result.isEmpty()) {
            final long anchor = prevEndMs == Long.MIN_VALUE ? LyricsLine.NO_TIME : prevEndMs;
            appendGapRuns(result, text, textLen, cursor, textLen, anchor, anchor);
        }
        return result;
    }

    private static long[] resolveGapAnchor(long prevEndMs, long nextStartMs) {
        if (prevEndMs == Long.MIN_VALUE) {
            return new long[]{nextStartMs, nextStartMs};
        }
        if (prevEndMs < 0L || nextStartMs < 0L) {
            final long valid = prevEndMs >= 0L ? prevEndMs : nextStartMs;
            return new long[]{valid, valid};
        }
        return new long[]{prevEndMs, Math.max(prevEndMs, nextStartMs)};
    }

    private static int countGapRuns(String text, int gapStart, int gapEnd) {
        int runCount = 0;
        int idx = gapStart;
        while (idx < gapEnd) {
            while (idx < gapEnd && Character.isWhitespace(text.charAt(idx))) {
                idx++;
            }
            if (idx < gapEnd) {
                runCount++;
                while (idx < gapEnd && !Character.isWhitespace(text.charAt(idx))) {
                    idx++;
                }
            }
        }
        return runCount;
    }

    private static void appendGapRuns(List<WordTiming> out, String text, int textLen,
                                      int gapStart, int gapEnd, long anchorStartMs, long anchorEndMs) {
        final int runCount = countGapRuns(text, gapStart, gapEnd);
        if (runCount == 0) {
            return;
        }
        final long span = (anchorStartMs >= 0L && anchorEndMs > anchorStartMs)
                ? anchorEndMs - anchorStartMs : 0L;
        int emitted = 0;
        int idx = gapStart;
        while (idx < gapEnd) {
            while (idx < gapEnd && Character.isWhitespace(text.charAt(idx))) {
                idx++;
            }
            final int runStart = idx;
            while (idx < gapEnd && !Character.isWhitespace(text.charAt(idx))) {
                idx++;
            }
            if (idx > runStart) {
                final long rStart = anchorStartMs < 0L
                        ? anchorStartMs : anchorStartMs + (span * emitted) / runCount;
                final long rEnd = anchorStartMs < 0L
                        ? anchorStartMs : anchorStartMs + (span * (emitted + 1)) / runCount;
                final boolean trailSpace = idx < textLen && Character.isWhitespace(text.charAt(idx));
                out.add(new WordTiming(runStart, idx, rStart, rEnd, null, trailSpace, true));
                emitted++;
            }
        }
    }
}
