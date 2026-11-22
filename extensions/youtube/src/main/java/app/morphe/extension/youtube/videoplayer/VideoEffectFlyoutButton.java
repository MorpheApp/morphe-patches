/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5137
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.patches.VideoEffectDialog;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.patches.LoadVideoPatch;
import app.morphe.extension.youtube.patches.utils.FlyoutUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PipDismissHelper;

@SuppressWarnings("unused")
public class VideoEffectFlyoutButton {

    /**
     * Adds a menu item to the player settings flyout menu.
     */
    public static int addFlyoutButton(Object flyoutPanel, int nextButtonIndex, boolean isTopFlyout) {
        if (Settings.VIDEO_EFFECT_FLYOUT_BUTTON.get() && isTopFlyout) {
            nextButtonIndex = FlyoutUtils.addFlyoutButton(
                    flyoutPanel,
                    ResourceUtils.getDrawable("morphe_ic_video_effect"),
                    str("morphe_video_effect_title"),
                    v -> {
                        FlyoutUtils.dismissFlyout();
                        showDialog(v.getContext());
                    },
                    nextButtonIndex
            );
        }
        return nextButtonIndex;
    }

    private static void showDialog(Context context) {
        try {
            SheetBottomDialog.SlideDialog dialog = VideoEffectDialog.create(
                    context,
                    LegacyPlayerControlButton.getDialogBackgroundColor(),
                    LegacyPlayerControlButton.fadeInDuration,
                    effectDialog -> {
                        effectDialog.dismiss();
                        LoadVideoPatch.initializeReloadVideo();
                    }
            );
            PipDismissHelper.dismissOnPip(dialog);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showDialog failure", ex);
        }
    }
}
