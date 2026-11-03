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
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.SwipeDetector;
import app.morphe.extension.youtube.swipecontrols.controller.gesture.core.SwipeDetector.SwipeDirection;
import app.morphe.extension.youtube.swipecontrols.misc.SwipeControlsUtils;

public class SwipeSeekControllerTest {
    @Test public void formatsDurationsAndSignedFineSeekAmounts() {
        assertEquals("00:00", SwipeControlsUtils.formatTime(-1));
        assertEquals("01:30", SwipeControlsUtils.formatTime(90000));
        assertEquals("02:15:30", SwipeControlsUtils.formatTime(8130000));
        assertEquals("+5", SwipeControlsUtils.formatSeekSeconds(5000));
        assertEquals("-15", SwipeControlsUtils.formatSeekSeconds(-15000));
        assertEquals("+0.5", SwipeControlsUtils.formatSeekSeconds(500));
        assertEquals("-0.5", SwipeControlsUtils.formatSeekSeconds(-500));
    }

    @Test public void matchesNextPlayerLinearSensitivityForFiveTenAndFifteenSeconds() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 40000, 120000, 0);
        session.update(100, 1f, 50);
        assertEquals(5000, session.getOffset());
        session.update(200, 1f, 50);
        assertEquals(10000, session.getOffset());
        session.update(300, 1f, 50);
        assertEquals(15000, session.finish(false, "video") - 40000);
    }

    @Test public void adaptsPixelMovementToEachDisplayDensity() {
        for (float density : new float[]{0.75f, 1f, 2f, 2.75f, 3.5f}) {
            SwipeSeekSession session = new SwipeSeekSession();
            session.begin("video", 40000, 120000, 20);
            session.update(20 + 100 * density, density, 50);
            assertEquals("density=" + density, 5000, session.getOffset());
        }
    }

    @Test public void fineSeeksAreContinuousAndCanReverse() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 40000, 120000, 300);
        session.update(373, 1f, 50);
        assertEquals(3650, session.getOffset());
        session.update(227, 1f, 50);
        assertEquals(-3650, session.getOffset());
        assertEquals(36350, session.finish(false, "video"));
    }

    @Test public void startsAtActivationWithoutCountingTouchSlop() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 40000, 120000, 240);
        session.update(240, 3f, 50);
        assertEquals(0, session.getOffset());
        session.update(540, 3f, 50);
        assertEquals(5000, session.getOffset());
    }

    @Test public void clampsTargetAndDisplayedOffsetAtBothEnds() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 5000, 120000, 0);
        session.update(-400, 1f, 50);
        assertEquals(0, session.getTarget());
        assertEquals(-5000, session.getOffset());
        session.begin("video", 115000, 120000, 0);
        session.update(400, 1f, 50);
        assertEquals(120000, session.getTarget());
        assertEquals(5000, session.getOffset());
    }

    @Test public void cancellationResetsBeforeTheNextGesture() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 10000, 120000, 20);
        session.update(120, 1f, 50);
        assertEquals(-1, session.finish(true, "video"));
        assertFalse(session.isActive());
        session.begin("video", 60000, 120000, 1000);
        session.update(1100, 1f, 50);
        assertEquals(65000, session.finish(false, "video"));
    }

    @Test public void rejectsVideoChangesAndInvalidPlayback() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("old", 10000, 120000, 0);
        session.update(100, 1f, 50);
        assertEquals(-1, session.finish(false, "new"));
        session.begin("video", -1, 120000, 0);
        assertFalse(session.isActive());
        session.begin("video", 0, 0, 0);
        assertFalse(session.isActive());
        session.begin("", 10000, 120000, 0);
        assertFalse(session.isActive());
        session.begin(null, 10000, 120000, 0);
        assertFalse(session.isActive());
        session.begin("video", 10000, 120000, Float.NaN);
        assertFalse(session.isActive());
    }

    @Test public void detectsVideoChangesDuringAnActiveGesture() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("old", 10000, 120000, 0);
        assertTrue(session.isCurrentVideo("old"));
        assertFalse(session.isCurrentVideo("new"));
        assertFalse(session.isCurrentVideo(null));
        session.finish(true, "new");
        assertFalse(session.isCurrentVideo("old"));
    }

    @Test public void clampsImportedSensitivityAndIgnoresInvalidGeometry() {
        SwipeSeekSession session = new SwipeSeekSession();
        session.begin("video", 5000, 500000, 0);
        session.update(100, 1f, 1000);
        assertEquals(25000, session.getTarget());
        session.update(-100, 1f, -1);
        assertEquals(4000, session.getTarget());
        session.update(Float.NaN, 1f, 50);
        session.update(100, 0, 50);
        session.update(100, Float.NaN, 50);
        assertEquals(4000, session.getTarget());
    }

    @Test public void deviceTouchSlopOnlyChangesHorizontalSeekActivation() {
        SwipeDetector detector = new SwipeDetector(30);
        detector.submitDisplacement(9, 0, 16);
        assertEquals(SwipeDirection.NONE, detector.getCurrentSwipe());
        detector.submitDisplacement(17, 0, 16);
        assertEquals(SwipeDirection.HORIZONTAL, detector.getCurrentSwipe());
        detector.resetSwipe();
        detector.submitDisplacement(0, 17, 16);
        assertEquals(SwipeDirection.NONE, detector.getCurrentSwipe());
        detector.submitDisplacement(0, 31, 16);
        assertEquals(SwipeDirection.VERTICAL, detector.getCurrentSwipe());
    }
}
