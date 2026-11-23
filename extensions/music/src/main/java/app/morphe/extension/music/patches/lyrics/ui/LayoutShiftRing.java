/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

/**
 * Ring of recent layout shifts, keyed by a monotonic sequence number so a row can consume exactly
 * the shifts that happened above it since it was last placed. Pure holder, no view dependencies.
 */
final class LayoutShiftRing {

    private static final int SHIFT_RING = 32;

    private final int[] shiftIndex = new int[SHIFT_RING];
    private final float[] shiftDelta = new float[SHIFT_RING];
    private final long[] shiftSeqs = new long[SHIFT_RING];
    private long shiftSeq;

    long currentSeq() {
        return shiftSeq;
    }

    void record(int rowIndex, float delta) {
        final long seq = ++shiftSeq;
        final int slot = (int) (seq % SHIFT_RING);
        shiftIndex[slot] = rowIndex;
        shiftDelta[slot] = delta;
        shiftSeqs[slot] = seq;
    }

    /** Sums the shifts above {@code index} recorded after the row's last observed sequence. */
    float consume(int index, long seenShiftSeq) {
        float sum = 0f;
        for (int i = 0; i < SHIFT_RING; i++) {
            if (shiftSeqs[i] > seenShiftSeq && shiftIndex[i] < index) {
                sum += shiftDelta[i];
            }
        }
        return sum;
    }
}
