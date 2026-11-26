/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5134
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

/**
 * Where the items of the launcher are, in a fan around a center, and which one a finger points at.
 * Positions and distances are in dp, and y grows downward.
 */
final class RadialLayout {
    static final int MAX_ITEMS = 5;
    /** Angle from the first item to the last one, by the number of items. */
    private static final float[] SPREAD_DEGREES = {0, 0, 70, 115, 145, 170};

    /**
     * Distance of the items from the center, where there is room for it. That far out, the finger
     * that opened the launcher does not cover them. Pointing goes by the direction of the finger,
     * so the finger does not have to go all the way.
     */
    static final float RADIUS_FAR = 104;
    /** The shortest distance, for where the area ends closer. */
    static final float RADIUS = 72;
    private static final float RADIUS_STEP = 8;
    /** A low player gets smaller items, and a smaller fan with them. */
    static final float RADIUS_OF_HEIGHT = 0.34f;
    static final float MIN_SCALE = 0.75f;
    /** Half the size of an item, and of the one that the finger points at. */
    static final float ITEM_RADIUS = 22;
    static final float ITEM_RADIUS_SELECTED = 27;
    /** Room between an item and the edge of the area. */
    static final float EDGE_MARGIN = 6;
    static final float CENTER_EDGE_MARGIN = 28;
    /**
     * Distance from the center where the finger starts to point at an item, and where it stops.
     * Farther than the drag that opens the launcher, so that it opens with nothing picked.
     */
    static final float SELECT_ENTER = 44;
    static final float SELECT_LEAVE = 36;
    /**
     * How far the finger has to move from where it opened the launcher before it points at an item.
     * Next to an edge of the area the center is not under the point of the press, and the finger
     * can be far from the center when the launcher opens.
     */
    static final float SELECT_MOVE = 14;

    /** A finger that points past the outer items still means them. */
    private static final float SELECT_BEYOND_FAN = (float) Math.toRadians(35);
    /** How much closer the finger must point at another item to leave the one it points at. */
    private static final float SELECT_KEEP = (float) Math.toRadians(6);
    private static final float TILT_STEP = (float) Math.toRadians(5);
    /** A quarter turn to each side. */
    private static final int TILT_STEPS = 18;
    /** A fan that is farther out than it has to be leans only this much. */
    private static final int TILT_STEPS_FAR = 6;
    private static final int FULL_TURN_STEPS = 72;
    private static final float UP = (float) -Math.PI / 2;
    private static final float DOWN = (float) Math.PI / 2;

    final float centerX;
    final float centerY;
    /** 1 for the designed sizes. Smaller in a low player. */
    final float scale;
    final float[] itemX;
    final float[] itemY;
    private final float[] itemAngle;
    /** Where the finger was when the launcher opened. */
    private final float openX;
    private final float openY;

    private RadialLayout(float centerX, float centerY, float scale, float[] itemX, float[] itemY,
                         float openX, float openY) {
        this.centerX = centerX;
        this.centerY = centerY;
        this.scale = scale;
        this.openX = openX;
        this.openY = openY;
        this.itemX = itemX;
        this.itemY = itemY;
        itemAngle = new float[itemX.length];
        for (int i = 0; i < itemX.length; i++) {
            itemAngle[i] = (float) Math.atan2(itemY[i] - centerY, itemX[i] - centerX);
        }
    }

    int count() {
        return itemX.length;
    }

    /**
     * Lays out the fan inside an area.
     * <p>
     * It opens upward around the point of the press, which is the way the finger went, as far out
     * as there is room. Next to a side of the area it leans away from that side. Without room
     * above, it opens downward around the finger instead, so that the finger starts in its middle.
     *
     * @param count Number of items, 1 to {@link #MAX_ITEMS}.
     */
    static RadialLayout create(float pressX, float pressY, float fingerX, float fingerY,
                               float left, float top, float right, float bottom, int count) {
        final float scale = Math.max(MIN_SCALE, Math.min(1, (bottom - top) * RADIUS_OF_HEIGHT / RADIUS));
        final float spread = (float) Math.toRadians(SPREAD_DEGREES[count]);
        final float inset = (ITEM_RADIUS_SELECTED + EDGE_MARGIN) * scale;
        final Fit fit = new Fit(left + inset, top + inset, right - inset, bottom - inset, spread, count);

        final float pressCenterX = clampCenter(pressX, left, right);
        final float pressCenterY = clampCenter(pressY, top, bottom);
        boolean upward = false;
        for (float radius = RADIUS_FAR; radius >= RADIUS && !upward; radius -= RADIUS_STEP) {
            fit.radius = radius * scale;
            upward = fit.find(pressCenterX, pressCenterY, true, radius > RADIUS ? TILT_STEPS_FAR : TILT_STEPS);
        }
        if (!upward) {
            final float fingerCenterX = clampCenter(fingerX, left, right);
            final float fingerCenterY = clampCenter(fingerY, top, bottom);
            if (!fit.find(fingerCenterX, fingerCenterY, false, TILT_STEPS)) {
                // The area is too small for the fan. Take what sticks out least and push it inside.
                fit.best(pressCenterX, pressCenterY);
            }
        }
        fit.orderFromTheLeft();
        return new RadialLayout(fit.centerX, fit.centerY, scale, fit.x, fit.y, fingerX, fingerY);
    }

    private static float clampCenter(float value, float from, float to) {
        if (to - from < 2 * CENTER_EDGE_MARGIN) return (from + to) / 2;
        return Math.max(from + CENTER_EDGE_MARGIN, Math.min(to - CENTER_EDGE_MARGIN, value));
    }

