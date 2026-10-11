/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3397
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.graphics.drawable.Drawable;
import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.PictureinPictureButtonPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PictureinPictureButton {

    private static final String pipButtonName = str("morphe_pip_button_flyout_name");

    static {
        if (Settings.PIP_OVERLAY_BUTTON.get() && PictureinPictureButtonPatch.isPipSupported()) {
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
                    () -> (Settings.PIP_OVERLAY_BUTTON.get() && PictureinPictureButtonPatch.isPipSupported())
                            ? LegacyPlayerControlButton.ButtonVisibility.ENABLED
                            : LegacyPlayerControlButton.ButtonVisibility.DISABLED,
                    v -> PictureinPictureButtonPatch.enterPictureInPicture(),
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex, boolean isTopFlyout, boolean isShortFlyout) {
        if (Settings.PIP_FLYOUT_BUTTON.get() && (isTopFlyout || isShortFlyout)) {

            Drawable icon = ResourceUtils.getDrawable(Utils.appIsUsingBoldIcons()
                    ? "morphe_pip_button_bold"
                    : "morphe_pip_button");

            nextButtonIndex = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    icon,
                    pipButtonName,
                    v -> PictureinPictureButtonPatch.enterPictureInPicture(),
                    nextButtonIndex
            );
        }
        return nextButtonIndex;
    }
}
