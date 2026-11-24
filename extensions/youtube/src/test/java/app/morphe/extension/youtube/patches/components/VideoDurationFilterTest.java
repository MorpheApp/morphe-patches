/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5277
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.components;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class VideoDurationFilterTest {

    private static void assertSeconds(long expected, String text) {
        assertEquals(text, expected, VideoDurationFilter.parseDurationSeconds(text));
    }

    @Test
    public void parsesDurations() {
        assertSeconds(9, "0:09");
        assertSeconds(754, "12:34");
        assertSeconds(2741, "45:41");
        assertSeconds(10429, "2:53:49");
        assertSeconds(3723, "1:2:3");
        assertSeconds(2741, " 45:41 ");
    }

    @Test
    public void parsesNonLatinDigits() {
        assertSeconds(2741, "٤٥:٤١");
    }

    @Test
    public void ignoresTextsThatAreNotDurations() {
        assertSeconds(0, "");
        assertSeconds(0, "LIVE");
        assertSeconds(0, "Scheduled");
        assertSeconds(0, "249 episodes");
        assertSeconds(0, "45 minutes, 41 seconds");
        assertSeconds(0, ":");
        assertSeconds(0, "1:");
        assertSeconds(0, "1:23:45:67");
    }
}
