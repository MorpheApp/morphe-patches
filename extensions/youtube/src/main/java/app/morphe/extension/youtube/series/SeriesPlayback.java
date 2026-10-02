package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.*;

import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/** Uses the host's existing next/previous/autoplay dispatch; never starts an end timer. */
public final class SeriesPlayback {
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Session session;
    private static long token;
    private static boolean navigating;

    private static final class Session {
        final Series series;
        final String video;
        final int ordinal;
        final long privacy;

        Session(Series series, String video, int ordinal) {
            this.series = series;
            this.video = video;
            this.ordinal = ordinal;
            privacy = RecordingPrivacy.generation();
        }
    }

    static void activate(Series series, String video, int ordinal) {
        clear();
        if (ordinal > 0 && series.complete()) session = new Session(series, video, ordinal);
    }

    static void clear() {
        session = null;
        navigating = false;
        token++;
    }

    static void privacyChanged(long generation) {
        if (session != null && session.privacy != generation) clear();
    }

    static void observed(String video) {
        // Launch initialization can briefly expose the old controller. Only call after it settles.
        if (session != null && !session.video.equals(video)) clear();
    }

    /** Return true only when this explicit Series playback session owns the navigation. */
    public static boolean navigate(Enum<?> intent) {
        if (Looper.myLooper() != Looper.getMainLooper() || session == null || intent == null)
            return false;
        String kind = intent.name();
        if (kind.equals("JUMP")) {
            if (!TrackerRuntime.launchPending())
                clear(); // An explicit native selection ends ownership.
            return false;
        }
        if (!(kind.equals("NEXT")
                || kind.equals("PREVIOUS")
                || kind.equals("AUTOPLAY")
                || kind.equals("AUTONAV"))) return false;
        Session active = session;
        PlaybackBridge.Source source = PlaybackBridge.source();
        try {
            if (source == null
                    || !active.video.equals(source.seriesTrackerVideoId())
                    || !RecordingPrivacy.acceptsManualPosition(active.privacy)) {
                clear();
                return false;
            }
        } catch (RuntimeException unavailable) {
            clear();
            return false;
        }
        if ((kind.equals("AUTOPLAY") || kind.equals("AUTONAV"))
                && Settings.DISABLE_PLAYLIST_AUTOPLAY.get()) return true;
        if (navigating) return true;
        navigating = true;
        long request = ++token;
        TrackerRuntime.flush();
        TrackerService service = TrackerService.existing();
        if (service == null) {
            clear();
            return false;
        }
        service.series(
                active.series.id,
                current -> {
                    if (!valid(active, source, request)) return;
                    int ordinal =
                            ResumePlanner.occurrence(
                                    active.series, active.video, active.ordinal, current);
                    if (ordinal < 0) {
                        fail("series_tracker_playback_changed");
                        return;
                    }
                    Episode next =
                            ResumePlanner.adjacent(
                                    current, active.video, ordinal, kind.equals("PREVIOUS"));
                    if (next == null) {
                        navigating = false;
                        // Consume native autoplay at the end, so it cannot escape into
                        // recommendations.
                        if (kind.equals("NEXT") || kind.equals("PREVIOUS"))
                            toast("series_tracker_playback_boundary");
                        return;
                    }
                    Progress progress = current.progress(next.videoId);
                    service.select(
                            current,
                            next.videoId,
                            next.ordinal,
                            progress.watched(),
                            () -> {
                                if (!valid(active, source, request)) return;
                                activate(current, next.videoId, next.ordinal);
                                try {
                                    ResumeLauncher.launch(
                                            Utils.getContext(),
                                            new LaunchRequest(
                                                    next.videoId,
                                                    current.id,
                                                    progress.watched() ? 0 : progress.positionMs));
                                } catch (RuntimeException failure) {
                                    fail("series_tracker_open_failed");
                                }
                            },
                            message -> {
                                if (valid(active, source, request)) fail(message);
                            });
                },
                message -> {
                    if (valid(active, source, request)) fail(message);
                });
        return true;
    }

    private static boolean valid(Session active, PlaybackBridge.Source source, long request) {
        if (session != active || token != request) return false;
        try {
            if (source == PlaybackBridge.source()
                    && active.video.equals(source.seriesTrackerVideoId())
                    && RecordingPrivacy.acceptsManualPosition(active.privacy)) return true;
        } catch (RuntimeException ignored) {
        }
        clear();
        return false;
    }

    private static void fail(String message) {
        clear();
        toast(message);
    }

    private static void toast(String message) {
        main.post(
                () ->
                        Toast.makeText(
                                        Utils.getContext(),
                                        UiText.get(Utils.getContext(), message),
                                        Toast.LENGTH_LONG)
                                .show());
    }

    private SeriesPlayback() {}
}
