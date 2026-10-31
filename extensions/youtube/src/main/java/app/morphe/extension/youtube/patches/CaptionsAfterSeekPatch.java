/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3986
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class CaptionsAfterSeekPatch {

    /**
     * Injection point.
     *
     * @param captionTimes Sorted times the captions change at.
     * @return Start time of the caption at the video time,
     *         or the video time if it is before the first caption.
     */
    public static long getCaptionStartTime(List<Long> captionTimes, long time) {
        try {
            int index = Collections.binarySearch(captionTimes, time);
            if (index < 0) {
                // Not an exact match. Use the caption time before the insertion point.
                index = -index - 2;
                if (index < 0) return time;
            }
            return captionTimes.get(index);
        } catch (Exception ex) {
            Logger.printException(() -> "getCaptionStartTime failure", ex);
            return time;
        }
    }
}
