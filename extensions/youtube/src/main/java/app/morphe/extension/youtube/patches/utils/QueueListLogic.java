/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3582
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.utils;

import java.util.List;

/**
 * List operations of the on-device queue.
 */
public final class QueueListLogic {

    /**
     * @return If the list changed.
     */
    public static <T> boolean move(List<T> list, int from, int to) {
        final int size = list.size();
        if (from < 0 || from >= size) {
            return false;
        }

        to = Math.max(0, Math.min(to, size - 1));
        if (to == from) {
            return false;
        }

        list.add(to, list.remove(from));
        return true;
    }

    /**
     * @return If the list changed.
     */
    public static <T> boolean remove(List<T> list, int index) {
        if (index < 0 || index >= list.size()) {
            return false;
        }

        list.remove(index);
        return true;
    }

    /**
     * @return If the list changed.
     */
    public static <T> boolean clear(List<T> list) {
        if (list.isEmpty()) {
            return false;
        }

        list.clear();
        return true;
    }

    /**
     * Index a dragged row ends at, from the center of the dragged row and the centers of the other
     * rows in their current order.
     */
    public static int dropIndex(float draggedCenter, float[] otherCenters) {
        int before = 0;
        for (float center : otherCenters) {
            if (center < draggedCenter) {
                before++;
            }
        }
        return before;
    }
}
