/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.Map;

/** Spring curves of the lyrics scroll, and the delay each line moves with. */
final class SpringEasing {

    static final Interpolator SPRING_SCROLL_EASING =
            new PathInterpolator(0.41f, 0f, 0.12f, 0.99f);

    static final float DEFAULT_ZETA = 0.78f;
    static final float RELAX_ZETA = 0.80f;
    /** Damping between lines that follow closely, so a quick run of lines does not bounce. */
    static final float ZETA_TIGHT = 0.85f;
    /** Damping between lines with a pause of {@link #MAX_DYNAMIC_GAP_MS} or more. */
    static final float ZETA_WIDE = 0.73f;
    static final float MAX_DYNAMIC_GAP_MS = 2500f;
    /** How long the settle after the overshoot takes, relative to the rise. */
    static final float SETTLE_RATIO = 1.5f;
    private static final int SPRING_CACHE_SIZE = 128;

    /** Only used on the main thread. */
    private static final Map<Long, LyricsSpring> SPRING_CACHE = new HashMap<>();

    private SpringEasing() {
    }

    /**
     * {@return the damping ratio for a scroll to a line that follows the previous one after
     * this pause}
     */
    static float computeZeta(long timeGapMs) {
        return computeZeta(timeGapMs, false);
    }

    /**
     * Computes dynamic damping ratio (zeta) based on line intervals:
     * - Tighter damping (0.85) for short inter-line gaps so rapid lines don't bounce excessively
     * - Wider damping (0.73) for longer inter-line gaps up to 2500ms
     * - Adds +0.05 damping (clamped to 0.95) in Relax Scrolling mode
     */
    static float computeZeta(long timeGapMs, boolean isRelaxMode) {
        float zeta = isRelaxMode ? RELAX_ZETA : DEFAULT_ZETA;
        if (timeGapMs > 0L) {
            final float t = Math.max(0f, Math.min(1f, (float) timeGapMs / MAX_DYNAMIC_GAP_MS));
            zeta = ZETA_TIGHT - t * (ZETA_TIGHT - ZETA_WIDE);
            if (isRelaxMode) {
                zeta = Math.min(0.95f, zeta + 0.05f);
            }
        }
        return zeta;
    }

    /** {@return a spring that rises in about this time, cached as most scrolls share one} */
    @NonNull
    static LyricsSpring springFor(long durationMs, long timeGapMs) {
        return springFor(durationMs, timeGapMs, false);
    }

    /** {@return a spring that rises in about this time with optional Relax Scrolling damping} */
    @NonNull
    static LyricsSpring springFor(long durationMs, long timeGapMs, boolean isRelaxMode) {
        return springFor(durationMs, computeZeta(timeGapMs, isRelaxMode), SETTLE_RATIO);
    }

    @NonNull
    static LyricsSpring springFor(long durationMs, float zeta, float settleRatio) {
        final int durationKey = Math.max(10, (int) (((Math.max(1L, durationMs) + 5L) / 10L) * 10L));
        final int zetaKey = Math.round(zeta * 1000f);
        final int ratioKey = Math.round(settleRatio * 100f);
        final long key = ((long) durationKey << 32) | ((long) (zetaKey & 0xFFFF) << 16) | (ratioKey & 0xFFFF);
        LyricsSpring spring = SPRING_CACHE.get(key);
        if (spring == null) {
            if (SPRING_CACHE.size() >= SPRING_CACHE_SIZE) {
                SPRING_CACHE.clear();
            }
            spring = new LyricsSpring((float) durationKey, zetaKey / 1000f, ratioKey / 100f);
            SPRING_CACHE.put(key, spring);
        }
        return spring;
    }

    /**
     * Rises along {@link #SPRING_SCROLL_EASING} to the first overshoot of a damped spring, then
     * settles back to 1.
     */
    static final class LyricsSpring {
        final float peakMs;
        final float zeta;
        final float settleRatio;
        final float tailMs;
        final float totalMs;
        final float peakValue;

        LyricsSpring(float peakMs) {
            this(peakMs, DEFAULT_ZETA, SETTLE_RATIO);
        }

