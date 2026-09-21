package app.morphe.extension.youtube.series;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

/** Main-thread adapter: ID and time are sampled from the same active controller. */
final class TrackerRuntime {
    private static long privacyGeneration;
    private static final PlaybackReducer reducer =
            new PlaybackReducer(
                    snapshot -> {
                        TrackerService service = service();
                        if (service != null)
                            service.checkpoint(
                                    snapshot, privacyGeneration, () -> reducerAck(snapshot));
                    },
                    () -> Settings.SERIES_TRACKER_COMPLETION_PERCENT.get(),
                    () -> Settings.SERIES_TRACKER_COMPLETION_SECONDS.get());
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static boolean waiting;
    private static LaunchRequest pending;
    private static long requestedAt, launchToken;

    private static TrackerService service() {
        return Utils.getContext() == null ? null : TrackerService.get(Utils.getContext());
    }

    static void initialize() {
        service();
    }

    static void newVideo() {
        reducer.newVideo(SystemClock.elapsedRealtime());
    }

    static void sample() {
        long currentPrivacyGeneration = RecordingPrivacy.generation();
        if (privacyGeneration != currentPrivacyGeneration) {
            reducer.clear();

            privacyGeneration = currentPrivacyGeneration;
        }
        PlaybackBridge.Source source = PlaybackBridge.source();
        if (source == null) return;
        try {
            String before = source.seriesTrackerVideoId();
            long position = source.seriesTrackerPosition();
            long duration = VideoInformation.getVideoLength();
            boolean shorts = VideoInformation.lastVideoIdIsShort();
            String after = source.seriesTrackerVideoId();
            if (source != PlaybackBridge.source() || before == null || !before.equals(after))
                return;
            if (waiting) {
                if (pending == null
                        || SystemClock.elapsedRealtime() - requestedAt < 600
                        || !pending.videoId.equals(before)
                        || Math.abs(position - pending.seconds() * 1000) > 3000
                        || !PlaybackSession.play()) return;

                waiting = false;
                pending = null;
            }
            TrackerService tracker = service();
            if (!Settings.SERIES_TRACKER_RECORD_PROGRESS.get()
                    || !RecordingPrivacy.allowsRecording()
                    || tracker == null
                    || !tracker.isTracked(before)) {
                reducer.clear();
                return;
            }
            String type = PlayerType.getCurrent().name();
            boolean excluded =
                    shorts
                            || type.equals("INLINE_MINIMAL")
                            || (!type.startsWith("WATCH_WHILE_") && !PlaybackSession.active());
            reducer.sample(
                    before,
                    after,
                    position,
                    duration,
                    excluded,
                    SystemClock.elapsedRealtime(),
                    System.currentTimeMillis());
        } catch (RuntimeException ignored) {
            /* Constructor-time metadata is not ready yet. */
        }
    }

    static void state(String value) {
        sample();
        reducer.state(value, SystemClock.elapsedRealtime());
    }

    static void prepareLaunch() {
        reducer.newVideo(SystemClock.elapsedRealtime());
        waiting = true;
        pending = null;
        launchToken++;
    }

    static void awaitLaunch(LaunchRequest request) {
        pending = request;
        waiting = true;
        requestedAt = SystemClock.elapsedRealtime();
        long token = ++launchToken;
        main.postDelayed(
                new Runnable() {
                    public void run() {
                        if (token != launchToken || !waiting) return;
                        sample();
                        if (!waiting) return;
                        if (SystemClock.elapsedRealtime() - requestedAt >= 15000) {
                            cancelLaunch();
                            if (Utils.getContext() != null)
                                Toast.makeText(
                                                Utils.getContext(),
                                                UiText.get(
                                                        Utils.getContext(),
                                                        "series_tracker_error_resume"),
                                                Toast.LENGTH_LONG)
                                        .show();

                        } else main.postDelayed(this, 250);
                    }
                },
                650);
    }

    static void cancelLaunch() {
        waiting = false;
        pending = null;
        launchToken++;
    }

    static void clear() {
        reducer.clear();
    }

    static void flush() {
        if (!Settings.SERIES_TRACKER_RECORD_PROGRESS.get()
                || !RecordingPrivacy.allowsRecording()
                || privacyGeneration != RecordingPrivacy.generation()) reducer.clear();
        else reducer.flush(SystemClock.elapsedRealtime());
    }

    private static void reducerAck(PlaybackReducer.Snapshot snapshot) {
        reducer.acknowledge(snapshot.generation, snapshot.sequence);
    }

    private TrackerRuntime() {}
}
