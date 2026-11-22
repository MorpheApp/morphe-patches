/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5099
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

/**
 * A value that follows its target like a weight on a damped spring.
 * A new target keeps the current velocity, so an animation can turn around midway without a jump.
 */
final class Spring {
    private static final float MAX_STEP_SECONDS = 0.004f;
    private static final float REST_DISTANCE = 0.001f;
    private static final float REST_VELOCITY = 0.01f;

    private final float stiffness;
    private final float damping;

    private float value;
    private float velocity;
    private float target;

    /**
     * @param stiffness    Higher is faster. About 300 settles in a quarter of a second.
     * @param dampingRatio 1 stops without overshoot, lower values bounce.
     */
    Spring(float stiffness, float dampingRatio) {
        this.stiffness = stiffness;
        this.damping = 2 * dampingRatio * (float) Math.sqrt(stiffness);
    }

    float value() {
        return value;
    }

    float target() {
        return target;
    }

    void setTarget(float target) {
        this.target = target;
    }

    void snapTo(float value) {
        this.value = value;
        target = value;
        velocity = 0;
    }

    /** Pushes the value away from its target, which then pulls it back. */
    void kick(float velocity) {
        this.velocity += velocity;
    }

    boolean atRest() {
        return value == target && velocity == 0;
    }

    /**
     * @return Whether the value still moves.
     */
    boolean advance(float seconds) {
        if (atRest()) return false;

        float left = Math.min(seconds, 0.1f);
        while (left > 0) {
            final float step = Math.min(left, MAX_STEP_SECONDS);
            velocity += (stiffness * (target - value) - damping * velocity) * step;
            value += velocity * step;
            left -= step;
        }
        if (Math.abs(target - value) < REST_DISTANCE && Math.abs(velocity) < REST_VELOCITY) {
            value = target;
            velocity = 0;
            return false;
        }
        return true;
    }
}
