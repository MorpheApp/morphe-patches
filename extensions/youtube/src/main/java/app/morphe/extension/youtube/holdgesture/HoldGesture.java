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
        LOCK_TARGET,
        /** A drag up opened the launcher, and the finger picks one of its actions. */
        LAUNCHER
    }

    /** How a sideways drag changes the speed. */
    public enum Adjust {
        /** The distance from the neutral point is the number of steps. */
        PRECISE,
        /** Far from the neutral point the speed keeps changing, faster the farther out. */
        THROTTLE,
        /** On the way back from there the speed stays, until the finger is back, rests or turns. */
        COASTING
    }

    public enum Outcome {
        /** Play again at the speed from before the press. */
        RESTORE,
        /** Keep the chosen speed after the release. */
        LOCK,
        /** End the lock that was on when the press began. */
        UNLOCK,
        /** Play again at the speed from before the press, and run what the finger picked in the launcher. */
        LAUNCHER
    }

    public interface Listener {
        void onStateChanged(State previous, State current);

        /**
         * The speed to play at while the finger stays down.
         *
         * @param stepped False for the speed that the hold starts with.
         */
        void onSpeedChanged(float speed, boolean stepped);

        /**
         * @param direction -1 or 1 while the speed keeps changing by itself, else 0.
         */
        void onAdjustChanged(Adjust adjust, int direction);

        /** The speed is the one of now where the finger is now, and changes from there. */
        void onNeutralMoved();

        /** How far the finger is pulled toward the lock, 0 to 1. */
        void onLockProgress(float progress, boolean armed);

        /** Where the finger is while the launcher is open. */
        void onLauncherMoved(float x, float y);

        /** The finger left the screen. */
        void onFinished(Outcome outcome, float speed);
    }

    public static final class Config {
        public final boolean adjustEnabled;
        public final boolean lockEnabled;
        public final boolean launcherEnabled;
        public final float speedStep;
        public final float minSpeed;
        public final float maxSpeed;
        /** Horizontal distance of one speed step near the neutral point. */
        public final float stepDistance;

        public Config(boolean adjustEnabled, boolean lockEnabled, boolean launcherEnabled,
                      float speedStep, float minSpeed, float maxSpeed, float stepDistance) {
            this.adjustEnabled = adjustEnabled;
            this.lockEnabled = lockEnabled;
            this.launcherEnabled = launcherEnabled;
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
    /** Distance from the neutral point where the speed starts to change by itself. */
    static final float THROTTLE_ENTER = 48;
    /** Distances beyond that where the second and the third pace begin. */
    static final float THROTTLE_SECOND = 22;
    static final float THROTTLE_THIRD = 46;
    /** The third pace is never closer to the second one than a finger can tell apart. */
    static final float THROTTLE_THIRD_MIN = 8;
    static final long[] THROTTLE_INTERVAL_MS = {210, 88, 42};
    /** What one interval of the third pace changes. Several steps, where the steps are small. */
    private static final float THROTTLE_THIRD_SPEED = 0.2f;
    /** With less room than this to a side of the screen, the throttle of that side begins earlier. */
    static final float THROTTLE_SHORT_ROOM = 60;
    static final float THROTTLE_SHORT_ROOM_SHARE = 0.55f;
    static final float THROTTLE_ENTER_MIN = 22;
    /** A finger does not reach the last bit of the screen at its sides. */
    static final float SIDE_EDGE_MARGIN = 8;
    /** Way back from the farthest point that stops the throttle. */
    static final float CLUTCH_PULL_BACK = 10;
    /** Rest on the way back that makes the place of the finger the neutral point. */
    static final long NEUTRAL_REST_MS = 180;
    /** A resting finger still moves this much. */
    static final float REST_SLOP = 2;
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
    /** Way up that opens the launcher. */
    static final float LAUNCHER_ENTER = 30;

    private static final int MAX_STEPS = 1000;
    /** Steps to make up for when the throttle was not run for a while. */
    private static final int THROTTLE_CATCH_UP = 4;

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

    private Adjust adjust = Adjust.PRECISE;
    /** Where the finger is at {@link #neutralSteps}. */
    private float neutralX;
    private int neutralSteps;
    private float lastX;
    private int throttleDirection;
    /** Index into {@link #THROTTLE_INTERVAL_MS}. */
    private int throttlePace;
    private long throttleStepAt;
    private boolean throttleStepped;
    /** Farthest distance from the neutral point in this throttle. */
    private float throttlePeak;
    /** Farthest that the finger came back since the throttle stopped. */
    private float coastTurnX;
    private float restX;
    private long restSince;

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

    public Adjust adjust() {
        return adjust;
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
        speed = holdSpeed;
        setState(State.HOLD_SPEED);
        listener.onSpeedChanged(speed, false);
    }

    /**
     * @param now Time in milliseconds, of the same clock as {@link #tick(long)}.
     */
    public void move(float x, float y, long now) {
        final float dx = x - startX;
        final float dy = y - startY;

        switch (state) {
            case HOLD_SPEED:
                if (config.adjustEnabled && Math.abs(dx) > ADJUST_ENTER && Math.abs(dx) > Math.abs(dy)) {
                    setNeutral(x);
                    setState(State.SPEED_ADJUST);
                } else if (config.lockEnabled && dy > LOCK_ENTER && dy > Math.abs(dx)) {
                    enterLock(LOCK_ENTER, dy);
                } else if (config.launcherEnabled && -dy > LAUNCHER_ENTER && -dy > Math.abs(dx)) {
                    setState(State.LAUNCHER);
                    listener.onLauncherMoved(x, y);
                }
                break;
            case SPEED_ADJUST:
                if (config.lockEnabled && dy > LOCK_ENTER_FROM_ADJUST) {
                    enterLock(LOCK_ENTER_FROM_ADJUST, dy);
                } else {
                    lastX = x;
                    adjustSpeed(x, now);
                }
                break;
            case LOCK_TARGET:
                if (dy < lockEnter - LOCK_LEAVE) {
                    leaveLock(x);
                } else {
                    updateLock(dy);
                }
                break;
            case LAUNCHER:
                listener.onLauncherMoved(x, y);
                break;
            case IDLE:
                break;
        }
    }

    /**
     * What goes on while the finger rests. To be called about every frame during {@link State#SPEED_ADJUST}.
     */
    public void tick(long now) {
        if (state != State.SPEED_ADJUST) return;

        if (adjust == Adjust.THROTTLE) {
            runThrottle(now);
        } else if (adjust == Adjust.COASTING && now - restSince >= NEUTRAL_REST_MS) {
            moveNeutral(lastX);
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
            case LAUNCHER:
                finish(Outcome.LAUNCHER);
                break;
            case IDLE:
                break;
        }
    }

    /**
     * The touch was taken away, such as by the system. Never locks, unlocks or runs an action.
     */
    public void cancel() {
        if (state != State.IDLE) {
            finish(Outcome.RESTORE);
        }
    }

    private void adjustSpeed(float x, long now) {
        final float offset = x - neutralX;
        final float distance = Math.abs(offset);
        final int direction = offset < 0 ? -1 : 1;
        final float enter = throttleEnter(direction);

        switch (adjust) {
            case PRECISE:
                if (distance <= enter) {
                    stepTo(x);
                    break;
                }
                stepTo(neutralX + direction * enter);
                if (canStep(direction)) {
                    throttleDirection = direction;
                    startThrottle(distance, now);
                }
                break;
            case THROTTLE:
                runThrottle(now);
                if (direction == throttleDirection && distance > enter
                        && !(throttleStepped && throttlePeak - distance >= CLUTCH_PULL_BACK)) {
                    throttlePeak = Math.max(throttlePeak, distance);
                    throttlePace = paceAt(distance, direction);
                } else if (throttleStepped) {
                    coastTurnX = x;
                    restX = x;
                    restSince = now;
                    setAdjust(Adjust.COASTING, 0);
                    adjustSpeed(x, now);
                } else {
                    // Out and back before the first step by itself.
                    setAdjust(Adjust.PRECISE, 0);
                    stepTo(x);
                }
                break;
            case COASTING:
                if (Math.abs(x - restX) > REST_SLOP) {
                    restX = x;
                    restSince = now;
                }
                if (now - restSince >= NEUTRAL_REST_MS) {
                    moveNeutral(x);
                } else if ((x - coastEnd()) * throttleDirection <= 0) {
                    // Back. What the finger moves on from here is a drag the other way.
                    moveNeutral(coastEnd());
                    stepTo(x);
                } else if ((x - coastTurnX) * throttleDirection < CLUTCH_PULL_BACK) {
                    coastTurnX = throttleDirection > 0 ? Math.min(coastTurnX, x) : Math.max(coastTurnX, x);
                } else if (offset * throttleDirection > throttleEnter(throttleDirection)) {
                    // Pushed out again.
                    startThrottle(distance, now);
                } else {
                    // Turned around: the drag goes on from where it turned.
                    moveNeutral(coastTurnX);
                    stepTo(x);
                }
                break;
        }
    }

    /**
     * Sets the speed that the place of the finger stands for near the neutral point.
     */
    private void stepTo(float x) {
        int target = neutralSteps + Math.round((x - neutralX) / config.stepDistance);
        if (target < minSteps || target > maxSteps) {
            target = Math.max(minSteps, Math.min(maxSteps, target));
            // Start over from the end of the range, so that a turn back takes effect at once.
            neutralX = x;
            neutralSteps = target;
        }
        setSteps(target);
    }

    private void setSteps(int target) {
        if (target != steps) {
            steps = target;
            speed = speedAt(steps);
            listener.onSpeedChanged(speed, true);
        }
    }

    /**
     * Where the way back ends: at the neutral point, or at the point of the press when that is
     * farther back, which is where the thumb is at ease.
     */
    private float coastEnd() {
        return throttleDirection > 0 ? Math.min(neutralX, startX) : Math.max(neutralX, startX);
    }

    private boolean canStep(int direction) {
        return direction < 0 ? steps > minSteps : steps < maxSteps;
    }

    private void startThrottle(float distance, long now) {
        throttlePeak = distance;
        throttlePace = paceAt(distance, throttleDirection);
        throttleStepAt = now;
        throttleStepped = false;
        setAdjust(Adjust.THROTTLE, throttleDirection);
    }

    private void runThrottle(long now) {
        final long interval = THROTTLE_INTERVAL_MS[throttlePace];
        final int count = throttlePace < 2 ? 1
                : Math.max(1, Math.round(THROTTLE_THIRD_SPEED / config.speedStep));
        for (int i = 0; i < THROTTLE_CATCH_UP && now - throttleStepAt >= interval; i++) {
            throttleStepAt += interval;
            final int target = Math.max(minSteps, Math.min(maxSteps, steps + throttleDirection * count));
            if (target != steps) {
                throttleStepped = true;
                setSteps(target);
            }
        }
        if (now - throttleStepAt >= interval) {
            throttleStepAt = now;
        }
    }

    private float roomToward(int direction) {
        final float moved = neutralX - startX;
        return direction < 0 ? room.left + moved : room.right - moved;
    }

    private float throttleEnter(int direction) {
        final float roomAtSide = roomToward(direction);
        return roomAtSide < THROTTLE_SHORT_ROOM
                ? Math.max(THROTTLE_ENTER_MIN, roomAtSide * THROTTLE_SHORT_ROOM_SHARE)
                : THROTTLE_ENTER;
    }

    /**
     * The paces move closer together where the screen ends before the third one.
     */
    private int paceAt(float distance, int direction) {
        final float enter = throttleEnter(direction);
        final float reach = roomToward(direction) - SIDE_EDGE_MARGIN - enter;
        final float squeeze = Math.max(THROTTLE_THIRD_MIN, Math.min(THROTTLE_THIRD, reach)) / THROTTLE_THIRD;
        final float beyond = distance - enter;
        return beyond < THROTTLE_SECOND * squeeze ? 0 : beyond < THROTTLE_THIRD * squeeze ? 1 : 2;
    }

    private void setNeutral(float x) {
        neutralX = x;
        neutralSteps = steps;
        lastX = x;
        setAdjust(Adjust.PRECISE, 0);
    }

    private void moveNeutral(float x) {
        setNeutral(x);
        listener.onNeutralMoved();
    }

    private void setAdjust(Adjust next, int direction) {
        if (adjust != next) {
            adjust = next;
            listener.onAdjustChanged(next, direction);
        }
    }

    private void enterLock(float enter, float dy) {
        lockCameFrom = state;
        lockEnter = enter;
        final float fitting = room.below - enter - LOCK_EDGE_MARGIN;
        lockTravel = Math.max(LOCK_TRAVEL_MIN, Math.min(LOCK_TRAVEL, fitting));
        lockArmed = false;
        setAdjust(Adjust.PRECISE, 0);
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
            setNeutral(x);
        }
        setState(lockCameFrom);
    }

    private void finish(Outcome outcome) {
        final float finalSpeed = speed;
        setAdjust(Adjust.PRECISE, 0);
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
