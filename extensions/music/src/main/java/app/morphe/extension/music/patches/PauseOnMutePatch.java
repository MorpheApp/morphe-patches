/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Pauses playback when the media volume is muted, and resumes it when the volume is raised again.
 *
 * The volume is watched with the broadcasts the audio service sends, and with a settings observer
 * as a fallback. Both run on a dedicated thread, because callbacks posted to the main thread can
 * be delayed a lot while the app is in the background, and the pause would be delayed with them.
 */
@SuppressWarnings({"unused", "deprecation"})
public final class PauseOnMutePatch {

    /**
     * Sent by the audio service right after the volume of a stream changes, and when a stream is
     * muted or unmuted. These are hidden framework constants, hence the literal values.
     */
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";

    private static HandlerThread handlerThread;
    private static Handler handler;

    private static AudioManager audioManager;
    private static BroadcastReceiver volumeReceiver;
    private static ContentObserver volumeObserver;
    private static volatile MediaSession capturedSession;
    private static volatile MediaController mediaController;
    private static volatile boolean initialized;
    private static volatile boolean muted;
    private static volatile boolean pausedByMute;

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

            HandlerThread thread = new HandlerThread("MorphePauseOnMute");
            thread.start();
            Handler backgroundHandler = new Handler(thread.getLooper());
            handlerThread = thread;
            handler = backgroundHandler;

            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context receiverContext, Intent intent) {
                    if (intent == null) {
                        return;
                    }
                    int streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, -1);
                    if (streamType != -1 && streamType != AudioManager.STREAM_MUSIC) {
                        return;
                    }
                    onVolumeChanged();
                }
            };

            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_VOLUME_CHANGED);
            filter.addAction(ACTION_STREAM_MUTE_CHANGED);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(
                        receiver, filter, null, backgroundHandler, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter, null, backgroundHandler, 0);
            }
            volumeReceiver = receiver;

            // Fallback for devices that do not deliver the broadcasts to a background app.
            ContentObserver observer = new ContentObserver(backgroundHandler) {
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
        // covers muting that keeps the volume index.
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
                pausedByMute = false;
                return;
            }

            if (stillMuted) {
                pausePlayback();
            } else {
                resumePlayback();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "Pause on mute: volume change failure", ex);
        }
    }

    private static void pausePlayback() {
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
