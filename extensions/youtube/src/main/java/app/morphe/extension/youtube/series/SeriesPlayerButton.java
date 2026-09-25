package app.morphe.extension.youtube.series;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;

public final class SeriesPlayerButton {
    static {
        if (Settings.SERIES_TRACKER_BUTTON.get())
            LegacyPlayerControlButton.incrementUpperButtonCount();
    }

    private static LegacyPlayerControlButton legacy;

    // The shared top-control hook supports both modern and legacy player styles.
    public static void initializeLegacyButton(View view) {
        try {
            legacy =
                    new LegacyPlayerControlButton(
                            view,
                            "series_tracker_button",
                            null,
                            "series_tracker_button",
                            Settings.SERIES_TRACKER_BUTTON,
                            v -> PlayerSeriesAction.open(v.getContext()),
                            null);
        } catch (Exception e) {
            Logger.printException(() -> "Series top button", e);
        }
    }

    private SeriesPlayerButton() {}
}
