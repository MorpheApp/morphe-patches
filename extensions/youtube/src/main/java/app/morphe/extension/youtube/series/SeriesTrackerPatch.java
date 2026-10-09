/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.VideoState;

/** Direct targets of Morphe's playback hooks. */
public final class SeriesTrackerPatch {
    /**
     * Changing the setting requires an app restart. When disabled, every hook returns
     * immediately and the app behaves as if the patch was not included.
     */
    static final boolean ENABLED = Settings.SERIES_TRACKER.get();

    static {
        if (ENABLED) {
            VideoState.getOnChange().addObserver(state -> {
                videoStateChanged(state);
                return kotlin.Unit.INSTANCE;
            });
        }
    }

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController controller) {
        if (!ENABLED) return;
        try {
            PlaybackBridge.attach(
                    controller instanceof PlaybackBridge.Source
                            ? (PlaybackBridge.Source) controller
                            : null);
            TrackerRuntime.initialize();
            TrackerRuntime.newVideo();
        } catch (Exception ex) {
            Logger.printException(() -> "newVideoStarted failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void videoTimeChanged(long time) {
        if (!ENABLED) return;
        try {
            TrackerRuntime.sample();
        } catch (Exception ex) {
            Logger.printException(() -> "videoTimeChanged failure", ex);
        }
    }

    private static void videoStateChanged(Enum<?> state) {
        try {
            TrackerRuntime.state(state.name());
        } catch (Exception ex) {
            Logger.printException(() -> "videoStateChanged failure", ex);
        }
    }

    private SeriesTrackerPatch() {}
}
