/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols;

import static org.junit.Assert.*;

import org.junit.Test;
import app.morphe.extension.youtube.swipecontrols.controller.SwipeSeekSession;
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsUtils;

public class SwipeSeekControllerTest {
    @Test public void formatsDurations() {
        assertEquals("00:00", SwipeControlsUtils.formatTime(-1));
        assertEquals("01:30", SwipeControlsUtils.formatTime(90000));
        assertEquals("02:15:30", SwipeControlsUtils.formatTime(8130000));
    }

    @Test public void computesForwardAndBackwardTargets() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 40000, 120000);
        session.update(500, 1000, 30);
        assertEquals(55000, session.getTarget());
        session.update(-500, 1000, 30);
        assertEquals(25000, session.finish(false, "video"));
        assertFalse(session.isActive());
    }

    @Test public void clampsTargetAndDisplayedOffsetAtBothEnds() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 5000, 120000);
        session.update(-1000, 1000, 30);
        assertEquals(0, session.getTarget());
        assertEquals(-5000, session.getOffset());
        session.begin("video", 115000, 120000);
        session.update(1000, 1000, 30);
        assertEquals(120000, session.getTarget());
        assertEquals(5000, session.getOffset());
    }

    @Test public void cancellationResetsBeforeTheNextGesture() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 10000, 120000);
        session.update(500, 1000, 30);
        assertEquals(-1, session.finish(true, "video"));
        assertFalse(session.isActive());
        session.begin("video", 60000, 120000);
        session.update(500, 1000, 30);
        assertEquals(75000, session.finish(false, "video"));
    }

    @Test public void rejectsVideoChangesAndInvalidPlayback() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("old", 10000, 120000);
        session.update(500, 1000, 30);
        assertEquals(-1, session.finish(false, "new"));
        session.begin("video", -1, 120000);
        assertFalse(session.isActive());
        session.begin("video", 0, 0);
        assertFalse(session.isActive());
        session.begin("", 10000, 120000);
        assertFalse(session.isActive());
        session.begin(null, 10000, 120000);
        assertFalse(session.isActive());
    }

    @Test public void detectsVideoChangesDuringAnActiveGesture() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("old", 10000, 120000);
        assertTrue(session.isCurrentVideo("old"));
        assertFalse(session.isCurrentVideo("new"));
        assertFalse(session.isCurrentVideo(null));
        session.finish(true, "new");
        assertFalse(session.isCurrentVideo("old"));
    }

    @Test public void clampsImportedSensitivityAndIgnoresInvalidGeometry() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 5000, 500000);
        session.update(1000, 1000, 1000);
        assertEquals(125000, session.getTarget());
        session.update(-1000, 1000, -1);
        assertEquals(4000, session.getTarget());
        session.update(Float.NaN, 1000, 30);
        session.update(1000, 0, 30);
        assertEquals(4000, session.getTarget());
    }
}
