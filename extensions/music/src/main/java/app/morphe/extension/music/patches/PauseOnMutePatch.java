/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.app.Activity;
import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Pauses playback when the media volume is muted, and resumes it when the volume is raised again.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class PauseOnMutePatch {

    /**
     * Delay before pausing, so that dragging the volume slider through zero does not pause
     * playback for only a fraction of a second.
     */
    private static final long PAUSE_DELAY_MILLIS = 250;

    private static final Handler mainHandler = new Handler(Looper.getMainLooper());

    private static AudioManager audioManager;
    private static volatile MediaSession capturedSession;
    private static volatile MediaController mediaController;
    private static ContentObserver volumeObserver;
    private static boolean initialized;
    private static boolean muted;
    private static boolean pausedByMute;

    private static final Runnable pausePlaybackTask = () -> {
        try {
            MediaController controller = mediaController;
            if (controller == null) {
                return;
            }
            PlaybackState state = controller.getPlaybackState();
            if (state == null) {
                return;
            }
            int playbackState = state.getState();
            if (playbackState != PlaybackState.STATE_PLAYING
                    && playbackState != PlaybackState.STATE_BUFFERING) {
                return;
            }
            pausedByMute = true;
            controller.getTransportControls().pause();
        } catch (Exception ex) {
            Logger.printException(() -> "Pause on mute: pause failure", ex);
        }
    };

    private PauseOnMutePatch() {
    }

    /**
     * Injection point. Called when the YouTube Music main activity is created.
     */
    public static void initialize(Activity activity) {
        if (initialized) {
            return;
        }

        try {
            Context context = activity.getApplicationContext();
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) {
                Logger.printException(() -> "Pause on mute: audio manager is not available");
                return;
            }

            audioManager = manager;
            muted = isMuted(manager);

            ContentObserver observer = new ContentObserver(mainHandler) {
                @Override
                public void onChange(boolean selfChange) {
                    onVolumeChanged();
                }

                @Override
                public void onChange(boolean selfChange, Uri uri) {
                    onVolumeChanged();
                }
            };

            context.getContentResolver().registerContentObserver(
                    android.provider.Settings.System.CONTENT_URI,
                    true,
                    observer
            );

            volumeObserver = observer;
            initialized = true;
        } catch (Exception ex) {
            Logger.printException(() -> "Pause on mute: initialization failure", ex);
        }
    }

    /**
     * Injection point. Called right before YouTube Music runs {@code MediaSession.setMetadata}.
     */
    public static void onSetMetadata(MediaSession session, MediaMetadata metadata) {
        if (session == null || session == capturedSession) {
            return;
        }
        capturedSession = session;

        Utils.runOnMainThread(() -> {
            try {
                mediaController = session.getController();
            } catch (Exception ex) {
                Logger.printException(() -> "Pause on mute: media session capture failure", ex);
            }
        });
    }

    private static boolean isMuted(AudioManager manager) {
        // getStreamVolume() returns 0 while the volume is held down, and isStreamMute() also
        // covers muting that keeps the volume index, such as the mute key or a Bluetooth mute.
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                || manager.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    private static void onVolumeChanged() {
        try {
            AudioManager manager = audioManager;
            if (manager == null) {
                return;
            }

            boolean stillMuted = isMuted(manager);
            if (stillMuted == muted) {
                return;
            }
            muted = stillMuted;

            if (!Settings.PAUSE_ON_MUTE.get()) {
                mainHandler.removeCallbacks(pausePlaybackTask);
                pausedByMute = false;
                return;
            }

            if (stillMuted) {
                // The observer fires for every step of the volume slider.
                mainHandler.removeCallbacks(pausePlaybackTask);
                mainHandler.postDelayed(pausePlaybackTask, PAUSE_DELAY_MILLIS);
            } else {
                mainHandler.removeCallbacks(pausePlaybackTask);
                resumePlayback();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Pause on mute: volume change failure", ex);
        }
    }

    private static void resumePlayback() {
        if (!pausedByMute) {
            return;
        }
        pausedByMute = false;

        try {
            MediaController controller = mediaController;
            if (controller == null) {
                return;
            }
            PlaybackState state = controller.getPlaybackState();
            if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                // Playback was already resumed another way after it was paused.
                return;
            }
            controller.getTransportControls().play();
        } catch (Exception ex) {
            Logger.printException(() -> "Pause on mute: resume failure", ex);
        }
    }
}
