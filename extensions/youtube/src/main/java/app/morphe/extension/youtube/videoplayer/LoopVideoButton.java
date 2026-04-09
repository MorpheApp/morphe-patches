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

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS;

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class LoopVideoButton {

    static {
        if (Settings.LOOP_VIDEO_BUTTON.get()) {
            LegacyPlayerControlButton.incrementUpperButtonCount();
        }
    }

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static final int LOOP_VIDEO_ON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            RESTORE_OLD_PLAYER_BUTTONS
                    ? "morphe_loop_video_button_on"
                    : "morphe_loop_video_button_on_bold");
    private static final int LOOP_VIDEO_OFF = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,
            RESTORE_OLD_PLAYER_BUTTONS
                    ? "morphe_loop_video_button_off"
                    : "morphe_loop_video_button_off_bold");

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    "morphe_loop_video_button",
                    null,
                    null,
                    Settings.LOOP_VIDEO_BUTTON::get,
                    v -> updateButtonAppearance(true, v),
                    null
            );
            // Set icon when initializing button based on current setting
            updateButtonAppearance(false, null);
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**
     * Animate button transition with fade and scale.
     */
    private static void animateButtonTransition(View view, boolean newState) {
        if (!(view instanceof ImageView imageView)) return;

        // Fade out.
        imageView.animate()
                .alpha(0.3f)
                .scaleX(1.15f)
                .scaleY(1.15f)
                .setDuration(100)
                .withEndAction(() -> {
                    if (legacy != null) {
                        legacy.setIcon(newState ? LOOP_VIDEO_ON : LOOP_VIDEO_OFF);
                    }

                    // Fade in.
                    imageView.animate()
                            .alpha(1.0f)
                            .scaleX(1.0f)
                            .scaleY(1.0f)
                            .setDuration(100)
                            .start();
                })
                .start();
    }

    /**
     * injection point.
     */
    public static void setVisibilityNegatedImmediate() {
        if (legacy != null) legacy.setVisibilityNegatedImmediate();
    }

    /**
     * injection point.
     */
    public static void setVisibilityImmediate(boolean visible) {
        if (legacy != null) legacy.setVisibilityImmediate(visible);
        if (visible) {
            updateIconFromSettings();
        }
    }

    /**
     * injection point.
     */
    public static void setVisibility(boolean visible, boolean animated) {
        if (legacy != null) legacy.setVisibility(visible, animated);
        if (visible) {
            updateIconFromSettings();
        }
    }

    /**
     * Update icon based on current setting value.
     */
    private static void updateIconFromSettings() {
        LegacyPlayerControlButton localInstance = legacy;
        if (localInstance == null) return;

        final boolean currentState = Settings.LOOP_VIDEO.get();
        localInstance.setIcon(currentState ? LOOP_VIDEO_ON : LOOP_VIDEO_OFF);
    }

    /**
     * Updates the button's appearance.
     */
    private static void updateButtonAppearance(boolean userClickedButton, @Nullable View buttonView) {
        if (legacy == null) return;

        try {
            Utils.verifyOnMainThread();

            final boolean currentState = Settings.LOOP_VIDEO.get();

            if (userClickedButton) {
                final boolean newState = !currentState;

                Settings.LOOP_VIDEO.save(newState);
                Utils.showToastShort(str(newState
                        ? "morphe_loop_video_button_toast_on"
                        : "morphe_loop_video_button_toast_off"));

                // Animate with the new state.
                if (buttonView != null) {
                    animateButtonTransition(buttonView, newState);
                }
            } else {
                // Initialization - just set icon based on current state.
                legacy.setIcon(currentState ? LOOP_VIDEO_ON : LOOP_VIDEO_OFF);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateButtonAppearance failure", ex);
        }
    }
}
