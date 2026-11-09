/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import java.util.HashMap;
import java.util.Map;

/** Spring curves of the lyrics scroll, and the delay each line moves with. */
final class SpringEasing {

    static final Interpolator SPRING_SCROLL_EASING =
            new PathInterpolator(0.41f, 0f, 0.12f, 0.99f);

    private static final float DEFAULT_ZETA = 0.78f;
    /** Damping between lines that follow closely, so a quick run of lines does not bounce. */
    private static final float ZETA_TIGHT = 0.85f;
    /** Damping between lines with a pause of {@link #MAX_DYNAMIC_GAP_MS} or more. */
    private static final float ZETA_WIDE = 0.73f;
    private static final float MAX_DYNAMIC_GAP_MS = 2500f;
    /** How long the settle after the overshoot takes, relative to the rise. */
    private static final float SETTLE_RATIO = 1.5f;
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
        if (timeGapMs <= 0) {
            return DEFAULT_ZETA;
        }
        final float t = Math.min(1f, timeGapMs / MAX_DYNAMIC_GAP_MS);
        return ZETA_TIGHT - t * (ZETA_TIGHT - ZETA_WIDE);
    }

    /** {@return a spring that rises in about this time, cached as most scrolls share one} */
    static LyricsSpring springFor(long durationMs, long timeGapMs) {
        final int durationKey = Math.max(10, (int) ((Math.max(1L, durationMs) + 5L) / 10L * 10L));
        final int zetaKey = Math.round(computeZeta(timeGapMs) * 1000f);
        final long key = ((long) durationKey << 16) | zetaKey;
        LyricsSpring spring = SPRING_CACHE.get(key);
        if (spring == null) {
            if (SPRING_CACHE.size() >= SPRING_CACHE_SIZE) {
                SPRING_CACHE.clear();
            }
            spring = new LyricsSpring(durationKey, zetaKey / 1000f);
            SPRING_CACHE.put(key, spring);
        }
        return spring;
    }

    /**
     * Rises along {@link #SPRING_SCROLL_EASING} to the first overshoot of a damped spring, then
     * settles back to 1.
     */
    static final class LyricsSpring {
        private final float peakMs;
        private final float tailMs;
        private final float peakValue;
        final float totalMs;

        LyricsSpring(float peakMs) {
            this(peakMs, DEFAULT_ZETA);
        }

        LyricsSpring(float peakMs, float zeta) {
            this.peakMs = Math.max(1f, peakMs);
            this.tailMs = this.peakMs * SETTLE_RATIO;
            this.totalMs = this.peakMs + this.tailMs;
            final double z = Math.max(0.01f, Math.min(0.99f, zeta));
            // The overshoot of an underdamped spring, which depends on the damping alone.
            this.peakValue = (float) (1.0 + Math.exp(-Math.PI * z / Math.sqrt(1.0 - z * z)));
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

        private final boolean scrollingDown;
        private final int referenceIndex;
        private final int staggerAfterIndex;
        final long durationMs;
        final float delayIncrementMs;
        /** Lyric lines among the rows before each row index, one entry longer than the rows. */
        private final int[] linesBefore;

        /**
         * @param staggerAfterIndex The last row that moves without delay when scrolling down,
         *                          such as the end of a group of lines sung together.
         */
        LyricsScrollStagger(boolean scrollingDown, int referenceIndex, int staggerAfterIndex,
                            long durationMs, float delayIncrementMs, int[] linesBefore) {
            this.scrollingDown = scrollingDown;
            this.referenceIndex = referenceIndex;
            this.staggerAfterIndex = Math.max(referenceIndex, staggerAfterIndex);
            this.durationMs = durationMs;
            this.delayIncrementMs = delayIncrementMs;
            this.linesBefore = linesBefore;
        }

        int steps(int index) {
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