    /**
     * @param current Item that the finger points at so far, or -1.
     * @return Index of the item that the finger points at, or -1.
     */
    int select(float x, float y, int current) {
        final float dx = x - centerX;
        final float dy = y - centerY;
        final float reach = (current >= 0 ? SELECT_LEAVE : SELECT_ENTER) * scale;
        if (dx * dx + dy * dy < reach * reach) return -1;
        if (current < 0) {
            final float moved = SELECT_MOVE * scale;
            if ((x - openX) * (x - openX) + (y - openY) * (y - openY) < moved * moved) return -1;
        }

        final float angle = (float) Math.atan2(dy, dx);
        int best = -1;
        float bestOff = SELECT_BEYOND_FAN;
        for (int i = 0; i < itemAngle.length; i++) {
            final float off = angleBetween(angle, itemAngle[i]);
            if (off <= bestOff) {
                best = i;
                bestOff = off;
            }
        }
        if (current >= 0 && current < itemAngle.length && best != current) {
            // Do not flicker between two items on the line between them.
            final float currentOff = angleBetween(angle, itemAngle[current]);
            if (currentOff <= SELECT_BEYOND_FAN + SELECT_KEEP && (best < 0 || currentOff - bestOff < SELECT_KEEP)) {
                return current;
            }
        }
        return best;
    }

    private static float angleBetween(float a, float b) {
        float difference = Math.abs(a - b) % (float) (2 * Math.PI);
        if (difference > Math.PI) {
            difference = (float) (2 * Math.PI) - difference;
        }
        return difference;
    }

    /**
     * Tries the directions of a fan, from the wished one outward to both sides.
     */
    private static final class Fit {
        final float left;
        final float top;
        final float right;
        final float bottom;
        final float spread;
        final float[] x;
        final float[] y;
        float radius;
        float centerX;
        float centerY;

        Fit(float left, float top, float right, float bottom, float spread, int count) {
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
            this.spread = spread;
            x = new float[count];
            y = new float[count];
        }

        /**
         * @param tiltSteps How far the fan may lean to a side.
         * @return Whether a fan around the center fits into the area. It is in {@link #x} and {@link #y} then.
         */
        boolean find(float centerX, float centerY, boolean upward, int tiltSteps) {
            this.centerX = centerX;
            this.centerY = centerY;
            final float direction = upward ? UP : DOWN;
            // Lean toward the middle of the area first. Angles grow clockwise.
            final int inward = (centerX < (left + right) / 2) == upward ? 1 : -1;
            for (int step = 0; step <= tiltSteps; step++) {
                if (place(direction + inward * step * TILT_STEP) == 0) return true;
                if (step > 0 && place(direction - inward * step * TILT_STEP) == 0) return true;
            }
            return false;
        }

        /**
         * Takes the direction in which the fan has to move least to be inside, and moves it there.
         * Items that are outside even then, in an area smaller than the fan, are pushed inside.
         */
        void best(float centerX, float centerY) {
            this.centerX = centerX;
            this.centerY = centerY;
            float bestDirection = UP;
            float bestMoveX = 0;
            float bestMoveY = 0;
            float least = Float.MAX_VALUE;
            for (int step = 0; step < FULL_TURN_STEPS; step++) {
                final float direction = UP + step * TILT_STEP;
                place(direction);
                final float moveX = moveInside(x, left, right);
                final float moveY = moveInside(y, top, bottom);
                final float distance = (float) Math.hypot(moveX, moveY);
                if (distance < least) {
                    least = distance;
                    bestDirection = direction;
                    bestMoveX = moveX;
                    bestMoveY = moveY;
                }
            }
            place(bestDirection);
            this.centerX += bestMoveX;
            this.centerY += bestMoveY;
            for (int i = 0; i < x.length; i++) {
                x[i] = left > right ? (left + right) / 2 : Math.max(left, Math.min(right, x[i] + bestMoveX));
                y[i] = top > bottom ? (top + bottom) / 2 : Math.max(top, Math.min(bottom, y[i] + bestMoveY));
            }
        }

        /**
         * @return How far the positions have to move along one axis to be between two bounds.
         *         Far, if they are spread wider than the bounds are apart.
         */
        private static float moveInside(float[] positions, float from, float to) {
            float lowest = Float.MAX_VALUE;
            float highest = -Float.MAX_VALUE;
            for (float position : positions) {
                lowest = Math.min(lowest, position);
                highest = Math.max(highest, position);
            }
            if (highest - lowest > to - from) return 10 * (highest - lowest);
            return lowest < from ? from - lowest : highest > to ? to - highest : 0;
        }

        /**
         * The first item is the one on the left, also in a fan that opens downward.
         * In a fan that opens to a side it is the one at the top.
         */
        void orderFromTheLeft() {
            final int last = x.length - 1;
            final boolean sideways = Math.abs(x[0] - x[last]) < 1;
            if (sideways ? y[0] <= y[last] : x[0] < x[last]) return;
            for (int i = 0; i < last - i; i++) {
                final float swapX = x[i];
                x[i] = x[last - i];
                x[last - i] = swapX;
                final float swapY = y[i];
                y[i] = y[last - i];
                y[last - i] = swapY;
            }
        }

        /**
         * Puts the items on the arc around a direction.
         *
         * @return How far the items stick out of the area, added up.
         */
        private float place(float direction) {
            final int count = x.length;
            float outside = 0;
            for (int i = 0; i < count; i++) {
                final float angle = count == 1 ? direction : direction + spread * (i / (float) (count - 1) - 0.5f);
                x[i] = centerX + radius * (float) Math.cos(angle);
                y[i] = centerY + radius * (float) Math.sin(angle);
                outside += Math.max(0, left - x[i]) + Math.max(0, x[i] - right)
                        + Math.max(0, top - y[i]) + Math.max(0, y[i] - bottom);
            }
            return outside;
        }
    }
}
