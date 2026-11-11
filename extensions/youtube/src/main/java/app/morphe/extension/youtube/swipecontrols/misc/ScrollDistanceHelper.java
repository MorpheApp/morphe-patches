/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.misc;

import java.util.function.IntConsumer;

/**
 * Helper for scaling the onScroll handler.
 */
public final class ScrollDistanceHelper {

    /**
     * Absolute distance after which the callback is invoked.
     */
    private final int unitDistance;

    /**
     * Callback invoked with the scroll direction when the unit distance is reached.
     */
    private final IntConsumer callback;

    /**
     * Total distance scrolled.
     */
    private double scrolledDistance;

    /**
     * @param unitDistance Absolute distance after which the callback is invoked.
     * @param callback     Callback invoked with the scroll direction when the unit distance is reached.
     */
    public ScrollDistanceHelper(int unitDistance, IntConsumer callback) {
        this.unitDistance = unitDistance;
        this.callback = callback;
    }

    /**
     * Adds a scrolled distance to the total.
     * If the {@link #unitDistance} is reached, also invokes the callback.
     *
     * @param distance The distance to add.
     */
    public void add(double distance) {
        scrolledDistance += distance;

        // invoke the callback if we scrolled far enough
        while (Math.abs(scrolledDistance) >= unitDistance) {
            subtractUnitDistance();
            callback.accept((int) Math.signum(scrolledDistance));
        }
    }

    /**
     * Resets the distance scrolled to zero.
     */
    public void reset() {
        scrolledDistance = 0.0;
    }

    /**
     * Subtracts the {@link #unitDistance} from the total {@link #scrolledDistance}.
     */
    private void subtractUnitDistance() {
        scrolledDistance -= unitDistance * Math.signum(scrolledDistance);
    }
}
