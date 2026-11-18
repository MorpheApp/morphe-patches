/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5006
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.videoplayer;

import static app.morphe.extension.shared.StringRef.str;

import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.playback.speed.ReplayVideoController;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

@SuppressWarnings("unused")
public final class ReplayVideoButton {
    private static final int MAX_REWIND_SECONDS = 60;
    private static final String ICON = "morphe_replay_video_button";

    @Nullable
    private static LegacyPlayerControlButton legacy;
    private static WeakReference<ImageView> overlayRef = new WeakReference<>(null);
    private static WeakReference<View> legacyViewRef = new WeakReference<>(null);
    @Nullable
    private static volatile ReplayVideoController controller;
    private static boolean observersRegistered;
    private static boolean positionCheckScheduled;

    /** Injection point. */
    public static void initializeButton(View sourceButton) {
        if (!Settings.REPLAY_VIDEO_BUTTON.get()) return;
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) return;
            ImageView button = PlayerOverlayButton.addButton(sourceButton, PlayerIcons.name(ICON),
                    ReplayVideoButton::isAvailable, view -> toggleReplay(), null);
            overlayRef = new WeakReference<>(button);
            if (button != null) {
                PlayerOverlayButton.setToggleAccessibilityDelegate(button, ReplayVideoButton::isActive);
            }
            updateAppearance();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /** Injection point. */
    public static void initializeLegacyButton(View controlsView) {
        if (!Settings.REPLAY_VIDEO_BUTTON.get()) return;
        try {
            if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS) return;
            legacy = new LegacyPlayerControlButton(controlsView, ICON, null, PlayerIcons.name(ICON),
                    () -> isAvailable() ? LegacyPlayerControlButton.ButtonVisibility.ENABLED
                            : LegacyPlayerControlButton.ButtonVisibility.DISABLED,
                    view -> toggleReplay(), null);
            View button = Utils.getChildViewByResourceName(controlsView, ICON);
            legacyViewRef = new WeakReference<>(button);
            if (button != null) {
                PlayerOverlayButton.setToggleAccessibilityDelegate(button, ReplayVideoButton::isActive);
            }
            updateAppearance();
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    private static boolean isAvailable() {
        return Settings.REPLAY_VIDEO_BUTTON.get() && !VideoInformation.lastVideoIdIsShort()
                && !VideoInformation.isLiveStream();
    }

    private static boolean isActive() {
        return controller != null && controller.isActive();
    }

    private static ReplayVideoController getController() {
        if (controller == null) {
            controller = new ReplayVideoController(new ReplayVideoController.Player() {
                @Override public String videoId() { return VideoInformation.getVideoId(); }
                @Override public long position() { return VideoInformation.getVideoTime(); }
                @Override public float speed() { return VideoInformation.getPlaybackSpeed(); }
                @Override public long elapsedRealtime() { return SystemClock.elapsedRealtime(); }
                @Override public boolean seekTo(long position) { return VideoInformation.seekTo(position); }
                @Override public void changeSpeed(float speed) { VideoInformation.changePlaybackSpeed(speed); }
            });
        }
        if (!observersRegistered) {
            observersRegistered = true;
            VideoState.getOnChange().addObserver(state -> {
                if (!isActive()) return;
                Utils.runOnMainThreadNowOrLater(() -> {
                    if (state == VideoState.ENDED || state == VideoState.UNRECOVERABLE_ERROR) {
                        runAction(() -> controller.cancel(true));
                    } else if (state == VideoState.PLAYING) {
                        schedulePositionCheck();
                    }
                });
            });
            PlayerType.getOnChange().addObserver(type -> {
                if (!isActive()) return;
                if (type.isNoneOrHidden() || type == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED
                        || type == PlayerType.WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED) {
                    runAction(() -> controller.cancel(true));
                }
            });
        }
        return controller;
    }

    private static void toggleReplay() {
        runAction(() -> {
            ReplayVideoController replay = getController();
            if (replay.isActive()) {
                replay.cancel(true);
                return;
            }
            if (!isAvailable()) return;
            int seconds = Settings.REPLAY_VIDEO_SECONDS.get();
            if (seconds < 1 || seconds > MAX_REWIND_SECONDS) {
                Settings.REPLAY_VIDEO_SECONDS.resetToDefault();
                seconds = Settings.REPLAY_VIDEO_SECONDS.get();
            }
            int slowSeconds = Settings.REPLAY_VIDEO_SLOW_SECONDS.get();
            if (slowSeconds < 1 || slowSeconds > MAX_REWIND_SECONDS) {
                Settings.REPLAY_VIDEO_SLOW_SECONDS.resetToDefault();
                slowSeconds = Settings.REPLAY_VIDEO_SLOW_SECONDS.get();
            }
            float speed = Settings.REPLAY_VIDEO_SPEED.get();
            if (!(speed >= 0.1f && speed <= VideoInformation.PLAYBACK_SPEED_MAXIMUM)) {
                Settings.REPLAY_VIDEO_SPEED.resetToDefault();
                speed = Settings.REPLAY_VIDEO_SPEED.get();
            }
            if (!replay.start(seconds * 1000L, slowSeconds * 1000L, speed)) {
                Utils.showToastShort(str("morphe_replay_video_unavailable"));
            }
            schedulePositionCheck();
        });
    }

    private static void schedulePositionCheck() {
        if (positionCheckScheduled || !isActive() || VideoState.getCurrent() == VideoState.PAUSED) return;
        positionCheckScheduled = true;
        Utils.runOnMainThreadDelayed(() -> {
            positionCheckScheduled = false;
            if (!isActive()) return;
            runAction(() -> {
                if (!Settings.REPLAY_VIDEO_BUTTON.get()) {
                    controller.cancel(true);
                } else if (VideoState.getCurrent() != VideoState.PAUSED) {
                    controller.tick();
                    schedulePositionCheck();
                }
            });
        }, controller.nextCheckDelayMillis());
    }

    /** Injection point, before the native absolute or relative seek. */
    public static void onSeek() {
        if (!Settings.REPLAY_VIDEO_BUTTON.get() || !isActive()) return;
        runAction(() -> controller.onSeek());
    }

    /** Injection point, before a manual selection in either speed menu. */
    public static void onUserSelectedSpeed(float speed) {
        if (!Settings.REPLAY_VIDEO_BUTTON.get() || !isActive()) return;
        runAction(() -> controller.onUserSelectedSpeed());
    }

    /** Injection point. */
    public static void onSpeedChanged(float speed) {
        if (!Settings.REPLAY_VIDEO_BUTTON.get() || !isActive()) return;
        runAction(() -> controller.onSpeedChanged(speed));
    }

    /** Injection point, before VideoInformation switches to the new player controller. */
    public static void onVideoReset() {
        if (!Settings.REPLAY_VIDEO_BUTTON.get() || !isActive()) return;
        runAction(() -> controller.cancel(true));
    }

    private static void runAction(Runnable action) {
        Utils.runOnMainThreadNowOrLater(() -> {
            final boolean wasActive = isActive();
            try {
                action.run();
            } catch (Exception ex) {
                Logger.printException(() -> "Replay video failure", ex);
                try {
                    if (controller != null) controller.cancel(true);
                } catch (Exception restoreException) {
                    Logger.printException(() -> "Restore replay speed failure", restoreException);
                }
            }
            try {
                if (wasActive != isActive()) updateAppearance();
            } catch (Exception ex) {
                Logger.printException(() -> "Update replay button failure", ex);
            }
        });
    }

    private static void updateAppearance() {
        final boolean active = isActive();
        final int icon = PlayerIcons.id(active ? ICON + "_active" : ICON);
        String description = str(active ? "morphe_replay_video_cancel" : "morphe_replay_video_start");
        ImageView overlay = overlayRef.get();
        if (overlay != null) {
            overlay.setImageResource(icon);
            overlay.setContentDescription(description);
        }
        if (legacy != null) legacy.setIcon(icon);
        View legacyView = legacyViewRef.get();
        if (legacyView != null) legacyView.setContentDescription(description);
    }
}
