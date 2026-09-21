package app.morphe.extension.youtube.series;

import java.util.*;

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

    static long position(PlaybackBridge.Source source, String video) {
        try {
            if (source == null || video.isEmpty() || !video.equals(source.seriesTrackerVideoId()))
                return -1;
            long position = source.seriesTrackerPosition();
            return video.equals(source.seriesTrackerVideoId()) ? Math.max(-1, position) : -1;
        } catch (RuntimeException unavailable) {
            return -1;
        }
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
        throw new IllegalArgumentException("series_tracker_video_not_in_playlist");
    }
}
