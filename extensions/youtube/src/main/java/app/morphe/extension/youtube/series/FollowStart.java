/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The explicit starting point of a newly followed playlist. */
final class FollowStart {
    final String video;
    final long positionMs;
    final boolean reverse, watchedBefore;

    FollowStart(String video, long positionMs, boolean reverse, boolean watchedBefore) {
        this.video = video;
        this.positionMs = positionMs;
        this.reverse = reverse;
        this.watchedBefore = watchedBefore;
    }

    static long position(@Nullable PlaybackBridge.Source source, String video) {
        if (video.isEmpty() || !video.equals(PlaybackBridge.videoId(source))) return -1;
        long position = PlaybackBridge.position(source);
        return video.equals(PlaybackBridge.videoId(source)) ? Math.max(-1, position) : -1;
    }

    static Set<String> previous(
            List<TrackerModels.Episode> episodes, String video, boolean reverse) {
        List<TrackerModels.Episode> ordered = new ArrayList<>(episodes);
        if (reverse) Collections.reverse(ordered);
        Set<String> ids = new LinkedHashSet<>();
        for (TrackerModels.Episode e : ordered) {
            if (e.available && e.videoId.equals(video)) return ids;
            if (e.available && !e.videoId.equals(video)) ids.add(e.videoId);
        }
        throw new IllegalArgumentException("morphe_series_tracker_video_not_in_playlist");
    }
}
