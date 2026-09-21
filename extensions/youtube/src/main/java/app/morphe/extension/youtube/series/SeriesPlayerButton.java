package app.morphe.extension.youtube.series;

import android.view.View;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.StringRef;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;
import app.morphe.extension.youtube.videoplayer.PlayerOverlayButton;

public final class SeriesPlayerButton {
    static {
        if (Settings.SERIES_TRACKER_BUTTON.get()
                && LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS)
            LegacyPlayerControlButton.incrementUpperButtonCount();
    }

    private static LegacyPlayerControlButton legacy;

    public static void initializeButton(View view) {
        if (!Settings.SERIES_TRACKER_BUTTON.get()
                || LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) return;
        try {
            ImageView button = new ImageView(view.getContext());
            button.setContentDescription(StringRef.str("series_tracker_title"));
            PlayerOverlayButton.addButton(
                    view,
                    button,
                    "series_tracker_button",
                    v -> PlayerSeriesAction.open(v.getContext()),
                    null);
        } catch (Exception e) {
            Logger.printException(() -> "Series overlay button", e);
        }
    }

    public static void initializeLegacyButton(View view) {
        if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) return;
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
            Logger.printException(() -> "Series legacy button", e);
        }
    }

    private SeriesPlayerButton() {}
}
