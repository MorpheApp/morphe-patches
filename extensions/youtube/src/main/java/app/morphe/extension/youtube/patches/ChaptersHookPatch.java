/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2214
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;

import java.util.Arrays;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;

@SuppressWarnings("unused")
public class ChaptersHookPatch {
    public interface TimelineMarker {
        long patch_getStartMillis();
        long patch_getEndMillis();
        CharSequence patch_getTitle();
    }

    @Nullable
    private static volatile TimelineMarker[] chapterMarkers;
    private static boolean heatMapPeakPoint = false;

    @Nullable
    public static TimelineMarker[] getChapterMarkers() {
        return chapterMarkers;
    }

    @Nullable
    public static CharSequence getChapterTitleAtTime(long currentMillis) {
        TimelineMarker[] markers = chapterMarkers;
        if (markers != null) {
            for (TimelineMarker marker : markers) {
                if (currentMillis >= marker.patch_getStartMillis()
                        && currentMillis < marker.patch_getEndMillis()) {
                    CharSequence title = marker.patch_getTitle();
                    //noinspection SizeReplaceableByIsEmpty
                    return title != null && title.length() > 0
                            ? title
                            : null;
                }
            }
        }
        return null;
    }

    /**
     * Injection point.
     */
    public static void newVideoLoaded(@Nullable String videoId) {
        chapterMarkers = null;
    }

    /**
     * Injection point.
     */
    public static void setTimelineMarkers(TimelineMarker[] markers) {
        if (markers.length > 0 && markers[0].patch_getTitle() == null) {
            // Chapters array can alternate between an array with
            // no titles and an identical array with titles.
            // Ignore the no title array as it's of no use here.
            return;
        }

        if (BaseSettings.DEBUG.get() && !Arrays.equals(markers, chapterMarkers)) {
            Logger.printDebug(() -> "TimelineMarkers: " + Arrays.toString(markers));
        }
        chapterMarkers = markers;
    }

    /**
     * Injection point.
     */
    public static void setHeatMapPeakPoint(boolean value) {
        heatMapPeakPoint = value;
    }

    public static boolean getHeatMapPeakPoint() {
        return heatMapPeakPoint;
    }
}
