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

import app.morphe.extension.youtube.holdgesture.HoldGesture.Adjust;
import app.morphe.extension.youtube.holdgesture.HoldGesture.Outcome;
import app.morphe.extension.youtube.holdgesture.HoldGesture.State;

public class HoldGestureTest {
    private static final float X = 200;
    private static final float Y = 100;
    private static final float STEP_DISTANCE = 18;
    /** Far from every edge of the screen. */
    private static final HoldGesture.Room WIDE = new HoldGesture.Room(2000, 2000, 2000);

    private static final class Recorder implements HoldGesture.Listener {
        final List<Float> speeds = new ArrayList<>();
        int throttleDirection;
        int neutralMoves;
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
        public void onAdjustChanged(Adjust adjust, int direction) {
            throttleDirection = direction;
        }

        @Override
        public void onNeutralMoved() {
            neutralMoves++;
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

    /** Moves into the speed adjustment and returns the x where it began, the neutral point. */
    private float startAdjusting(HoldGesture gesture) {
        float x = X + HoldGesture.ADJUST_ENTER + 1;
        gesture.move(x, Y, 0);
        assertEquals(State.SPEED_ADJUST, gesture.state());
        return x;
    }

    /** Pushes out until the speed has changed by itself once, then pulls back far enough to stop that. */
    private float throttleAndPullBack(HoldGesture gesture) {
        float x = startAdjusting(gesture);
        gesture.move(x + 60, Y, 1000);
        gesture.tick(1000 + HoldGesture.THROTTLE_INTERVAL_MS[0]);
        assertEquals(3.0f, gesture.speed(), 0);

        gesture.move(x + 60 - HoldGesture.CLUTCH_PULL_BACK + 1, Y, 1220);
        assertEquals("not yet far enough back", Adjust.THROTTLE, gesture.adjust());
        gesture.move(x + 60 - HoldGesture.CLUTCH_PULL_BACK, Y, 1230);
        assertEquals(Adjust.COASTING, gesture.adjust());
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
        gesture.move(X + 5, Y + 5, 0);
        gesture.release();
        assertEquals(Outcome.UNLOCK, recorder.outcome);
    }

    @Test
    public void sidewaysDragChangesSpeedInSteps() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        assertEquals("entering does not change the speed", 2.0f, gesture.speed(), 0);

        gesture.move(x + STEP_DISTANCE, Y, 0);
        assertEquals(2.25f, gesture.speed(), 0);
        gesture.move(x + STEP_DISTANCE * 2, Y, 0);
        assertEquals(2.5f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE * 2, Y, 0);
        assertEquals(1.5f, gesture.speed(), 0);

        gesture.release();
        assertEquals(Outcome.RESTORE, recorder.outcome);
    }

    @Test
    public void adjustingALockedSpeedWithoutLockingKeepsTheOldLock() {
        HoldGesture gesture = held(2.0f, true);
        float x = startAdjusting(gesture);
        gesture.move(x + STEP_DISTANCE * 2, Y, 0);
        gesture.release();
        assertEquals(Outcome.RESTORE, recorder.outcome);
    }

    @Test
    public void speedStopsAtTheEndsAndTurnsBackAtOnce() {
        HoldGesture fast = held(3.75f, false);
        float x = startAdjusting(fast);
        fast.move(x + 40, Y, 0);
        assertEquals(4.0f, fast.speed(), 0);
        fast.move(x + 40 - STEP_DISTANCE, Y, 0);
        assertEquals(3.75f, fast.speed(), 0);

        HoldGesture slow = held(0.5f, false);
        x = startAdjusting(slow);
        slow.move(x - 40, Y, 0);
        assertEquals(0.25f, slow.speed(), 0);
        slow.move(x - 40 + STEP_DISTANCE, Y, 0);
        assertEquals(0.5f, slow.speed(), 0);
    }

    @Test
    public void aStartSpeedBetweenStepsMovesToTheNextStep() {
        HoldGesture gesture = gesture(true, true, 0.5f);
        gesture.begin(X, Y, WIDE, 1.75f, false);
        float x = startAdjusting(gesture);

        gesture.move(x + STEP_DISTANCE, Y, 0);
        assertEquals(2.0f, gesture.speed(), 0);
        gesture.move(x, Y, 0);
        assertEquals(1.75f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE, Y, 0);
        assertEquals(1.5f, gesture.speed(), 0);
    }