        LyricsSpring(float peakMs, float zeta) {
            this(peakMs, zeta, SETTLE_RATIO);
        }

        LyricsSpring(float peakMs, float zeta, float settleRatio) {
            this.peakMs = Math.max(1f, peakMs);
            this.zeta = Math.max(0.01f, Math.min(0.99f, zeta));
            this.settleRatio = Math.max(0.1f, settleRatio);
            this.tailMs = this.peakMs * this.settleRatio;
            this.totalMs = this.peakMs + this.tailMs;

            final double peakS = this.peakMs / 1000.0;
            final double omegaD = Math.PI / peakS;
            final double omega0 = omegaD / Math.sqrt(1.0 - this.zeta * this.zeta);
            final double decayRate = this.zeta * omega0;
            this.peakValue = (float) (1.0 + Math.exp(-decayRate * peakS));
        }

        float value(float tMs) {
            if (tMs <= 0f) {
                return 0f;
            }
            if (tMs >= totalMs) {
                return 1f;
            }
            if (tMs < peakMs) {
                return peakValue * SPRING_SCROLL_EASING.getInterpolation(tMs / peakMs);
            }
            final float u = (tMs - peakMs) / tailMs;
            return 1f + (peakValue - 1f) * 0.5f * (1f + (float) Math.cos(Math.PI * u));
        }
    }

    /**
     * Delays each row's move by how many lyric lines it is past the line the scroll started
     * from, in the scroll's direction. Gap rows do not count as a line.
     */
    static final class LyricsScrollStagger {
        static final int MAX_STAGGER_STEPS = 12;

        final boolean scrollingDown;
        final int referenceIndex;
        final int staggerAfterIndex;
        final long durationMs;
        final float delayIncrementMs;
        final boolean isRelaxMode;
        /** Lyric lines among the rows before each row index, one entry longer than the rows. */
        private final int[] linesBefore;

        LyricsScrollStagger(boolean scrollingDown, int referenceIndex, int staggerAfterIndex,
                            long durationMs, float delayIncrementMs, int[] linesBefore) {
            this(scrollingDown, referenceIndex, staggerAfterIndex, durationMs, delayIncrementMs, false, linesBefore);
        }

        /**
         * @param staggerAfterIndex The last row that moves without delay when scrolling down,
         *                          such as the end of a group of lines sung together.
         */
        LyricsScrollStagger(boolean scrollingDown, int referenceIndex, int staggerAfterIndex,
                            long durationMs, float delayIncrementMs, boolean isRelaxMode,
                            int[] linesBefore) {
            this.scrollingDown = scrollingDown;
            this.referenceIndex = referenceIndex;
            this.staggerAfterIndex = Math.max(referenceIndex, staggerAfterIndex);
            this.durationMs = durationMs;
            // When scrolling up in Relax Scrolling, delay is halved.
            this.delayIncrementMs = (!scrollingDown && isRelaxMode)
                    ? (delayIncrementMs * 0.5f)
                    : delayIncrementMs;
            this.isRelaxMode = isRelaxMode;
            this.linesBefore = linesBefore;
        }

        int steps(int index) {
            if (linesBefore.length == 0) {
                int ref = scrollingDown ? staggerAfterIndex : referenceIndex;
                int raw = scrollingDown ? (index - ref) : (ref - index);
                return Math.max(0, Math.min(MAX_STAGGER_STEPS, raw));
            }
            final int last = linesBefore.length - 1;
            if (last < 0) {
                return 0;
            }
            final int steps;
            if (scrollingDown) {
                steps = index <= staggerAfterIndex ? 0
                        : linesBefore[clamp(index + 1, last)] - linesBefore[clamp(staggerAfterIndex + 1, last)];
            } else {
                steps = index >= referenceIndex ? 0
                        : linesBefore[clamp(referenceIndex + 1, last)] - linesBefore[clamp(index + 1, last)];
            }
            return Math.max(0, Math.min(MAX_STAGGER_STEPS, steps));
        }

        private static int clamp(int index, int last) {
            return Math.max(0, Math.min(index, last));
        }
    }
}
