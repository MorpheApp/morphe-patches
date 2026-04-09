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

import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.text.DecimalFormat;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.playback.speed.CustomPlaybackSpeedPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PlaybackSpeedDialogButton {

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static WeakReference<TextView> overlayTextRef = new WeakReference<>(null);

    private static final DecimalFormat speedDecimalFormatter = new DecimalFormat();
    static {
        speedDecimalFormatter.setMinimumFractionDigits(1);
        speedDecimalFormatter.setMaximumFractionDigits(2);
    }

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (RESTORE_OLD_PLAYER_BUTTONS || !Settings.PLAYBACK_SPEED_DIALOG_BUTTON.get()) {
                return;
            }

            overlayTextRef = new WeakReference<>(PlayerOverlayButton.addButtonWithTextOverlay(
                    controlsView,
                    getOnClickListener(),
                    getOnLongClickListener()
            ));

            // Set the appropriate icon.
            updateButtonAppearance();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!RESTORE_OLD_PLAYER_BUTTONS) {
                return;
            }

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_playback_speed_dialog_button_container",
                    "morphe_playback_speed_dialog_button",
                    "morphe_playback_speed_dialog_button_text",
                    null,
                    Settings.PLAYBACK_SPEED_DIALOG_BUTTON::get,
                    getOnClickListener(),
                    getOnLongClickListener()
            );

            // Set the appropriate icon.
            updateButtonAppearance();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    private static View.OnLongClickListener getOnLongClickListener() {
        return view -> {
            try {
                final float defaultSpeed = Settings.PLAYBACK_SPEED_DEFAULT.get();
                final float speed = (!Settings.REMEMBER_PLAYBACK_SPEED_LAST_SELECTED.get() ||
                        VideoInformation.getPlaybackSpeed() == defaultSpeed)
                        ? 1.0f
                        : defaultSpeed;
                VideoInformation.overridePlaybackSpeed(speed);
            } catch (Exception ex) {
                Logger.printException(() -> "speed button long click failure", ex);
            }
            return true;
        };
    }

    private static View.OnClickListener getOnClickListener() {
        return view -> {
            try {
                if (Settings.RESTORE_OLD_SPEED_MENU.get()) {
                    CustomPlaybackSpeedPatch.showOldPlaybackSpeedMenu();
                } else {
                    CustomPlaybackSpeedPatch.showModernCustomPlaybackSpeedDialog(view.getContext());
                }
            } catch (Exception ex) {
                Logger.printException(() -> "speed button onClick failure", ex);
            }
        };
    }

    /**
     * injection point.
     */
    public static void setVisibilityNegatedImmediate() {
        if (legacy != null) {
            legacy.setVisibilityNegatedImmediate();
        }
    }

    /**
     * Injection point.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (legacy != null) {
            legacy.setVisibilityImmediate(visible);
        }
    }

    /**
     * Injection point.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (legacy != null) {
            legacy.setVisibility(visible, animated);
        }
    }

    /**
     * Injection point.
     */
    public static void videoSpeedChanged(float currentVideoSpeed) {
        updateButtonAppearance();
    }

    /**
     * Updates the button's appearance, including icon and text overlay.
     */
    private static void updateButtonAppearance() {
        try {
            Utils.verifyOnMainThread();

            String speedText = speedDecimalFormatter.format(VideoInformation.getPlaybackSpeed());
            if (legacy != null) {
                legacy.setTextOverlay(speedText);
            }

            TextView overlay = overlayTextRef.get();
            if (overlay != null) {
                overlay.setText(speedText);
            }
            Logger.printDebug(() -> "Updated playback speed button text to: " + speedText);
        } catch (Exception ex) {
            Logger.printException(() -> "updateButtonAppearance failure", ex);
        }
    }
}
