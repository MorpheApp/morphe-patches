/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import android.media.AudioFormat;

import androidx.annotation.Nullable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Converts the PCM audio the app writes to its AudioTrack to the format of the stream,
 * so that the stream never changes format when the app does.
 */
final class PcmConverter {

    static final int OUTPUT_SAMPLE_RATE = 48000;
    static final int OUTPUT_CHANNELS = 2;
    static final int OUTPUT_BITS_PER_SAMPLE = 16;
    static final int OUTPUT_BYTES_PER_FRAME = OUTPUT_CHANNELS * OUTPUT_BITS_PER_SAMPLE / 8;
    static final int OUTPUT_BYTES_PER_SECOND = OUTPUT_SAMPLE_RATE * OUTPUT_BYTES_PER_FRAME;

    private static final float PCM_16BIT_SCALE = 32768f;
    private static final float PCM_24BIT_SCALE = 8388608f;
    private static final float PCM_32BIT_SCALE = 2147483648f;
    private static final float PCM_8BIT_OFFSET = 128f;
    private static final float PCM_8BIT_SCALE = 128f;

    private int sampleRate;
    /**
     * Read position of the next output frame. 0 is the first frame of the next buffer,
     * and -1 is the last frame of the previous buffer.
     */
    private double position;
    private float previousLeft;
    private float previousRight;
    private long ditherState = 0x123456789ABCDEFL;

    synchronized void reset() {
        sampleRate = 0;
    }

    /**
     * @return 48 kHz stereo 16-bit audio, or null if the format is not supported.
     */
    @Nullable
    synchronized byte[] convert(ByteBuffer input, int encoding, int channelCount, int inputSampleRate) {
        final int bytesPerSample = bytesPerSample(encoding);
        if (bytesPerSample == 0 || channelCount < 1 || channelCount > OUTPUT_CHANNELS || inputSampleRate <= 0) {
            return null;
        }

        input.order(ByteOrder.LITTLE_ENDIAN);
        final int frames = input.remaining() / (bytesPerSample * channelCount);
        if (frames == 0) {
            return new byte[0];
        }

        if (encoding == AudioFormat.ENCODING_PCM_16BIT && channelCount == OUTPUT_CHANNELS
                && inputSampleRate == OUTPUT_SAMPLE_RATE) {
            byte[] output = new byte[frames * OUTPUT_BYTES_PER_FRAME];
            input.get(output);
            return output;
        }

        if (inputSampleRate != sampleRate) {
            sampleRate = inputSampleRate;
            position = 0;
            previousLeft = 0;
            previousRight = 0;
        }

        final float[] left = new float[frames];
        final float[] right = new float[frames];
        for (int i = 0; i < frames; i++) {
            left[i] = readSample(input, encoding);
            right[i] = (channelCount == 2) ? readSample(input, encoding) : left[i];
        }

        // Linear interpolation, continued from the last frame of the previous buffer.
        final double step = (double) inputSampleRate / OUTPUT_SAMPLE_RATE;
        final int maxOutputFrames = (int) ((frames - position) / step) + 1;
        final ByteBuffer output = ByteBuffer.allocate(maxOutputFrames * OUTPUT_BYTES_PER_FRAME)
                .order(ByteOrder.LITTLE_ENDIAN);

        while (position < frames - 1 && output.remaining() >= OUTPUT_BYTES_PER_FRAME) {
            final int index = (int) Math.floor(position);
            final float fraction = (float) (position - index);
            final float fromLeft = (index < 0) ? previousLeft : left[index];
            final float fromRight = (index < 0) ? previousRight : right[index];
            output.putShort(toPcm16(fromLeft + fraction * (left[index + 1] - fromLeft)));
            output.putShort(toPcm16(fromRight + fraction * (right[index + 1] - fromRight)));
            position += step;
        }

        position -= frames;
        previousLeft = left[frames - 1];
        previousRight = right[frames - 1];

        byte[] result = new byte[output.position()];
        System.arraycopy(output.array(), 0, result, 0, result.length);
        return result;
    }

    private static int bytesPerSample(int encoding) {
        return switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT -> 1;
            case AudioFormat.ENCODING_PCM_16BIT -> 2;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3;
            case AudioFormat.ENCODING_PCM_32BIT, AudioFormat.ENCODING_PCM_FLOAT -> 4;
            default -> 0;
        };
    }

    private static float readSample(ByteBuffer input, int encoding) {
        return switch (encoding) {
            case AudioFormat.ENCODING_PCM_8BIT -> ((input.get() & 0xFF) - PCM_8BIT_OFFSET) / PCM_8BIT_SCALE;
            case AudioFormat.ENCODING_PCM_16BIT -> input.getShort() / PCM_16BIT_SCALE;
            case AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                final int low = input.get() & 0xFF;
                final int middle = input.get() & 0xFF;
                final int high = input.get(); // Sign extended.
                yield ((high << 16) | (middle << 8) | low) / PCM_24BIT_SCALE;
            }
            case AudioFormat.ENCODING_PCM_32BIT -> input.getInt() / PCM_32BIT_SCALE;
            default -> input.getFloat();
        };
    }

    /**
     * Quantizes a sample with triangular dither.
     */
    private short toPcm16(float sample) {
        final float dither = nextDither() - nextDither();
        final long value = Math.round(sample * (PCM_16BIT_SCALE - 1) + dither);
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    /**
     * @return Uniform noise in the range [-0.5, 0.5).
     */
    private float nextDither() {
        long x = ditherState;
        x ^= x << 13;
        x ^= x >>> 7;
        x ^= x << 17;
        ditherState = x;
        return (x & 0xFFFFFF) / 16777216f - 0.5f;
    }
}
