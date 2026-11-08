/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4566
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import android.animation.TimeInterpolator;
import android.view.animation.Interpolator;
import android.view.animation.PathInterpolator;

import androidx.annotation.NonNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public final class SpringEasing {

    public static final Interpolator SPRING_SCROLL_EASING =
            new PathInterpolator(0.41f, 0f, 0.12f, 0.99f);

    public static final float DEFAULT_ZETA = 0.78f;
    public static final float RELAX_ZETA = 0.80f;
    public static final float ZETA_TIGHT = 0.85f;
    public static final float ZETA_WIDE = 0.73f;
    public static final float MAX_DYNAMIC_GAP_MS = 2500f;
    public static final float DEFAULT_SETTLE_RATIO = 1.5f;

    private static final Map<String, LyricsSpring> SPRING_CACHE = new HashMap<>();

    /**
     * Computes dynamic damping ratio (zeta):
     * - Tighter damping (0.85) for short inter-line gaps so rapid lines don't bounce excessively
     * - Wider damping (0.73) for longer inter-line gaps up to 2500ms
     * - Adds +0.05 damping (clamped to 0.95) in Relax Scrolling mode
     */
    public static float computeZeta(long timeGapMs, boolean isRelaxMode) {
        float zeta = isRelaxMode ? RELAX_ZETA : DEFAULT_ZETA;
        if (timeGapMs > 0L) {
            float t = Math.max(0f, Math.min(1f, (float) timeGapMs / MAX_DYNAMIC_GAP_MS));
            zeta = ZETA_TIGHT - t * (ZETA_TIGHT - ZETA_WIDE);
            if (isRelaxMode) {
                zeta = Math.min(0.95f, zeta + 0.05f);
            }
        }
        return zeta;
    }

    @NonNull
    public static synchronized LyricsSpring springFor(long durationMs) {
        return springFor(durationMs, DEFAULT_ZETA, DEFAULT_SETTLE_RATIO);
    }

    @NonNull
    public static synchronized LyricsSpring springFor(long durationMs, long timeGapMs, boolean isRelaxMode) {
        return springFor(durationMs, computeZeta(timeGapMs, isRelaxMode), DEFAULT_SETTLE_RATIO);
    }

    @NonNull
    public static synchronized LyricsSpring springFor(long durationMs, float zeta, float settleRatio) {
        int durKey = Math.max(10, (int) (((Math.max(1L, durationMs) + 5L) / 10L) * 10L));
        int zetaKey = Math.round(zeta * 1000f);
        int ratioKey = Math.round(settleRatio * 100f);
        String cacheKey = durKey + ":" + zetaKey + ":" + ratioKey;
        LyricsSpring cached = SPRING_CACHE.get(cacheKey);
        if (cached == null) {
            if (SPRING_CACHE.size() > 128) {
                SPRING_CACHE.clear();
            }
            cached = new LyricsSpring((float) durKey, zetaKey / 1000f, ratioKey / 100f);
            SPRING_CACHE.put(cacheKey, cached);
        }
        return cached;
    }

    public static final class Result implements TimeInterpolator {
        public final float[] samples;
        public final int durationMs;

        Result(float[] s, int d) {
            this.samples = s;
            this.durationMs = d;
        }

        @Override
        public float getInterpolation(float input) {
            return interpolate(input);
        }

        public float interpolate(float input) {
            if (input <= 0f) return samples[0];
            if (input >= 1f) return samples[samples.length - 1];
            float x = input * (samples.length - 1);
            int i = (int) x;
            float f = x - i;
            return samples[i] + (samples[i + 1] - samples[i]) * f;
        }
    }

    public static final class LyricsSpring {
        public final float peakMs;
        public final float zeta;
        public final float settleRatio;
        public final float tailMs;
        public final float totalMs;
        public final float peakValue;

        public LyricsSpring(float peakMs, float zeta, float settleRatio) {
            this.peakMs = Math.max(1f, peakMs);
            this.zeta = Math.max(0.01f, Math.min(0.99f, zeta));
            this.settleRatio = Math.max(0.1f, settleRatio);
            this.tailMs = this.peakMs * this.settleRatio;
            this.totalMs = this.peakMs + this.tailMs;

            double peakS = this.peakMs / 1000.0;
            double omegaD = Math.PI / peakS;
            double omega0 = omegaD / Math.sqrt(1.0 - this.zeta * this.zeta);
            double decayRate = this.zeta * omega0;
            this.peakValue = (float) (1.0 + Math.exp(-decayRate * peakS));
        }

        public LyricsSpring(float peakMs) {
            this(peakMs, DEFAULT_ZETA, DEFAULT_SETTLE_RATIO);
        }

        public float value(float tMs) {
            if (tMs <= 0f) {
                return 0f;
            }
            if (tMs >= totalMs) {
                return 1f;
            }
            if (tMs < peakMs) {
                return peakValue * SPRING_SCROLL_EASING.getInterpolation(tMs / peakMs);
            } else {
                float u = (tMs - peakMs) / tailMs;
                return 1f + (peakValue - 1f) * 0.5f * (1f + (float) Math.cos(Math.PI * u));
            }
        }
    }

    public static final class LyricsScrollStagger {
        public static final int MAX_STAGGER_STEPS = 12;

        public final boolean scrollingDown;
        public final int referenceIndex;
        public final int staggerAfterIndex;
        public final long durationMs;
        public final float delayIncrementMs;
        public final boolean isRelaxMode;
        private final int[] linesBefore;

        public LyricsScrollStagger(
                boolean scrollingDown,
                int referenceIndex,
                long durationMs,
                float delayIncrementMs,
                @NonNull int[] linesBefore) {
            this(scrollingDown, referenceIndex, referenceIndex, durationMs, delayIncrementMs, false, linesBefore);
        }

        public LyricsScrollStagger(
                boolean scrollingDown,
                int referenceIndex,
                long durationMs,
                float delayIncrementMs,
                boolean isRelaxMode,
                @NonNull int[] linesBefore) {
            this(scrollingDown, referenceIndex, referenceIndex, durationMs, delayIncrementMs, isRelaxMode, linesBefore);
        }

        public LyricsScrollStagger(
                boolean scrollingDown,
                int referenceIndex,
                int staggerAfterIndex,
                long durationMs,
                float delayIncrementMs,
                boolean isRelaxMode,
                @NonNull int[] linesBefore) {
            this.scrollingDown = scrollingDown;
            this.referenceIndex = referenceIndex;
            this.staggerAfterIndex = Math.max(referenceIndex, staggerAfterIndex);
            this.durationMs = durationMs;
            // When scrolling up in Relax Scrolling mode, delay is halved
            this.delayIncrementMs = (!scrollingDown && isRelaxMode)
                    ? (delayIncrementMs * 0.5f)
                    : delayIncrementMs;
            this.isRelaxMode = isRelaxMode;
            this.linesBefore = linesBefore;
        }

        public LyricsScrollStagger(int anchorIndex, boolean scrollingDown, long delayIncrementMs) {
            this(scrollingDown, anchorIndex, anchorIndex, 400L, (float) Math.max(0L, delayIncrementMs), false, new int[0]);
        }

        public int steps(int index) {
            if (linesBefore.length == 0) {
                int ref = scrollingDown ? staggerAfterIndex : referenceIndex;
                int raw = scrollingDown ? (index - ref) : (ref - index);
                return Math.max(0, Math.min(MAX_STAGGER_STEPS, raw));
            }
            int last = linesBefore.length - 1;
            int steps;
            if (scrollingDown) {
                if (index <= staggerAfterIndex) {
                    steps = 0;
                } else {
                    int iClamped = Math.max(0, Math.min(index + 1, last));
                    int rClamped = Math.max(0, Math.min(staggerAfterIndex + 1, last));
                    steps = linesBefore[iClamped] - linesBefore[rClamped];
                }
            } else {
                if (index >= referenceIndex) {
                    steps = 0;
                } else {
                    int rClamped = Math.max(0, Math.min(referenceIndex + 1, last));
                    int iClamped = Math.max(0, Math.min(index + 1, last));
                    steps = linesBefore[rClamped] - linesBefore[iClamped];
                }
            }
            return Math.max(0, Math.min(MAX_STAGGER_STEPS, steps));
        }

        public int staggerOrderFor(int rowIndex) {
            return steps(rowIndex);
        }

        public long delayFor(int rowIndex) {
            return Math.round(steps(rowIndex) * delayIncrementMs);
        }
    }

    private static final Map<String, Result> CACHE = new LinkedHashMap<String, Result>(64, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Result> e) {
            return size() > 64;
        }
    };

    static double cubicBezierY(double t, double x1, double y1, double x2, double y2) {
        double cx = 3 * x1, bx = 3 * (x2 - x1) - cx, ax = 1 - cx - bx;
        double cy = 3 * y1, by = 3 * (y2 - y1) - cy, ay = 1 - cy - by;
        double u = t;
        for (int i = 0; i < 8; i++) {
            double x = ((ax * u + bx) * u + cx) * u - t;
            double dx = (3 * ax * u + 2 * bx) * u + cx;
            if (Math.abs(dx) < 1e-6) break;
            u -= x / dx;
        }
        return ((ay * u + by) * u + cy) * u;
    }

    public static synchronized Result get(double peakTimeMs, double zeta, double settleTailMs) {
        peakTimeMs = Math.round(peakTimeMs / 10.0) * 10.0;
        settleTailMs = Math.round(settleTailMs / 10.0) * 10.0;
        String key = peakTimeMs + ":" + zeta + ":" + settleTailMs;
        Result hit = CACHE.get(key);
        if (hit != null) return hit;

        double peakS = peakTimeMs / 1000.0, tailS = settleTailMs / 1000.0;
        double omegaD = Math.PI / peakS;
        double omega0 = omegaD / Math.sqrt(1.0 - zeta * zeta);
        double decayRate = zeta * omega0;
        double peakValue = 1.0 + Math.exp(-decayRate * peakS);

        double dt = 1.0 / 120.0;

        java.util.ArrayList<Float> pts = new java.util.ArrayList<>();
        for (double t = 0; t <= peakS; t += dt) {
            pts.add((float) (peakValue * cubicBezierY(t / peakS, 0.41, 0, 0.12, 0.99)));
        }
        for (double t = dt; t <= tailS; t += dt) {
            double u = t / tailS;
            pts.add((float) (1.0 + (peakValue - 1.0) * 0.5 * (1.0 + Math.cos(Math.PI * u))));
        }
        pts.add(1f);
        float[] arr = new float[pts.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = Math.round(pts.get(i) * 10000f) / 10000f;
        Result r = new Result(arr, (int) Math.round((peakS + tailS) * 1000));
        CACHE.put(key, r);
        return r;
    }

    private SpringEasing() {
    }
}
