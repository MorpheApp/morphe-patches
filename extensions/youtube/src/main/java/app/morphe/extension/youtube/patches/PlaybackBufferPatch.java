/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3386
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PlaybackBufferPatch {

    public enum PlaybackBufferSize {
        DEFAULT(1),
        LOW(2),
        MEDIUM(4),
        MAXIMUM(8);

        private final int multiplier;

        PlaybackBufferSize(int multiplier) {
            this.multiplier = multiplier;
        }

        public int getMultiplier() {
            return multiplier;
        }
    }

    /**
     * Upper limit for the buffer memory, regardless of the selected size.
     */
    private static final int MAX_BYTE_LIMIT = 128 * 1024 * 1024;

    /**
     * Injection point.
     * <p>
     * Dividing the buffered duration is the same as multiplying the duration limits.
     */
    public static long scaleBufferedDurationUs(long bufferedUs) {
        try {
            int multiplier = Settings.PLAYBACK_BUFFER_SIZE.get().getMultiplier();
            return multiplier > 1 ? bufferedUs / multiplier : bufferedUs;
        } catch (Exception ex) {
            Logger.printException(() -> "scaleBufferedDurationUs failure", ex);
            return bufferedUs;
        }
    }

    /**
     * Injection point.
     */
    public static int scaleByteLimit(int bytes) {
        try {
            int multiplier = Settings.PLAYBACK_BUFFER_SIZE.get().getMultiplier();
            if (multiplier <= 1) {
                return bytes;
            }
            long scaled = (long) bytes * multiplier;
            return (int) Math.min(scaled, Math.max(bytes, MAX_BYTE_LIMIT));
        } catch (Exception ex) {
            Logger.printException(() -> "scaleByteLimit failure", ex);
            return bytes;
        }
    }
}
