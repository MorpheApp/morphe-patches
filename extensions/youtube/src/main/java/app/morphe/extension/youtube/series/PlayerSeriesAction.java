package app.morphe.extension.youtube.series;

import android.app.Activity;
import android.content.Context;
import android.widget.Toast;

import app.morphe.extension.youtube.patches.VideoInformation;

import java.lang.ref.WeakReference;

/** Player actions capture their context at the tap; following never launches another video. */
final class PlayerSeriesAction {
    private static WeakReference<Activity> pending = new WeakReference<>(null);

    static String playlist(String video, String responseVideo, String playlist) {
        return !video.isEmpty()
                        && video.equals(responseVideo)
                        && PlaylistInput.suggestible(playlist)
                ? playlist
                : "";
    }

    static void open(Context context) {
        Activity activity = HistoryUi.activity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (pending.get() == activity) return;
        String video = activeVideo();
        if (!video.matches("[A-Za-z0-9_-]{11}")) {
            HistoryUi.open(activity);
            return;
        }
        String playlist =
                playlist(
                        video,
                        VideoInformation.getPlayerResponseVideoId(),
                        VideoInformation.getPlaylistId());
        pending = new WeakReference<>(activity);
        TrackerService.get(activity)
                .library(
                        rows -> {
                            pending.clear();
                            if (activity.isFinishing() || activity.isDestroyed()) return;
                            // A player transition while storage was busy must not open the previous
                            // video's series.
                            if (!video.equals(activeVideo())) return;
                            String followed = followedSeries(rows, playlist, video);
                            if (!followed.isEmpty()) {
                                HistoryUi.openSeries(activity, followed);
                                return;
                            }
                            java.util.function.Consumer<String> done =
                                    id ->
                                            Toast.makeText(
                                                            activity,
                                                            UiText.get(
                                                                    activity,
                                                                    "series_tracker_followed"),
                                                            Toast.LENGTH_SHORT)
                                                    .show();
                            if (playlist.isEmpty()) {
                                java.util.List<PlaylistDiscovery.Match> saved =
                                        new java.util.ArrayList<>();
                                for (TrackerModels.Series series : rows) {
                                    if (series.bookmarkId.equals(video)
                                            || series.episodes.stream()
                                                    .anyMatch(e -> e.videoId.equals(video)))
                                        saved.add(
                                                new PlaylistDiscovery.Match(
                                                        series.id, series.name, "", video));
                                }
                                if (saved.size() > 1)
                                    PlaylistDiscoverySheet.saved(activity, video, done, saved);
                                else PlaylistDiscoverySheet.show(activity, video, done);
                            } else FollowFlow.fromPlayer(activity, playlist, video, done);
                        },
                        message -> {
                            pending.clear();
                            if (!activity.isFinishing() && !activity.isDestroyed())
                                Toast.makeText(
                                                activity,
                                                UiText.get(activity, message),
                                                Toast.LENGTH_LONG)
                                        .show();
                        });
    }

    static String followedSeries(
            java.util.List<TrackerModels.Series> rows, String playlist, String video) {
        if (!playlist.isEmpty()) {
            for (TrackerModels.Series series : rows)
                if (series.id.equals(playlist)) return series.id;
            return "";
        }
        String match = "";
        for (TrackerModels.Series series : rows) {
            if (series.bookmarkId.equals(video)
                    || series.episodes.stream().anyMatch(e -> e.videoId.equals(video))) {
                // Do not arbitrarily pick between two saved playlists containing the same video.
                if (!match.isEmpty()) return "";
                match = series.id;
            }
        }
        return match;
    }

    static String activeVideo() {
        try {
            String video = PlaybackBridge.videoId();
            return video == null ? "" : video;
        } catch (RuntimeException ignored) {
            // The controller may still be constructing, or may just have been released.
            return "";
        }
    }

    private PlayerSeriesAction() {}
}
