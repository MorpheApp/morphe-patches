/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2624
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.PlayerVolumePatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class MuteVideoButton {

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static WeakReference<ImageView> overlayButtonRef = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                    || !Settings.MUTE_VIDEO_BUTTON.get()) {
                return;
            }

            overlayButtonRef = new WeakReference<>(PlayerOverlayButton.addButton(
                    controlsView,
                    getIconName(),
                    view -> toggleMute(),
                    null
            ));
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) {
                return;
            }

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_mute_video_button",
                    null,
                    getIconName(),
                    Settings.MUTE_VIDEO_BUTTON,
                    view -> toggleMute(),
                    null
            );
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    /**
     * Injection point.
     * <p>
     * Mute is not persisted, since a forgotten mute looks like broken audio.
     */
    public static void resetMuteButton() {
        if (!PlayerVolumePatch.isMuted()) return;

        Utils.runOnMainThread(() -> {
            try {
                PlayerVolumePatch.setMuted(false);
                updateButtonIcon();
            } catch (Exception ex) {
                Logger.printException(() -> "resetMuteButton failure", ex);
            }
        });
    }

    private static void toggleMute() {
        try {
            PlayerVolumePatch.setMuted(!PlayerVolumePatch.isMuted());
            updateButtonIcon();
        } catch (Exception ex) {
            Logger.printException(() -> "toggleMute failure", ex);
        }
    }

    private static void updateButtonIcon() {
        Utils.verifyOnMainThread();

        final int icon = ResourceUtils.getIdentifierOrThrow(ResourceType.DRAWABLE, getIconName());

        ImageView overlayButton = overlayButtonRef.get();
        if (overlayButton != null) {
            overlayButton.setImageResource(icon);
        }

        if (legacy != null) {
            legacy.setIcon(icon);
        }
    }

    private static String getIconName() {
        String base = PlayerVolumePatch.isMuted()
                ? "morphe_mute_video_button_on"
                : "morphe_mute_video_button_off";
        return LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                ? base
                : base + "_bold";
    }
}
