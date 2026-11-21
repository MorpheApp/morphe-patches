/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5134
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.holdgesture;

import static app.morphe.extension.shared.StringRef.str;

import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.holdgesture.LauncherIcon.Glyph;
import app.morphe.extension.youtube.patches.LoopVideoPatch;
import app.morphe.extension.youtube.patches.SaveToWatchLaterPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.videoplayer.CopyVideoLinkButton;
import app.morphe.extension.youtube.videoplayer.LoopVideoButton;

/**
 * What an item of the launcher does with the video that plays.
 * The names are the values of the settings that put actions into the launcher.
 */
public enum LauncherAction {
    /** Keeps touches from the app until the screen is unlocked again. */
    SCREEN_LOCK(Glyph.SCREEN_LOCK),
    LOOP(Glyph.LOOP),
    /** Saves the picture of the video as an image. */
    SCREENSHOT(Glyph.CAMERA),
    WATCH_LATER(Glyph.CLOCK),
    /** Copies a link that opens the video at the time of now. */
    TIMESTAMP_URL(Glyph.LINK);

    /** What an action needs from the player. */
    interface Host {
        void lockScreen();

        /** A view inside of the player, if there is a player. */
        @Nullable
        View playerView();

        /** Shows that a picture of the video was taken. */
        void flash();
    }

    final Glyph glyph;

    LauncherAction(Glyph glyph) {
        this.glyph = glyph;
    }

    /**
     * @return False if the patch that the action belongs to was not used on the app.
     */
    public boolean isAvailable() {
        return switch (this) {
            case LOOP -> LoopVideoPatch.isPatchIncluded();
            case WATCH_LATER -> SaveToWatchLaterPatch.isPatchIncluded();
            default -> true;
        };
    }

    /**
     * @return Whether the action is something that is switched on now, and running it switches it off.
     */
    boolean isOn() {
        return this == LOOP && Settings.LOOP_VIDEO.get();
    }

    /** Says what running the action does now. */
    String label() {
        return str(switch (this) {
            case SCREEN_LOCK -> "morphe_hold_gesture_launcher_action_screen_lock";
            case LOOP -> isOn()
                    ? "morphe_hold_gesture_launcher_action_loop_off"
                    : "morphe_hold_gesture_launcher_action_loop";
            case SCREENSHOT -> "morphe_hold_gesture_launcher_action_screenshot";
            case WATCH_LATER -> "morphe_hold_gesture_launcher_action_watch_later";
            case TIMESTAMP_URL -> "morphe_hold_gesture_launcher_action_timestamp_url";
        });
    }

    void run(Host host) {
        switch (this) {
            case SCREEN_LOCK -> host.lockScreen();
            case LOOP -> {
                final boolean loop = !Settings.LOOP_VIDEO.get();
                LoopVideoButton.setLoopButton(loop);
                LoopVideoButton.updateButtonIcon();
                Utils.showSnackbarShort(str(loop
                        ? "morphe_loop_video_button_toast_on"
                        : "morphe_loop_video_button_toast_off"));
            }
            case SCREENSHOT -> VideoFrameSaver.save(host.playerView(), host::flash);
            case WATCH_LATER -> SaveToWatchLaterPatch.saveVideo(VideoInformation.getVideoId());
            case TIMESTAMP_URL -> CopyVideoLinkButton.copyLink(true);
        }
    }
}
