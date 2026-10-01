/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3386
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PlaybackBufferPatch {

    public enum PlaybackBufferSize {
        DEFAULT(1),
        LOW(2),
        MEDIUM(4),
        MAXIMUM(8);

        /**
         * Upper limit for the buffer memory, regardless of the selected size.
         */
        private static final int MAX_BYTE_LIMIT = 128 * 1024 * 1024;

        private final int multiplier;

        PlaybackBufferSize(int multiplier) {
            this.multiplier = multiplier;
        }

        public long scaleBufferedDurationUs(long bufferedUs) {
            if (this == DEFAULT) {
                return bufferedUs;
            }
            return bufferedUs / multiplier;
        }

        public int scaleByteLimit(int bytes) {
            if (this == DEFAULT) {
                return bytes;
            }

            final long scaled = (long) bytes * multiplier;
            return (int) Math.min(scaled, Math.max(bytes, MAX_BYTE_LIMIT));
        }
    }

    /**
     * Injection point.
     * <p>
     * Dividing the buffered duration is the same as multiplying the duration limits.
     */
    public static long scaleBufferedDurationUs(long bufferedUs) {
        return Settings.PLAYBACK_BUFFER_SIZE.get().scaleBufferedDurationUs(bufferedUs);
    }

    /**
     * Injection point.
     */
    public static int scaleByteLimit(int bytes) {
        return Settings.PLAYBACK_BUFFER_SIZE.get().scaleByteLimit(bytes);
    }
}
