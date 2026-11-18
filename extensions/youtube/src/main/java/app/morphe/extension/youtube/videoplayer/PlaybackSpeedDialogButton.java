/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.text.DecimalFormat;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.playback.speed.CustomPlaybackSpeedPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class PlaybackSpeedDialogButton {

    /**
     * Two speeds closer than this are the same speed in the cycle list.
     */
    private static final float CYCLE_SPEED_TOLERANCE = 0.01f;

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
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS ||
                    !Settings.PLAYBACK_SPEED_DIALOG_BUTTON.get()) {
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
            if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) {
                return;
            }

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_playback_speed_dialog_button_container",
                    "morphe_playback_speed_dialog_button",
                    "morphe_playback_speed_dialog_button_text",
                    null,
                    Settings.PLAYBACK_SPEED_DIALOG_BUTTON,
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
            if (Settings.PLAYBACK_SPEED_BUTTON_CYCLE.get()) {
                // Tap cycles, so tap and hold opens the speed menu.
                showSpeedDialog(view);
                return true;
            }
            try {
                final float defaultSpeed = Settings.PLAYBACK_SPEED_DEFAULT.get();
                final float speed = (!Settings.REMEMBER_PLAYBACK_SPEED_LAST_SELECTED.get() ||
                        VideoInformation.getPlaybackSpeed() == defaultSpeed)
                        ? 1.0f
                        : defaultSpeed;
                VideoInformation.changePlaybackSpeed(speed);
                if (speed == 1.0f) {
                    VideoInformation.setAudioPitch(1.0f);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "speed button long click failure", ex);
            }
            return true;
        };
    }

    private static View.OnClickListener getOnClickListener() {
        return view -> {
            if (Settings.PLAYBACK_SPEED_BUTTON_CYCLE.get()) {
                cycleSpeed();
            } else {
                showSpeedDialog(view);
            }
        };
    }

    private static void showSpeedDialog(View view) {
        try {
            if (Settings.RESTORE_OLD_SPEED_MENU.get()) {
                CustomPlaybackSpeedPatch.showOldPlaybackSpeedMenu();
            } else {
                CustomPlaybackSpeedPatch.showModernCustomPlaybackSpeedDialog(view.getContext());
            }
        } catch (Exception ex) {
            Logger.printException(() -> "showSpeedDialog failure", ex);
        }
    }

    /**
     * Changes to the speed after the current one in the cycle list.
     * A current speed not in the list goes to the first one.
     */
    private static void cycleSpeed() {
        try {
            final float[] speeds = loadCycleSpeeds();
            final float current = VideoInformation.getPlaybackSpeed();
            float next = speeds[0];
            for (int i = 0; i < speeds.length; i++) {
                if (Math.abs(speeds[i] - current) < CYCLE_SPEED_TOLERANCE) {
                    next = speeds[(i + 1) % speeds.length];
                    break;
                }
            }

            final float speed = next;
            Logger.printDebug(() -> "Speed button cycle: " + current + " -> " + speed);
            // Same as a speed picked in the speed menu, including "Remember playback speed".
            VideoInformation.setPlaybackSpeed(speed);
        } catch (Exception ex) {
            Logger.printException(() -> "cycleSpeed failure", ex);
        }
    }

    /**
     * @return The cycle speeds. An invalid list is reset to the default.
     */
    private static float[] loadCycleSpeeds() {
        StringSetting setting = Settings.PLAYBACK_SPEED_BUTTON_CYCLE_SPEEDS;
        try {
            // Same format as the custom playback speeds,
            // including commas for speeds in a localized format.
            String[] speedStrings = setting.get().trim().replace(',', '.').split("\\s+");
            float[] speeds = new float[speedStrings.length];
            for (int i = 0; i < speedStrings.length; i++) {
                final float speed = Float.parseFloat(speedStrings[i]);
                if (speed <= 0 || speed > VideoInformation.PLAYBACK_SPEED_MAXIMUM) {
                    throw new IllegalArgumentException("Invalid speed: " + speed);
                }
                speeds[i] = speed;
            }
            return speeds;
        } catch (IllegalArgumentException ex) { // NumberFormatException is a subclass.
            Logger.printInfo(() -> "Invalid speed cycle list: " + setting.get(), ex);
            Utils.showToastShort(str("morphe_playback_speed_button_cycle_speeds_invalid"));
            setting.resetToDefault();
            return loadCycleSpeeds();
        }
    }

    /**
     * Injection point.
     */
    public static void videoSpeedChanged(float currentVideoSpeed) {
        Utils.runOnMainThread(PlaybackSpeedDialogButton::updateButtonAppearance);
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