    @Test
    public void tenthStepsStayRoundDownToTheSlowestSpeed() {
        HoldGesture gesture = gesture(true, true, 0.1f);
        gesture.begin(X, Y, WIDE, 2.0f, false);
        float x = startAdjusting(gesture);

        gesture.move(x + STEP_DISTANCE * 2, Y, 0);
        assertEquals(2.2f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE * 2, Y, 0);
        assertEquals(1.8f, gesture.speed(), 0);

        gesture.move(x - 100, Y, 1000);
        for (long now = 1000; now < 3000; now += 16) {
            gesture.tick(now);
        }
        assertEquals(0.25f, gesture.speed(), 0);
        assertTrue("the last step is the one from 0.3", recorder.speeds.contains(0.3f));
    }

    @Test
    public void twentiethStepsStayRound() {
        HoldGesture gesture = gesture(true, true, 0.05f);
        gesture.begin(X, Y, WIDE, 1.0f, false);
        float x = startAdjusting(gesture);

        gesture.move(x + STEP_DISTANCE, Y, 0);
        assertEquals(1.05f, gesture.speed(), 0);
        gesture.move(x + STEP_DISTANCE * 2, Y, 0);
        assertEquals(1.1f, gesture.speed(), 0);
        gesture.move(x - STEP_DISTANCE * 2, Y, 0);
        assertEquals(0.9f, gesture.speed(), 0);
    }

    @Test
    public void farFromTheNeutralPointTheSpeedKeepsChanging() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);

        gesture.move(x + HoldGesture.THROTTLE_ENTER + 1, Y, 1000);
        assertEquals("the steps of the way there", 2.75f, gesture.speed(), 0);
        assertEquals(Adjust.THROTTLE, gesture.adjust());
        assertEquals(1, recorder.throttleDirection);

