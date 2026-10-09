/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3114
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.series;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton;

public final class SeriesPlayerButton {
    static {
        if (SeriesTrackerPatch.ENABLED && Settings.SERIES_TRACKER_BUTTON.get())
            LegacyPlayerControlButton.incrementUpperButtonCount();
    }

    private static LegacyPlayerControlButton legacy;

    // The shared top-control hook supports both modern and legacy player styles.
    public static void initializeLegacyButton(View view) {
        if (!SeriesTrackerPatch.ENABLED) return;
        try {
            legacy = new LegacyPlayerControlButton(
                    view,
                    "morphe_series_tracker_button",
                    null,
                    "morphe_series_tracker_button",
                    Settings.SERIES_TRACKER_BUTTON,
                    v -> PlayerSeriesAction.open(v.getContext()),
                    null);
        } catch (Exception e) {
            Logger.printException(() -> "Series top button", e);
        }
    }

    private SeriesPlayerButton() {}
}
