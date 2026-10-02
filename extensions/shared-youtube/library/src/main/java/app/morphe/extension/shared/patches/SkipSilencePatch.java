/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3413
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public final class SkipSilencePatch {

    public enum MinimumPause {
        MS_500(500_000),
        MS_700(700_000),
        S_1(1_000_000),
        S_2(2_000_000),
        S_3(3_000_000),
        S_5(5_000_000),
        S_10(10_000_000);

        final long durationUs;

        MinimumPause(long durationUs) {
            this.durationUs = durationUs;
        }
    }

    /**
     * YouTube defaults: pauses of 0.1 seconds or more keep only 20% of their length, at a threshold of 1024.
     * That cuts nearly every natural pause, so the minimum pause length is a setting,
     * and 40% of a shortened pause is kept.
     */
    private static final float SILENCE_RETENTION_RATIO = 0.4f;
    private static final short SILENCE_THRESHOLD_LEVEL = 512;

    /**
     * Injection point.
     */
    public static boolean isSkipSilenceEnabled(boolean original) {
        return SharedYouTubeSettings.SKIP_SILENCE.get() || original;
    }

    /**
     * Injection point.
     */
    public static long minimumSilenceDurationUs(long original) {
        return Math.max(original, SharedYouTubeSettings.SKIP_SILENCE_MINIMUM_PAUSE.get().durationUs);
    }

    /**
     * Injection point.
     */
    public static float silenceRetentionRatio(float original) {
        return Math.max(original, SILENCE_RETENTION_RATIO);
    }

    /**
     * Injection point.
     */
    public static short silenceThresholdLevel(short original) {
        return (short) Math.min(original, SILENCE_THRESHOLD_LEVEL);
    }
}
