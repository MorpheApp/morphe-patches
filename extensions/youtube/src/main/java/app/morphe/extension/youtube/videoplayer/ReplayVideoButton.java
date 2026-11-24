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
import app.morphe.extension.shared.settings.preference.SeekBarPreference;
import app.morphe.extension.youtube.patches.LegacyPlayerControlsPatch;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.playback.speed.ReplayVideoController;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.VideoState;

@SuppressWarnings("unused")
public final class ReplayVideoButton {
    private static final String BUTTON_ID = "morphe_replay_video_button";
    private static final String ICON = "morphe_replay_video_button";
    private static final String ICON_ACTIVE = "morphe_replay_video_button_active";

    @Nullable
    private static LegacyPlayerControlButton legacy;

    private static WeakReference<ImageView> overlayButtonRef = new WeakReference<>(null);

    private static final ReplayVideoController controller = new ReplayVideoController(
            new ReplayVideoController.Player() {
                @Override
                public String videoId() {
                    return VideoInformation.getVideoId();
                }

                @Override
                public long position() {
                    return VideoInformation.getVideoTime();
                }

                @Override
                public float speed() {
                    return VideoInformation.getPlaybackSpeed();
                }

                @Override
                public long elapsedRealtime() {
                    return SystemClock.elapsedRealtime();
                }

                @Override
                public boolean seekTo(long position) {
                    return VideoInformation.seekTo(position);
                }

                @Override
                public void changeSpeed(float speed) {
                    VideoInformation.changePlaybackSpeed(speed);
                }
            });

    /**
     * Observers are added with the first replay, so the button does nothing until it is used.
     */
    private static boolean observersAdded;

    private static boolean positionCheckScheduled;

    @SuppressWarnings("SameReturnValue")
    public static boolean isPatchIncluded() {
        return false;
    }

