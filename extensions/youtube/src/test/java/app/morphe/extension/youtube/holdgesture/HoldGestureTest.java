/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.youtube.holdgesture.HoldGesture.Outcome;
import app.morphe.extension.youtube.holdgesture.HoldGesture.State;

public class HoldGestureTest {
    private static final float X = 200;
    private static final float Y = 100;
    private static final float STEP_DISTANCE = 26;
    /** Far from every edge of the screen. */
    private static final HoldGesture.Room WIDE = new HoldGesture.Room(2000, 2000, 2000);

    private static final class Recorder implements HoldGesture.Listener {
        final List<Float> speeds = new ArrayList<>();
        float lockProgress;
        boolean lockArmed;
        Outcome outcome;
        float finalSpeed;

        @Override
        public void onStateChanged(State previous, State current) {
        }

        @Override
        public void onSpeedChanged(float speed, boolean stepped) {
            speeds.add(speed);
        }

        @Override
        public void onLockProgress(float progress, boolean armed) {
            lockProgress = progress;
            lockArmed = armed;
        }

        @Override
        public void onFinished(Outcome outcome, float speed) {
            this.outcome = outcome;
            finalSpeed = speed;
        }
    }

    private final Recorder recorder = new Recorder();

    private HoldGesture gesture(boolean adjustEnabled, boolean lockEnabled, float step) {
        return new HoldGesture(new HoldGesture.Config(adjustEnabled, lockEnabled,
                step, 0.25f, 4.0f, STEP_DISTANCE), recorder);
    }

    private HoldGesture held(float holdSpeed, boolean locked) {
        HoldGesture gesture = gesture(true, true, 0.25f);
        gesture.begin(X, Y, WIDE, holdSpeed, locked);
        return gesture;
    }

    /** Moves into the speed adjustment and returns the x where it began. */
    private float startAdjusting(HoldGesture gesture) {
        float x = X + HoldGesture.ADJUST_ENTER + 1;
        gesture.move(x, Y);
        assertEquals(State.SPEED_ADJUST, gesture.state());
        return x;
    }

    @Test
    public void holdPlaysFastAndReleaseRestores() {
        HoldGesture gesture = held(2.0f, false);
        assertEquals(State.HOLD_SPEED, gesture.state());
        assertEquals(List.of(2.0f), recorder.speeds);

        gesture.release();
        assertEquals(Outcome.RESTORE, recorder.outcome);
        assertEquals(State.IDLE, gesture.state());
    }

    @Test
    public void holdWithoutMovingEndsALock() {
        HoldGesture gesture = held(2.5f, true);
        gesture.move(X + 5, Y + 5);
        gesture.release();
        assertEquals(Outcome.UNLOCK, recorder.outcome);
    }

    @Test
    public void sidewaysDragChangesSpeedInSteps() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        assertEquals("entering does not change the speed", 2.0f, gesture.speed(), 0);

        gesture.move(x + STEP_DISTANCE, Y);
        assertEquals(2.25f, gesture.speed(), 0);
        gesture.move(x + STEP_DISTANCE * 3, Y);
        assertEquals(2.75f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE * 2, Y);
        assertEquals(1.5f, gesture.speed(), 0);

