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

import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class LoopVideoButton {
    private static final int LOOP_VIDEO_ON = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE, "morphe_loop_video_button_on");
    private static final int LOOP_VIDEO_OFF = ResourceUtils.getIdentifierOrThrow(
            ResourceType.DRAWABLE,"morphe_loop_video_button_off");

    private static WeakReference<ImageView> instance = new WeakReference<>(null);

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            instance = new WeakReference<>(PlayerOverlayButton.addButton(controlsView,
                    "morphe_loop_video_button_off",
                    view -> updateButtonAppearance(true, view),
                    null
            ));

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
                    ImageView button = instance.get();
                    if (button != null) {
                        // TODO
//                        button.setIcon(newState ? LOOP_VIDEO_ON : LOOP_VIDEO_OFF);
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
     * Updates the button's appearance.
     */
    private static void updateButtonAppearance(boolean userClickedButton, @Nullable View buttonView) {
        if (instance == null) return;

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
                // FIXME
//                instance.setIcon(currentState ? LOOP_VIDEO_ON : LOOP_VIDEO_OFF);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateButtonAppearance failure", ex);
        }
    }
}
