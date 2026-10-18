/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import static app.morphe.extension.youtube.series.TrackerModels.Episode;
import static app.morphe.extension.youtube.series.TrackerModels.Progress;
import static app.morphe.extension.youtube.series.TrackerModels.Series;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

/**
 * Next, previous and autoplay of an episode opened from the library follow the series order.
 * Uses the app's own navigation, so no end of video timer is needed.
 */
final class SeriesPlayback {
    @Nullable
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

    /**
     * Must be called after a launch has settled, as launching can briefly show the old video.
     */
    static void observed(String video) {
        if (session != null && !session.video.equals(video)) clear();
    }

    /**
     * @return If the navigation was handled and the app navigation must be skipped.
     */
    static boolean navigate(Enum<?> intent) {
        if (session == null || !Utils.isCurrentlyOnMainThread()) return false;
        String kind = intent.name();
        if (kind.equals("JUMP")) {
            // Selecting another video ends the series playback, unless it is an episode launch.
            if (!TrackerRuntime.launchPending()) clear();
            return false;
        }
        final boolean autoplay = kind.equals("AUTOPLAY") || kind.equals("AUTONAV");
        final boolean previous = kind.equals("PREVIOUS");
        if (!autoplay && !previous && !kind.equals("NEXT")) return false;

        Session active = session;
        PlaybackBridge.Source source = PlaybackBridge.source();
        if (!valid(active, source, token)) return false;
        if (autoplay && Settings.DISABLE_PLAYLIST_AUTOPLAY.get()) return true;
        if (navigating) return true;
        TrackerService service = TrackerService.existing();
        if (service == null) {
            clear();
            return false;
        }
        navigating = true;
        final long request = ++token;
        TrackerRuntime.flush();
        service.series(active.series.id(), current -> {
            if (!valid(active, source, request)) return;
            int ordinal = ResumePlanner.occurrence(active.series, active.video, active.ordinal, current);
            if (ordinal < 0) {
                fail("morphe_series_tracker_playback_changed");
                return;
            }
            Episode next = ResumePlanner.adjacent(current, active.video, ordinal, previous);
            if (next == null) {
                navigating = false;
                // Autoplay at the end is consumed, so it does not continue with recommendations.
                if (!autoplay) {
                    Utils.showToastLong(UiText.get("morphe_series_tracker_playback_boundary"));
                }
                return;
            }
            Progress progress = current.progress(next.videoId);
            boolean watched = progress.watched();
            service.select(current, next.videoId, next.ordinal, watched, () -> {
                if (!valid(active, source, request)) return;
                activate(current, next.videoId, next.ordinal);
                try {
                    ResumeLauncher.launch(Utils.getContext(), new LaunchRequest(
                            next.videoId, current.id(), watched ? 0 : progress.positionMs()));
                } catch (RuntimeException ex) {
                    Logger.printException(() -> "Could not open episode", ex);
                    fail("morphe_series_tracker_open_failed");
                }
            }, message -> {
                if (valid(active, source, request)) fail(message);
            });
        }, message -> {
            if (valid(active, source, request)) fail(message);
        });
        return true;
    }

    /**
     * Ends the session if the video or the account changed.
     */
    private static boolean valid(Session active, @Nullable PlaybackBridge.Source source, long request) {
        if (session != active || token != request) return false;
        if (source != null
                && source == PlaybackBridge.source()
                && active.video.equals(PlaybackBridge.videoId(source))
                && RecordingPrivacy.acceptsManualPosition(active.privacy)) {
            return true;
        }
        clear();
        return false;
    }

    private static void fail(String message) {
        clear();
        Utils.showToastLong(UiText.get(message));
    }

    private SeriesPlayback() {}
}
