package app.morphe.extension.youtube.series;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoInformation;

/** Direct targets of Morphe's playback hooks. */
public final class SeriesTrackerPatch {
    static {
        app.morphe.extension.youtube.shared.VideoState.getOnChange()
                .addObserver(
                        state -> {
                            videoStateChanged(state);
                            return kotlin.Unit.INSTANCE;
                        });
    }

    public static void newVideoStarted(VideoInformation.PlaybackController controller) {
        try {
            PlaybackBridge.attach(
                    controller instanceof PlaybackBridge.Source
                            ? (PlaybackBridge.Source) controller
                            : null);
            TrackerRuntime.initialize();
            TrackerRuntime.newVideo();
        } catch (Exception e) {
            Logger.printException(() -> "Series controller initialization", e);
        }
    }

    public static void videoTimeChanged(long time) {
        try {
            TrackerRuntime.sample();
        } catch (Exception e) {
            Logger.printException(() -> "Series progress sampling", e);
        }
    }

    public static void videoStateChanged(Enum<?> state) {
        try {
            TrackerRuntime.state(state.name());
        } catch (Exception e) {
            Logger.printException(() -> "Series playback state", e);
        }
    }

    private SeriesTrackerPatch() {}
}
