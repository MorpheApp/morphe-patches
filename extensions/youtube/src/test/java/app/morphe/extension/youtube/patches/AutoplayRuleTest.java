/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4659
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.youtube.patches.AutoplayRule.Mode;

public class AutoplayRuleTest {
    private static final String PLAYLIST = "PLabcdefghijk";
    private static final String MIX = "RDabcdefghijk";
    private static final String OTHER_MIX = "RDlmnopqrstuv";

    private final AutoplayRule rule = new AutoplayRule();

    private boolean autoplay(String listId, Mode mode, int stopAfter) {
        return rule.shouldSkip("AUTOPLAY", listId, mode, stopAfter);
    }

    @Test
    public void suggestedVideoNeverPlays() {
        for (Mode mode : Mode.values()) {
            assertTrue(rule.shouldSkip("AUTONAV", PLAYLIST, mode, 0));
            assertTrue(rule.shouldSkip("AUTONAV", null, mode, 0));
        }
    }

    @Test
    public void userNavigationIsNeverStopped() {
        for (String intent : new String[]{"NEXT", "PREVIOUS", "JUMP"}) {
            assertFalse(rule.shouldSkip(intent, MIX, Mode.OFF, 1));
        }
    }

    @Test
    public void playlistGoesOnUnlessModeOff() {
        rule.videoLoaded(PLAYLIST, "video1");
        assertFalse(autoplay(PLAYLIST, Mode.AUTO, 1));
        assertFalse(autoplay(PLAYLIST, Mode.SINGLE, 1));
        assertTrue(autoplay(PLAYLIST, Mode.OFF, 1));
        assertEquals(0, rule.mixCount());
    }

    @Test
    public void mixStopsAfterLimit() {
        rule.videoLoaded(MIX, "video1");
        assertFalse(autoplay(MIX, Mode.AUTO, 3));
        rule.videoLoaded(MIX, "video2");
        assertFalse(autoplay(MIX, Mode.AUTO, 3));
        rule.videoLoaded(MIX, "video3");
        assertEquals(3, rule.mixCount());
        assertTrue(autoplay(MIX, Mode.AUTO, 3));
        // The Mix ended, so playing it again starts a new count.
        assertEquals(0, rule.mixCount());
        rule.videoLoaded(MIX, "video4");
        assertEquals(1, rule.mixCount());
    }

    @Test
    public void mixWithNoLimitGoesOn() {
        for (int i = 1; i <= 50; i++) {
            rule.videoLoaded(MIX, "video" + i);
            assertFalse(autoplay(MIX, Mode.AUTO, 0));
        }
    }

    @Test
    public void singleModeStopsMixAfterOneVideo() {
        rule.videoLoaded(MIX, "video1");
        assertTrue(autoplay(MIX, Mode.SINGLE, 10));
    }

    @Test
    public void sameVideoCountsOnce() {
        rule.videoLoaded(MIX, "video1");
        rule.videoLoaded(MIX, "video1");
        rule.videoLoaded(MIX, "video1");
        assertEquals(1, rule.mixCount());
    }

    @Test
    public void userPickRestartsCount() {
        rule.videoLoaded(MIX, "video1");
        rule.videoLoaded(MIX, "video2");
        assertFalse(rule.shouldSkip("NEXT", MIX, Mode.AUTO, 3));
        rule.videoLoaded(MIX, "video3");
        assertEquals(1, rule.mixCount());
    }

    @Test
    public void otherMixRestartsCount() {
        rule.videoLoaded(MIX, "video1");
        rule.videoLoaded(MIX, "video2");
        rule.videoLoaded(OTHER_MIX, "video3");
        assertEquals(1, rule.mixCount());
        assertFalse(autoplay(OTHER_MIX, Mode.AUTO, 2));
    }

    @Test
    public void leavingMixClearsCount() {
        rule.videoLoaded(MIX, "video1");
        rule.videoLoaded(PLAYLIST, "video2");
        assertEquals(0, rule.mixCount());
    }
}
