/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

/**
 * What one finger does on the player from the long press to the release.
 * Positions and distances are in dp, and y grows downward.
 */
public final class HoldGesture {

    public enum State {
        IDLE,
        /** The finger has not picked a direction since the long press. */
        HOLD_SPEED,
        SPEED_ADJUST,
        LOCK_TARGET
    }

    public enum Outcome {
        /** Play again at the speed from before the press. */
        RESTORE,
        /** Keep the chosen speed after the release. */
        LOCK,
        /** End the lock that was on when the press began. */
        UNLOCK
    }

    public interface Listener {
        void onStateChanged(State previous, State current);

        /**
         * The speed to play at while the finger stays down.
         *
         * @param stepped False for the speed that the hold starts with.
         */
        void onSpeedChanged(float speed, boolean stepped);

        /** How far the finger is pulled toward the lock, 0 to 1. */
        void onLockProgress(float progress, boolean armed);

        /** The finger left the screen. */
        void onFinished(Outcome outcome, float speed);
    }

    public static final class Config {
        public final boolean adjustEnabled;
        public final boolean lockEnabled;
        public final float speedStep;
        public final float minSpeed;
        public final float maxSpeed;
        /** Horizontal distance of one speed step, where the screen has room for it. */
        public final float stepDistance;

        public Config(boolean adjustEnabled, boolean lockEnabled,
                      float speedStep, float minSpeed, float maxSpeed, float stepDistance) {
            this.adjustEnabled = adjustEnabled;
            this.lockEnabled = lockEnabled;
            this.speedStep = speedStep;
            this.minSpeed = minSpeed;
            this.maxSpeed = maxSpeed;
            this.stepDistance = stepDistance;
        }
    }

    /**
     * How far the finger can move from where it pressed before it meets an edge of the screen.
     */
    public static final class Room {
        public final float left;
        public final float right;
        public final float below;

        public Room(float left, float right, float below) {
            this.left = left;
            this.right = right;
            this.below = below;
        }
    }

    static final float ADJUST_ENTER = 22;
    /** Shortest distance of one speed step that a finger can still stop on. */
    static final float STEP_DISTANCE_MIN = 8;
    /** A finger does not reach the last bit of the screen at its sides. */
    static final float SIDE_EDGE_MARGIN = 12;
    static final float LOCK_ENTER = 26;
    /** Farther than {@link #LOCK_ENTER}, because a sideways drag drifts up and down. */
    static final float LOCK_ENTER_FROM_ADJUST = 46;
    /** Pull from where the lock target shows up to where it arms. */
    static final float LOCK_TRAVEL = 42;
    /** Shortest pull, used when the press is too close to the bottom of the screen. */
    static final float LOCK_TRAVEL_MIN = 16;
    static final float LOCK_EDGE_MARGIN = 8;
    /** Way back up that leaves the lock target, and that disarms it. */
    static final float LOCK_LEAVE = 12;
    static final float LOCK_DISARM = 6;

    private static final int MAX_STEPS = 1000;

    private final Config config;
    private final Listener listener;

    private State state = State.IDLE;
    private float startX;
    private float startY;
    private Room room;

    private float startSpeed;
    private boolean startedLocked;
    private float speed;
    private int minSteps;
    private int maxSteps;
    private int steps;
    /** Distance of one step slower and of one step faster. */
    private float stepLeft;
    private float stepRight;
    /** Where the finger is at the start speed. */
    private float zeroX;

    private State lockCameFrom;
    private float lockEnter;
    private float lockTravel;
    private boolean lockArmed;

    public HoldGesture(Config config, Listener listener) {
        this.config = config;
        this.listener = listener;
    }

    public State state() {
        return state;
    }

    public float speed() {
        return speed;
    }

    /**
     * The long press is reached.
     *
     * @param holdSpeed Speed to play at while the finger stays down.
     * @param locked    Whether the speed is locked now. A locked speed is also the hold speed.
     */
    public void begin(float x, float y, Room room, float holdSpeed, boolean locked) {
        if (state != State.IDLE) return;
        startX = x;
        startY = y;
        this.room = room;
        startSpeed = holdSpeed;
        startedLocked = locked;
        steps = 0;
        minSteps = 0;
        while (speedAt(minSteps) > config.minSpeed && minSteps > -MAX_STEPS) minSteps--;
        maxSteps = 0;
        while (speedAt(maxSteps) < config.maxSpeed && maxSteps < MAX_STEPS) maxSteps++;
        stepLeft = stepDistanceFor(room.left, -minSteps);
        stepRight = stepDistanceFor(room.right, maxSteps);
        speed = holdSpeed;
        setState(State.HOLD_SPEED);
        listener.onSpeedChanged(speed, false);
    }

