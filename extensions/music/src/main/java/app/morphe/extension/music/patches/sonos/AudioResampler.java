/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4891
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.sonos;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Click-free linear interpolation resampler for stereo 16-bit PCM.
 * Seamlessly converts 44.1 kHz video-mode audio to 48.0 kHz native DAC clock.
 */
public final class AudioResampler {
    private short lastLeft = 0;
    private short lastRight = 0;
    private boolean hasLastSample = false;
    private double phase = 0.0;

    public synchronized void reset() {
        lastLeft = 0;
        lastRight = 0;
        hasLastSample = false;
        phase = 0.0;
    }

    public synchronized byte[] resampleStereo16(byte[] input, int length, int inRate, int outRate) {
        if (inRate == outRate || inRate <= 0 || outRate <= 0 || length < 4) {
            return (length == input.length) ? input : Arrays.copyOf(input, length);
        }

        final int inFrames = length / 4;
        final ByteBuffer inBuf = ByteBuffer.wrap(input, 0, length).order(ByteOrder.LITTLE_ENDIAN);
        final short[] inLeft = new short[inFrames];
        final short[] inRight = new short[inFrames];

        for (int i = 0; i < inFrames; i++) {
            inLeft[i] = inBuf.getShort();
            inRight[i] = inBuf.getShort();
        }

        final double ratio = (double) inRate / (double) outRate;
        final int estOutFrames = (int) Math.ceil((inFrames - phase) / ratio) + 16;
        final byte[] outBytes = new byte[estOutFrames * 4];
        final ByteBuffer outBuf = ByteBuffer.wrap(outBytes).order(ByteOrder.LITTLE_ENDIAN);

        int outCount = 0;
        double currentPhase = phase;

        while (true) {
            final int idx = (int) Math.floor(currentPhase);
            final double frac = currentPhase - idx;

            final short sL;
            final short sR;

            if (idx < 0) {
                final int pL = hasLastSample ? lastLeft : inLeft[0];
                final int pR = hasLastSample ? lastRight : inRight[0];
                final int nL = inLeft[0];
                final int nR = inRight[0];
                sL = (short) (pL + frac * (nL - pL));
                sR = (short) (pR + frac * (nR - pR));
            } else if (idx < inFrames - 1) {
                final int pL = inLeft[idx];
                final int pR = inRight[idx];
                final int nL = inLeft[idx + 1];
                final int nR = inRight[idx + 1];
                sL = (short) (pL + frac * (nL - pL));
                sR = (short) (pR + frac * (nR - pR));
            } else {
                break;
            }

            outBuf.putShort(sL);
            outBuf.putShort(sR);
            outCount++;

            currentPhase += ratio;
        }

        phase = currentPhase - inFrames;
        lastLeft = inLeft[inFrames - 1];
        lastRight = inRight[inFrames - 1];
        hasLastSample = true;

        final int totalBytes = outCount * 4;
        return (totalBytes == outBytes.length) ? outBytes : Arrays.copyOf(outBytes, totalBytes);
    }
}