        gesture.release();
        assertEquals(Outcome.RESTORE, recorder.outcome);
    }

    @Test
    public void adjustingALockedSpeedWithoutLockingKeepsTheOldLock() {
        HoldGesture gesture = held(2.0f, true);
        float x = startAdjusting(gesture);
        gesture.move(x + STEP_DISTANCE * 2, Y);
        gesture.release();
        assertEquals(Outcome.RESTORE, recorder.outcome);
    }

    @Test
    public void speedStopsAtTheEndsAndTurnsBackAtOnce() {
        HoldGesture gesture = held(3.5f, false);
        float x = startAdjusting(gesture);
        gesture.move(x + STEP_DISTANCE * 10, Y);
        assertEquals(4.0f, gesture.speed(), 0);

        gesture.move(x + STEP_DISTANCE * 9, Y);
        assertEquals(3.75f, gesture.speed(), 0);

        gesture.move(x - STEP_DISTANCE * 100, Y);
        assertEquals(0.25f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE * 99, Y);
        assertEquals(0.5f, gesture.speed(), 0);
    }

    @Test
    public void aStartSpeedBetweenStepsMovesToTheNextStep() {
        HoldGesture gesture = gesture(true, true, 0.5f);
        gesture.begin(X, Y, WIDE, 1.75f, false);
        float x = startAdjusting(gesture);

        gesture.move(x + STEP_DISTANCE, Y);
        assertEquals(2.0f, gesture.speed(), 0);
        gesture.move(x, Y);
        assertEquals(1.75f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE, Y);
        assertEquals(1.5f, gesture.speed(), 0);
    }

    @Test
    public void tenthStepsStayRound() {
        HoldGesture gesture = gesture(true, true, 0.1f);
        gesture.begin(X, Y, WIDE, 2.0f, false);
        float x = startAdjusting(gesture);

        gesture.move(x + STEP_DISTANCE * 3, Y);
        assertEquals(2.3f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE * 17, Y);
        assertEquals(0.3f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE * 18, Y);
        assertEquals(0.25f, gesture.speed(), 0);
    }

    @Test
    public void nearAnEdgeTheStepsShrinkSoThatTheSlowestSpeedIsReached() {
        HoldGesture gesture = gesture(true, true, 0.25f);
        float roomLeft = 110;
        gesture.begin(X, Y, new HoldGesture.Room(roomLeft, 2000, 2000), 2.0f, false);

        gesture.move(X - HoldGesture.ADJUST_ENTER - 1, Y);
        assertEquals(2.0f, gesture.speed(), 0);
        gesture.move(X - roomLeft + HoldGesture.SIDE_EDGE_MARGIN, Y);
        assertEquals(0.25f, gesture.speed(), 0);
    }

    @Test
    public void theSideWithRoomKeepsTheUsualStep() {
        HoldGesture gesture = gesture(true, true, 0.25f);
        gesture.begin(X, Y, new HoldGesture.Room(110, 2000, 2000), 2.0f, false);
        float x = startAdjusting(gesture);

        gesture.move(x + STEP_DISTANCE, Y);
        assertEquals(2.25f, gesture.speed(), 0);
        gesture.move(x + STEP_DISTANCE * 2, Y);
        assertEquals(2.5f, gesture.speed(), 0);
    }

    @Test
    public void stepsDoNotShrinkBelowWhatAFingerCanStopOn() {
        HoldGesture gesture = gesture(true, true, 0.25f);
        gesture.begin(X, Y, new HoldGesture.Room(30, 2000, 2000), 2.0f, false);
        float x = X - HoldGesture.ADJUST_ENTER - 1;
        gesture.move(x, Y);

        gesture.move(x - HoldGesture.STEP_DISTANCE_MIN, Y);
        assertEquals(1.75f, gesture.speed(), 0);
        gesture.move(x - HoldGesture.STEP_DISTANCE_MIN * 7, Y);
        assertEquals(0.25f, gesture.speed(), 0);
    }

    @Test
    public void pullDownLocksOnlyWhenArmed() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER + 1);
        assertEquals(State.LOCK_TARGET, gesture.state());
        assertFalse(recorder.lockArmed);

        gesture.move(X, Y + HoldGesture.LOCK_ENTER + HoldGesture.LOCK_TRAVEL / 2);
        assertEquals(0.5f, recorder.lockProgress, 0.01f);

        gesture.release();
        assertEquals(Outcome.RESTORE, recorder.outcome);
    }

    @Test
    public void pullDownToTheEndLocks() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER + HoldGesture.LOCK_TRAVEL);
        assertTrue(recorder.lockArmed);

        gesture.release();
        assertEquals(Outcome.LOCK, recorder.outcome);
        assertEquals(2.0f, recorder.finalSpeed, 0);
    }

    @Test
    public void armedLockDoesNotFlickerAtTheEdge() {
        HoldGesture gesture = held(2.0f, false);
        float armedAt = Y + HoldGesture.LOCK_ENTER + HoldGesture.LOCK_TRAVEL;
        gesture.move(X, armedAt);
        gesture.move(X, armedAt - 3);
        assertTrue(recorder.lockArmed);

        gesture.move(X, armedAt - HoldGesture.LOCK_DISARM - 1);
        assertFalse(recorder.lockArmed);
    }

    @Test
    public void adjustThenPullDownLocksTheAdjustedSpeed() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        gesture.move(x + STEP_DISTANCE * 2, Y);

        float down = Y + HoldGesture.LOCK_ENTER_FROM_ADJUST + 1;
        gesture.move(x + STEP_DISTANCE * 2, down);
        assertEquals(State.LOCK_TARGET, gesture.state());

        // Sideways drift on the way down must not change the speed.
        gesture.move(x + STEP_DISTANCE * 4, down + HoldGesture.LOCK_TRAVEL);
        assertEquals(2.5f, gesture.speed(), 0);

        gesture.release();
        assertEquals(Outcome.LOCK, recorder.outcome);
        assertEquals(2.5f, recorder.finalSpeed, 0);
    }

    @Test
    public void goingBackUpReturnsToAdjustingWithTheSameSpeed() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        gesture.move(x + STEP_DISTANCE * 2, Y);
        gesture.move(x + STEP_DISTANCE * 2, Y + HoldGesture.LOCK_ENTER_FROM_ADJUST + 1);

        float drifted = x + STEP_DISTANCE * 5;
        gesture.move(drifted, Y + HoldGesture.LOCK_ENTER_FROM_ADJUST - HoldGesture.LOCK_LEAVE - 1);
        assertEquals(State.SPEED_ADJUST, gesture.state());
        assertEquals(2.5f, gesture.speed(), 0);

        gesture.move(drifted + STEP_DISTANCE, Y);
        assertEquals(2.75f, gesture.speed(), 0);
    }

    @Test
    public void goingBackUpFromAPlainHoldReturnsToTheHold() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER + 10);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER - HoldGesture.LOCK_LEAVE - 1);
        assertEquals(State.HOLD_SPEED, gesture.state());
        assertEquals(0, recorder.lockProgress, 0);
    }

    @Test
    public void lockNeedsLessPullNearTheBottomOfTheScreen() {
        HoldGesture gesture = gesture(true, true, 0.25f);
        float roomBelow = HoldGesture.LOCK_ENTER + HoldGesture.LOCK_EDGE_MARGIN + 20;
        gesture.begin(X, Y, new HoldGesture.Room(2000, 2000, roomBelow), 2.0f, false);

        gesture.move(X, Y + HoldGesture.LOCK_ENTER + 20);
        assertTrue(recorder.lockArmed);
    }

    @Test
    public void disabledDirectionsDoNothing() {
        HoldGesture gesture = gesture(false, false, 0.25f);
        gesture.begin(X, Y, WIDE, 2.0f, false);

        gesture.move(X + 100, Y);
        assertEquals(State.HOLD_SPEED, gesture.state());
        gesture.move(X, Y + 100);
        assertEquals(State.HOLD_SPEED, gesture.state());
    }

    @Test
    public void upwardDragKeepsTheHold() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y - 80);
        assertEquals(State.HOLD_SPEED, gesture.state());
    }

    @Test
    public void aCanceledTouchNeverLocksOrUnlocks() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER + HoldGesture.LOCK_TRAVEL);
        gesture.cancel();
        assertEquals(Outcome.RESTORE, recorder.outcome);

        HoldGesture locked = held(2.0f, true);
        locked.cancel();
        assertEquals(Outcome.RESTORE, recorder.outcome);
    }
}