    public void move(float x, float y) {
        final float dx = x - startX;
        final float dy = y - startY;

        switch (state) {
            case HOLD_SPEED:
                if (config.adjustEnabled && Math.abs(dx) > ADJUST_ENTER && Math.abs(dx) > Math.abs(dy)) {
                    zeroX = x;
                    setState(State.SPEED_ADJUST);
                } else if (config.lockEnabled && dy > LOCK_ENTER && dy > Math.abs(dx)) {
                    enterLock(LOCK_ENTER, dy);
                }
                break;
            case SPEED_ADJUST:
                if (config.lockEnabled && dy > LOCK_ENTER_FROM_ADJUST) {
                    enterLock(LOCK_ENTER_FROM_ADJUST, dy);
                } else {
                    adjustSpeed(x);
                }
                break;
            case LOCK_TARGET:
                if (dy < lockEnter - LOCK_LEAVE) {
                    leaveLock(x);
                } else {
                    updateLock(dy);
                }
                break;
            case IDLE:
                break;
        }
    }

    public void release() {
        switch (state) {
            case HOLD_SPEED:
                finish(startedLocked ? Outcome.UNLOCK : Outcome.RESTORE);
                break;
            case SPEED_ADJUST:
                finish(Outcome.RESTORE);
                break;
            case LOCK_TARGET:
                finish(lockArmed ? Outcome.LOCK : Outcome.RESTORE);
                break;
            case IDLE:
                break;
        }
    }

    /**
     * The touch was taken away, such as by the system. Never locks or unlocks.
     */
    public void cancel() {
        if (state != State.IDLE) {
            finish(Outcome.RESTORE);
        }
    }

    /**
     * Distance of one step toward one side. Shorter than usual when the finger would meet
     * the edge of the screen before the last step.
     */
    private float stepDistanceFor(float roomAtSide, int stepsToLast) {
        if (stepsToLast == 0) return config.stepDistance;

        final float fitting = (roomAtSide - ADJUST_ENTER - SIDE_EDGE_MARGIN) / stepsToLast;
        return Math.max(STEP_DISTANCE_MIN, Math.min(config.stepDistance, fitting));
    }

    private float offsetOf(int steps) {
        return steps * (steps < 0 ? stepLeft : stepRight);
    }

    private void adjustSpeed(float x) {
        final float offset = x - zeroX;
        int target = Math.round(offset / (offset < 0 ? stepLeft : stepRight));
        if (target < minSteps || target > maxSteps) {
            // Start over from the end of the range, so that a turn back takes effect at once.
            target = Math.max(minSteps, Math.min(maxSteps, target));
            zeroX = x - offsetOf(target);
        }
        if (target != steps) {
            steps = target;
            speed = speedAt(steps);
            listener.onSpeedChanged(speed, true);
        }
    }

    private void enterLock(float enter, float dy) {
        lockCameFrom = state;
        lockEnter = enter;
        final float fitting = room.below - enter - LOCK_EDGE_MARGIN;
        lockTravel = Math.max(LOCK_TRAVEL_MIN, Math.min(LOCK_TRAVEL, fitting));
        lockArmed = false;
        setState(State.LOCK_TARGET);
        updateLock(dy);
    }

    private void updateLock(float dy) {
        final float pulled = dy - lockEnter;
        final float progress = Math.max(0, Math.min(1, pulled / lockTravel));
        if (lockArmed) {
            lockArmed = pulled > lockTravel - LOCK_DISARM;
        } else {
            lockArmed = pulled >= lockTravel;
        }
        listener.onLockProgress(lockArmed ? 1 : progress, lockArmed);
    }

    private void leaveLock(float x) {
        lockArmed = false;
        listener.onLockProgress(0, false);
        if (lockCameFrom == State.SPEED_ADJUST) {
            // The finger may have moved sideways on the way down and back. Keep the speed.
            zeroX = x - offsetOf(steps);
        }
        setState(lockCameFrom);
    }

    private void finish(Outcome outcome) {
        final float finalSpeed = speed;
        setState(State.IDLE);
        listener.onFinished(outcome, finalSpeed);
    }

    private void setState(State next) {
        final State previous = state;
        state = next;
        listener.onStateChanged(previous, next);
    }

    /**
     * Speed that is the given number of steps away from the start speed.
     * A start speed between two steps moves to the next step first.
     */
    private float speedAt(int steps) {
        if (steps == 0) return startSpeed;

        final double position = (double) startSpeed / config.speedStep;
        final long nearest = Math.round(position);
        final long from;
        if (Math.abs(position - nearest) < 1e-3) {
            from = nearest;
        } else {
            from = (long) (steps > 0 ? Math.floor(position) : Math.ceil(position));
        }
        final float stepped = Math.round((from + steps) * (double) config.speedStep * 100) / 100f;
        return Math.max(config.minSpeed, Math.min(config.maxSpeed, stepped));
    }
}
