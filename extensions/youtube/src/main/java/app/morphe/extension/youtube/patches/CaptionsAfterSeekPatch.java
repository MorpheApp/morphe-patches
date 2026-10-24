/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public final class CaptionsAfterSeekPatch {

    private CaptionsAfterSeekPatch() {
    }

    /**
     * Injection point.
     *
     * @param captionTimes Sorted times the captions change at.
     * @return Start time of the caption at the video time.
     */
    @SuppressWarnings("unchecked")
    public static long getCaptionStartTime(List<?> captionTimes, long time) {
        try {
            int index = Collections.binarySearch((List<Long>) captionTimes, time);
            if (index < 0) index = -index - 2;
            return index < 0 ? time : (Long) captionTimes.get(index);
        } catch (Exception ex) {
            Logger.printException(() -> "getCaptionStartTime failure", ex);
            return time;
        }
    }
}
