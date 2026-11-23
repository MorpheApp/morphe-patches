/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class LayoutShiftRingTest {

    @Test
    public void onlyShiftsAboveTheRowAndAfterItsSequenceAreConsumed() {
        LayoutShiftRing ring = new LayoutShiftRing();
        ring.record(0, 10f);
        ring.record(2, 20f);
        ring.record(1, 5f);
        assertEquals(3L, ring.currentSeq());

        // Row 2 sees the shifts at rows 0 and 1, but not the one at its own index.
        assertEquals(15f, ring.consume(2, 0L), 0f);
        // Row 1 sees only the shift above it (row 0), not its own.
        assertEquals(10f, ring.consume(1, 0L), 0f);
        // A row that has already seen everything consumes nothing.
        assertEquals(0f, ring.consume(2, 3L), 0f);
    }

    @Test
    public void ringWrapsAfterThirtyTwoEntries() {
        LayoutShiftRing ring = new LayoutShiftRing();
        for (int i = 0; i < 33; i++) {
            ring.record(0, 1f);
        }
        assertEquals(33L, ring.currentSeq());
        // Oldest entry was overwritten, so at most 32 of the 33 shifts remain.
        assertEquals(32f, ring.consume(1, 0L), 0f);
    }
}