        gesture.tick(1209);
        assertEquals(2.75f, gesture.speed(), 0);
        gesture.tick(1210);
        assertEquals(3.0f, gesture.speed(), 0);
        gesture.tick(1420);
        assertEquals(3.25f, gesture.speed(), 0);
    }

    @Test
    public void fartherOutThePaceIsFaster() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        gesture.move(x + HoldGesture.THROTTLE_ENTER + 1, Y, 1000);

        gesture.move(x + HoldGesture.THROTTLE_ENTER + HoldGesture.THROTTLE_SECOND + 1, Y, 1010);
        gesture.tick(1087);
        assertEquals(2.75f, gesture.speed(), 0);
        gesture.tick(1088);
        assertEquals(3.0f, gesture.speed(), 0);

        gesture.move(x + HoldGesture.THROTTLE_ENTER + HoldGesture.THROTTLE_THIRD + 1, Y, 1100);
        gesture.tick(1129);
        assertEquals(3.0f, gesture.speed(), 0);
        gesture.tick(1130);
        assertEquals(3.25f, gesture.speed(), 0);
    }

    @Test
    public void smallStepsGoSeveralAtATimeAtTheThirdPace() {
        HoldGesture gesture = gesture(true, true, 0.05f);
        gesture.begin(X, Y, WIDE, 1.0f, false);
        float x = startAdjusting(gesture);

        gesture.move(x + 100, Y, 1000);
        assertEquals(1.15f, gesture.speed(), 0);
        gesture.tick(1042);
        assertEquals(1.35f, gesture.speed(), 0);
    }

    @Test
    public void theThrottleStopsAtTheEndOfTheRangeAndCanBeLockedThere() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        gesture.move(x + 100, Y, 1000);
        for (long now = 1000; now < 2000; now += 16) {
            gesture.tick(now);
        }
        assertEquals(4.0f, gesture.speed(), 0);

        gesture.move(x + 100, Y + HoldGesture.LOCK_ENTER_FROM_ADJUST + 1, 2100);
        assertEquals(State.LOCK_TARGET, gesture.state());
        assertEquals(Adjust.PRECISE, gesture.adjust());
        gesture.move(x + 100, Y + HoldGesture.LOCK_ENTER_FROM_ADJUST + HoldGesture.LOCK_TRAVEL, 2200);
        gesture.release();
        assertEquals(Outcome.LOCK, recorder.outcome);
        assertEquals(4.0f, recorder.finalSpeed, 0);
    }

    @Test
    public void outAndBackBeforeTheFirstStepIsAPlainDrag() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        gesture.move(x + 50, Y, 1000);
        assertEquals(Adjust.THROTTLE, gesture.adjust());

        gesture.move(x + STEP_DISTANCE * 2, Y, 1100);
        assertEquals(Adjust.PRECISE, gesture.adjust());
        assertEquals(2.5f, gesture.speed(), 0);
        assertEquals(0, recorder.neutralMoves);
    }

    @Test
    public void pullingBackKeepsTheSpeedAllTheWayToWhereThePressBegan() {
        HoldGesture gesture = held(2.0f, false);
        float x = throttleAndPullBack(gesture);

        // The press began past the point where the drag began.
        long now = 1230;
        for (float at = x + 40; at > X; at -= 10) {
            now += 30;
            gesture.move(at, Y, now);
            gesture.tick(now + 15);
        }
        assertEquals(Adjust.COASTING, gesture.adjust());
        assertEquals(3.0f, gesture.speed(), 0);
        assertEquals(0, recorder.neutralMoves);

        gesture.move(X, Y, now + 30);
        assertEquals(Adjust.PRECISE, gesture.adjust());
        assertEquals(1, recorder.neutralMoves);
        assertEquals(3.0f, gesture.speed(), 0);

        gesture.move(X + STEP_DISTANCE, Y, now + 400);
        assertEquals(3.25f, gesture.speed(), 0);
    }

    @Test
    public void goingOnPastWhereThePressBeganChangesTheSpeedTheOtherWayWithoutARest() {
        HoldGesture gesture = held(2.0f, false);
        float x = throttleAndPullBack(gesture);

        gesture.move(x + 20, Y, 1260);
        gesture.move(X - STEP_DISTANCE, Y, 1290);
        assertEquals(Adjust.PRECISE, gesture.adjust());
        assertEquals(2.75f, gesture.speed(), 0);
        gesture.move(X - STEP_DISTANCE * 2, Y, 1320);
        assertEquals(2.5f, gesture.speed(), 0);
    }

    @Test
    public void afterARestTheWayBackEndsAtThatRest() {
        HoldGesture gesture = held(2.0f, false);
        float x = throttleAndPullBack(gesture);
        gesture.tick(1230 + HoldGesture.NEUTRAL_REST_MS);
        float rest = x + 60 - HoldGesture.CLUTCH_PULL_BACK;

        // The next throttle from there comes back to there, and not to where the press began.
        gesture.move(rest + HoldGesture.THROTTLE_ENTER + 5, Y, 2000);
        gesture.tick(2000 + HoldGesture.THROTTLE_INTERVAL_MS[0]);
        float speed = gesture.speed();
        gesture.move(rest + HoldGesture.THROTTLE_ENTER + 5 - HoldGesture.CLUTCH_PULL_BACK, Y, 2220);
        assertEquals(Adjust.COASTING, gesture.adjust());

        gesture.move(X + 30, Y, 2250);
        assertEquals("still on the way to where the press began", Adjust.COASTING, gesture.adjust());
        assertEquals(speed, gesture.speed(), 0);
    }

    @Test
    public void turningAroundOnTheWayBackGoesOnFromThere() {
        HoldGesture gesture = held(2.0f, false);
        float x = throttleAndPullBack(gesture);

        gesture.move(x + 20, Y, 1300);
        gesture.move(x + 20 + HoldGesture.CLUTCH_PULL_BACK - 1, Y, 1340);
        assertEquals(Adjust.COASTING, gesture.adjust());
        gesture.move(x + 20 + HoldGesture.CLUTCH_PULL_BACK, Y, 1350);
        assertEquals(Adjust.PRECISE, gesture.adjust());
        assertEquals(1, recorder.neutralMoves);
        assertEquals(3.25f, gesture.speed(), 0);

        gesture.move(x + 20, Y, 1400);
        assertEquals(3.0f, gesture.speed(), 0);
    }

    @Test
    public void restingOnTheWayBackMakesThatPlaceTheNeutralPoint() {
        HoldGesture gesture = held(2.0f, false);
        float x = throttleAndPullBack(gesture);
        float rest = x + 60 - HoldGesture.CLUTCH_PULL_BACK;

        gesture.tick(1230 + HoldGesture.NEUTRAL_REST_MS - 1);
        assertEquals(Adjust.COASTING, gesture.adjust());
        gesture.tick(1230 + HoldGesture.NEUTRAL_REST_MS);
        assertEquals(Adjust.PRECISE, gesture.adjust());
        assertEquals(1, recorder.neutralMoves);

        gesture.move(rest - STEP_DISTANCE, Y, 1500);
        assertEquals(2.75f, gesture.speed(), 0);
    }

    @Test
    public void aShakingFingerCountsAsResting() {
        HoldGesture gesture = held(2.0f, false);
        float x = throttleAndPullBack(gesture);
        float rest = x + 60 - HoldGesture.CLUTCH_PULL_BACK;

        gesture.move(rest - HoldGesture.REST_SLOP, Y, 1300);
        gesture.move(rest, Y, 1230 + HoldGesture.NEUTRAL_REST_MS);
        assertEquals(Adjust.PRECISE, gesture.adjust());
        assertEquals(3.0f, gesture.speed(), 0);
    }

    @Test
    public void pushingOutAgainGoesOn() {
        HoldGesture gesture = held(2.0f, false);
        float x = throttleAndPullBack(gesture);

        gesture.move(x + 61, Y, 1300);
        assertEquals(Adjust.THROTTLE, gesture.adjust());
        gesture.tick(1300 + HoldGesture.THROTTLE_INTERVAL_MS[0]);
        assertEquals(3.25f, gesture.speed(), 0);
    }

    @Test
    public void nearAnEdgeOfTheScreenTheThrottleBeginsEarlier() {
        HoldGesture gesture = gesture(true, true, 0.25f);
        gesture.begin(X, Y, new HoldGesture.Room(2000, 62, 2000), 2.0f, false);
        float x = startAdjusting(gesture);

        // 39 dp are left to the edge of the screen.
        gesture.move(x + 32, Y, 1000);
        assertEquals(Adjust.THROTTLE, gesture.adjust());
        assertEquals(2.25f, gesture.speed(), 0);
        gesture.tick(1000 + HoldGesture.THROTTLE_INTERVAL_MS[2]);
        assertEquals("the third pace is within reach", 2.5f, gesture.speed(), 0);
    }

    @Test
    public void theSideWithRoomKeepsTheUsualDistance() {
        HoldGesture gesture = gesture(true, true, 0.25f);
        gesture.begin(X, Y, new HoldGesture.Room(2000, 62, 2000), 2.0f, false);
        float x = startAdjusting(gesture);

        gesture.move(x - HoldGesture.THROTTLE_ENTER, Y, 1000);
        assertEquals(Adjust.PRECISE, gesture.adjust());
        gesture.move(x - HoldGesture.THROTTLE_ENTER - 1, Y, 1010);
        assertEquals(Adjust.THROTTLE, gesture.adjust());
        assertEquals(-1, recorder.throttleDirection);
    }

    @Test
    public void timeAloneDoesNothingOutsideOfASidewaysDrag() {
        HoldGesture gesture = held(2.0f, false);
        gesture.tick(5000);
        assertEquals(State.HOLD_SPEED, gesture.state());
        assertEquals(List.of(2.0f), recorder.speeds);
    }

    @Test
    public void pullDownLocksOnlyWhenArmed() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER + 1, 0);
        assertEquals(State.LOCK_TARGET, gesture.state());
        assertFalse(recorder.lockArmed);

        gesture.move(X, Y + HoldGesture.LOCK_ENTER + HoldGesture.LOCK_TRAVEL / 2, 0);
        assertEquals(0.5f, recorder.lockProgress, 0.01f);

        gesture.release();
        assertEquals(Outcome.RESTORE, recorder.outcome);
    }

    @Test
    public void pullDownToTheEndLocks() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER + HoldGesture.LOCK_TRAVEL, 0);
        assertTrue(recorder.lockArmed);

        gesture.release();
        assertEquals(Outcome.LOCK, recorder.outcome);
        assertEquals(2.0f, recorder.finalSpeed, 0);
    }

    @Test
    public void armedLockDoesNotFlickerAtTheEdge() {
        HoldGesture gesture = held(2.0f, false);
        float armedAt = Y + HoldGesture.LOCK_ENTER + HoldGesture.LOCK_TRAVEL;
        gesture.move(X, armedAt, 0);
        gesture.move(X, armedAt - 3, 0);
        assertTrue(recorder.lockArmed);

        gesture.move(X, armedAt - HoldGesture.LOCK_DISARM - 1, 0);
        assertFalse(recorder.lockArmed);
    }

    @Test
    public void adjustThenPullDownLocksTheAdjustedSpeed() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        gesture.move(x + STEP_DISTANCE * 2, Y, 0);

        float down = Y + HoldGesture.LOCK_ENTER_FROM_ADJUST + 1;
        gesture.move(x + STEP_DISTANCE * 2, down, 0);
        assertEquals(State.LOCK_TARGET, gesture.state());

        // Sideways drift on the way down must not change the speed.
        gesture.move(x + STEP_DISTANCE * 4, down + HoldGesture.LOCK_TRAVEL, 0);
        assertEquals(2.5f, gesture.speed(), 0);

        gesture.release();
        assertEquals(Outcome.LOCK, recorder.outcome);
        assertEquals(2.5f, recorder.finalSpeed, 0);
    }

    @Test
    public void goingBackUpReturnsToAdjustingWithTheSameSpeed() {
        HoldGesture gesture = held(2.0f, false);
        float x = startAdjusting(gesture);
        gesture.move(x + STEP_DISTANCE * 2, Y, 0);
        gesture.move(x + STEP_DISTANCE * 2, Y + HoldGesture.LOCK_ENTER_FROM_ADJUST + 1, 0);

        float drifted = x + STEP_DISTANCE * 5;
        gesture.move(drifted, Y + HoldGesture.LOCK_ENTER_FROM_ADJUST - HoldGesture.LOCK_LEAVE - 1, 0);
        assertEquals(State.SPEED_ADJUST, gesture.state());
        assertEquals(2.5f, gesture.speed(), 0);

        gesture.move(drifted + STEP_DISTANCE, Y, 0);
        assertEquals(2.75f, gesture.speed(), 0);
    }

    @Test
    public void goingBackUpFromAPlainHoldReturnsToTheHold() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER + 10, 0);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER - HoldGesture.LOCK_LEAVE - 1, 0);
        assertEquals(State.HOLD_SPEED, gesture.state());
        assertEquals(0, recorder.lockProgress, 0);
    }

    @Test
    public void lockNeedsLessPullNearTheBottomOfTheScreen() {
        HoldGesture gesture = gesture(true, true, 0.25f);
        float roomBelow = HoldGesture.LOCK_ENTER + HoldGesture.LOCK_EDGE_MARGIN + 20;
        gesture.begin(X, Y, new HoldGesture.Room(2000, 2000, roomBelow), 2.0f, false);

        gesture.move(X, Y + HoldGesture.LOCK_ENTER + 20, 0);
        assertTrue(recorder.lockArmed);
    }

    @Test
    public void disabledDirectionsDoNothing() {
        HoldGesture gesture = gesture(false, false, 0.25f);
        gesture.begin(X, Y, WIDE, 2.0f, false);

        gesture.move(X + 100, Y, 0);
        assertEquals(State.HOLD_SPEED, gesture.state());
        gesture.move(X, Y + 100, 0);
        assertEquals(State.HOLD_SPEED, gesture.state());
    }

    @Test
    public void upwardDragKeepsTheHold() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y - 80, 0);
        assertEquals(State.HOLD_SPEED, gesture.state());
    }

    @Test
    public void aCanceledTouchNeverLocksOrUnlocks() {
        HoldGesture gesture = held(2.0f, false);
        gesture.move(X, Y + HoldGesture.LOCK_ENTER + HoldGesture.LOCK_TRAVEL, 0);
        gesture.cancel();
        assertEquals(Outcome.RESTORE, recorder.outcome);

        HoldGesture locked = held(2.0f, true);
        locked.cancel();
        assertEquals(Outcome.RESTORE, recorder.outcome);
    }
}
