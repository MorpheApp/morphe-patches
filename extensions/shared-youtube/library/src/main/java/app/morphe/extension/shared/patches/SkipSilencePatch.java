/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3467
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;

import app.morphe.extension.shared.Utils;
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
     * In quiet audio, such as a talk recorded far from the microphone, the end of a word can stay below
     * the fixed threshold and is then cut as silence. Streams without 'Stable volume' formats (Android VR)
     * are not leveled, so the threshold follows the audio: about 28 dB below its recent peaks,
     * but always above the background noise, or pauses with noise are no longer found.
     * Audio at normal levels keeps the fixed threshold.
     */
    private static final int AUDIO_PEAK_TO_THRESHOLD_RATIO = 25;
    private static final int NOISE_FLOOR_TO_THRESHOLD_RATIO = 3;
    private static final short MINIMUM_SILENCE_THRESHOLD_LEVEL = 64;
    /**
     * Levels are measured in blocks of 20 ms of 48 kHz stereo audio, independent of the buffer size.
     * The peak falls 1 dB per second, so it follows the level of the whole audio and not of a quiet passage,
     * and recovers within a minute from a loud intro. The noise floor is the quietest block,
     * and rises 1 dB per second so it follows a noise that becomes louder.
     */
    private static final int AUDIO_BLOCK_BYTES = 960 * 2 * 2;
    private static final float AUDIO_PEAK_DECAY_PER_BLOCK = (float) Math.pow(10, -1.0 / 20 * 0.02);
    private static final float NOISE_FLOOR_RISE_PER_BLOCK = (float) Math.pow(10, 1.0 / 20 * 0.02);

    /**
     * Fields are only used by the audio playback thread.
     */
    private static float audioPeak;
    private static float noiseFloor = Float.MAX_VALUE;
    private static short currentSilenceThresholdLevel = SILENCE_THRESHOLD_LEVEL;
    @Nullable
    private static ByteBuffer lastAudioInput;
    private static int lastAudioInputPosition;
    private static int lastAudioInputLimit;

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

    /**
     * Injection point.
     * Called with each buffer of 16-bit little endian audio, before silence is searched in it.
     * A buffer is given again while it is not fully consumed, and is then not measured again.
     */
    public static void updateSilenceThresholdLevel(ByteBuffer input) {
        final int position = input.position();
        final int limit = input.limit();
        final boolean alreadyMeasured = input == lastAudioInput
                && limit == lastAudioInputLimit && position > lastAudioInputPosition;
        lastAudioInput = input;
        lastAudioInputPosition = position;
        lastAudioInputLimit = limit;
        if (alreadyMeasured) {
            return;
        }

        for (int blockStart = position; blockStart < limit; blockStart += AUDIO_BLOCK_BYTES) {
            final int blockEnd = Math.min(limit, blockStart + AUDIO_BLOCK_BYTES);
            int blockPeak = 0;
            for (int i = blockStart + 1; i < blockEnd; i += 2) {
                blockPeak = Math.max(blockPeak, Math.abs((input.get(i) << 8) | (input.get(i - 1) & 0xFF)));
            }

            audioPeak = Math.max(blockPeak, audioPeak * AUDIO_PEAK_DECAY_PER_BLOCK);
            noiseFloor = Math.min(blockPeak, noiseFloor * NOISE_FLOOR_RISE_PER_BLOCK);
        }

        final float threshold = Math.max(audioPeak / AUDIO_PEAK_TO_THRESHOLD_RATIO,
                noiseFloor * NOISE_FLOOR_TO_THRESHOLD_RATIO);
        currentSilenceThresholdLevel = (short) Utils.clamp((int) threshold,
                MINIMUM_SILENCE_THRESHOLD_LEVEL, SILENCE_THRESHOLD_LEVEL);
    }

    /**
     * Injection point.
     * Called when the audio is flushed, such as after seeking or when another video starts.
     */
    public static void resetSilenceThresholdLevel() {
        audioPeak = 0;
        noiseFloor = Float.MAX_VALUE;
        currentSilenceThresholdLevel = MINIMUM_SILENCE_THRESHOLD_LEVEL;
        lastAudioInput = null;
    }

    /**
     * Injection point.
     * Called for each audio sample that is compared to the threshold.
     */
    public static short adaptiveSilenceThresholdLevel(short original) {
        return (short) Math.min(original, currentSilenceThresholdLevel);
    }
}
