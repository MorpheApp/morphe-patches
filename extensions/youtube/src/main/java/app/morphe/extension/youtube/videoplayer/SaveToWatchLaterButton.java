/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.SaveToWatchLaterPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SaveToWatchLaterButton {

    static {
        if (Settings.SAVE_TO_WATCH_LATER_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    @Nullable
    private static LegacyPlayerControlButton instance;

    /**
     * injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            instance = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_save_to_watch_later_button",
                    null,
                    "morphe_save_to_watch_later_button",
                    Settings.SAVE_TO_WATCH_LATER_BUTTON::get,
                    v -> SaveToWatchLaterPatch.saveVideo(),
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * injection point.
     */
    public static void setVisibilityNegatedImmediate() {
        if (instance != null) instance.setVisibilityNegatedImmediate();
    }

    /**
     * injection point.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (instance != null) instance.setVisibilityImmediate(visible);
    }

    /**
     * injection point.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (instance != null) instance.setVisibility(visible, animated);
    }
}
