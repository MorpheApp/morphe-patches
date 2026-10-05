/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3397
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.PictureinPictureButtonPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PictureinPictureButton {

    static {
        if (Settings.PIP_BUTTON_OVERLAY.get() && PictureinPictureButtonPatch.isPipSupported()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_pip_button",
                    null,
                    "morphe_pip_button",
                    () -> (Settings.PIP_BUTTON_OVERLAY.get() && PictureinPictureButtonPatch.isPipSupported())
                            ? LegacyPlayerControlButton.ButtonVisibility.ENABLED
                            : LegacyPlayerControlButton.ButtonVisibility.DISABLED,
                    v -> PictureinPictureButtonPatch.enterPictureInPicture(),
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }
}