    /**
     * Injection point.
     */
    public static void initializeButton(View controlsView) {
        try {
            if (LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                    || !Settings.REPLAY_VIDEO_BUTTON.get()) {
                return;
            }

            ImageView button = PlayerOverlayButton.addButton(
                    controlsView,
                    getIconName(),
                    ReplayVideoButton::isAvailable,
                    view -> toggleReplay(),
                    null
            );
            overlayButtonRef = new WeakReference<>(button);
            if (button != null) {
                setAccessibility(button);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "initializeButton failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void initializeLegacyButton(View controlsView) {
        try {
            if (!LegacyPlayerControlsPatch.RESTORE_OLD_PLAYER_BUTTONS
                    || !Settings.REPLAY_VIDEO_BUTTON.get()) {
                return;
            }

            legacy = new LegacyPlayerControlButton(
                    controlsView,
                    BUTTON_ID,
                    null,
                    getIconName(),
                    () -> isAvailable()
                            ? LegacyPlayerControlButton.ButtonVisibility.ENABLED
                            : LegacyPlayerControlButton.ButtonVisibility.DISABLED,
                    view -> toggleReplay(),
                    null
            );
            setAccessibility(Utils.getChildViewByResourceName(controlsView, BUTTON_ID));
        } catch (Exception ex) {
            Logger.printException(() -> "initializeLegacyButton failure", ex);
        }
    }

    /**
     * Injection point, before a native absolute or relative seek.
     */
    public static void onSeek() {
        if (!controller.isActive()) return;
        runAction(controller::onSeek);
    }

    /**
     * Injection point, when the player controller of a new video is created.
     * The current video ID is still the one of the previous video.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        if (!controller.isActive()) return;
        runAction(() -> controller.cancel(true));
    }

    private static boolean isAvailable() {
        return Settings.REPLAY_VIDEO_BUTTON.get()
                && !VideoInformation.lastVideoIdIsShort()
                && !VideoInformation.isLiveStream();
    }

    private static void setAccessibility(View button) {
        button.setContentDescription(str("morphe_replay_video_start"));
        PlayerOverlayButton.setToggleAccessibilityDelegate(button, controller::isActive);
    }

    private static void addObservers() {
        if (observersAdded) return;
        observersAdded = true;

        VideoState.getOnChange().addObserver(state -> {
            if (!controller.isActive()) return;
            if (state == VideoState.ENDED || state == VideoState.UNRECOVERABLE_ERROR) {
                runAction(() -> controller.cancel(true));
            } else if (state == VideoState.PLAYING) {
                Utils.runOnMainThreadNowOrLater(ReplayVideoButton::schedulePositionCheck);
            }
        });

        PlayerType.getOnChange().addObserver(type -> {
            if (!controller.isActive()) return;
            if (type.isNoneOrHidden()
                    || type == PlayerType.WATCH_WHILE_SLIDING_MINIMIZED_DISMISSED
                    || type == PlayerType.WATCH_WHILE_SLIDING_FULLSCREEN_DISMISSED) {
                runAction(() -> controller.cancel(true));
            }
        });

        VideoInformation.onPlaybackSpeedChange.addObserver(speed -> {
            if (!controller.isActive()) return;
            runAction(() -> controller.onSpeedChanged(speed));
        });

        VideoInformation.onUserSelectedPlaybackSpeed.addObserver(speed -> {
            if (!controller.isActive()) return;
            runAction(controller::onUserSelectedSpeed);
        });
    }

    private static void toggleReplay() {
        runAction(() -> {
            if (controller.isActive()) {
                controller.cancel(true);
                return;
            }
            startReplay(SeekBarPreference.clampToRange(Settings.REPLAY_VIDEO_SECONDS));
        });
    }

    /**
     * Starts a replay with the rewind selected by a swipe, or restarts the active replay.
     */
    public static void replayFromSwipe(int rewindSeconds) {
        runAction(() -> startReplay(rewindSeconds));
    }

    private static void startReplay(int rewindSeconds) {
        if (!isAvailable()) return;
        float speed = Settings.REPLAY_VIDEO_SPEED.get();
        if (!(speed > 0 && speed <= VideoInformation.PLAYBACK_SPEED_MAXIMUM)) {
            Utils.showToastLong(str("morphe_replay_video_speed_invalid"));
            speed = Settings.REPLAY_VIDEO_SPEED.resetToDefault();
        }
        addObservers();
        final long slowPlaybackMs = SeekBarPreference.clampToRange(Settings.REPLAY_VIDEO_SLOW_SECONDS) * 1000L;
        if (controller.start(rewindSeconds * 1000L, slowPlaybackMs, speed)) {
            schedulePositionCheck();
        } else {
            Utils.showToastShort(str("morphe_replay_video_unavailable"));
        }
    }

    private static void schedulePositionCheck() {
        if (positionCheckScheduled || !controller.isActive()
                || VideoState.getCurrent() == VideoState.PAUSED) {
            return;
        }

        positionCheckScheduled = true;
        Utils.runOnMainThreadDelayed(() -> {
            positionCheckScheduled = false;
            if (!controller.isActive()) return;

            runAction(() -> {
                if (VideoState.getCurrent() != VideoState.PAUSED) {
                    controller.tick();
                    schedulePositionCheck();
                }
            });
        }, controller.nextCheckDelayMillis());
    }

    /**
     * Runs a replay action on the main thread, and updates the button if the replay started or ended.
     */
    private static void runAction(Runnable action) {
        Utils.runOnMainThreadNowOrLater(() -> {
            final boolean wasActive = controller.isActive();
            try {
                action.run();
            } catch (Exception ex) {
                Logger.printException(() -> "Replay video failure", ex);
            }
            if (wasActive != controller.isActive()) {
                updateButtonIcon();
            }
        });
    }

    private static void updateButtonIcon() {
        try {
            final int icon = PlayerIcons.id(getIconBaseName());

            ImageView overlayButton = overlayButtonRef.get();
            if (overlayButton != null) {
                overlayButton.setImageResource(icon);
            }

            if (legacy != null) {
                legacy.setIcon(icon);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "updateButtonIcon failure", ex);
        }
    }

    private static String getIconName() {
        return PlayerIcons.name(getIconBaseName());
    }

    private static String getIconBaseName() {
        return controller.isActive() ? ICON_ACTIVE : ICON;
    }
}
