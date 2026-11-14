/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4778
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

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
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Pauses playback when the media volume is muted, and resumes it when the volume is raised again.
 *
 * The volume is watched with the broadcasts the audio service sends, and with a settings observer
 * as a fallback. Both run on a dedicated thread, because callbacks posted to the main thread can
 * be delayed a lot while the app is in the background.
 */
@SuppressWarnings("unused")
public final class PauseWhenMutedPatch {

    /**
     * Sent by the audio service when the volume of a stream changes, and when a stream is
     * muted or unmuted. These are hidden framework constants, hence the literal values.
     */
    private static final String ACTION_VOLUME_CHANGED = "android.media.VOLUME_CHANGED_ACTION";
    private static final String ACTION_STREAM_MUTE_CHANGED = "android.media.STREAM_MUTE_CHANGED_ACTION";
    private static final String EXTRA_VOLUME_STREAM_TYPE = "android.media.EXTRA_VOLUME_STREAM_TYPE";
    private static final int STREAM_TYPE_UNKNOWN = -1;

    private static final boolean ENABLED = Settings.PAUSE_WHEN_MUTED.get();

    /**
     * Guarded by the class lock.
     */
    private static boolean listening;
    @Nullable
    private static volatile MediaSession mediaSession;
    @Nullable
    private static volatile MediaController mediaController;
    /**
     * Only changed on the listener thread, after it is set before the listeners are registered.
     */
    private static volatile boolean muted;
    /**
     * If playback is currently paused because the volume was muted.
     * Cleared when playback starts again any other way, so a later manual pause is respected.
     */
    private static volatile boolean pausedByMute;

    private PauseWhenMutedPatch() {
    }

    /**
     * Injection point. Called right before YouTube Music runs {@code MediaSession.setMetadata}.
     */
    public static void onSetMetadata(MediaSession session, MediaMetadata metadata) {
        if (!ENABLED) return;

        try {
            if (session == mediaSession) return;
            mediaSession = session;
            mediaController = session.getController();

            startListening();
        } catch (Exception ex) {
            Logger.printException(() -> "onSetMetadata failure", ex);
        }
    }

    /**
     * Injection point. Called right before YouTube Music runs {@code MediaSession.setPlaybackState}.
     */
    public static void onSetPlaybackState(PlaybackState state) {
        if (!ENABLED || state == null) return;

        int playbackState = state.getState();
        if (playbackState == PlaybackState.STATE_PLAYING
                || playbackState == PlaybackState.STATE_BUFFERING
                || playbackState == PlaybackState.STATE_CONNECTING) {
            pausedByMute = false;
        }
    }

    private static synchronized void startListening() {
        if (listening) return;
        listening = true;

        Context context = Utils.getContext();
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);

        HandlerThread thread = new HandlerThread("MorphePauseWhenMuted");
        thread.start();
        Handler handler = new Handler(thread.getLooper());

        muted = isMuted(manager);

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context receiverContext, Intent intent) {
                int streamType = intent.getIntExtra(EXTRA_VOLUME_STREAM_TYPE, STREAM_TYPE_UNKNOWN);
                if (streamType == STREAM_TYPE_UNKNOWN || streamType == AudioManager.STREAM_MUSIC) {
                    onVolumeChanged(manager);
                }
            }
        };
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_VOLUME_CHANGED);
        filter.addAction(ACTION_STREAM_MUTE_CHANGED);
        // Both actions are protected system broadcasts, so no exported flag is needed.
        context.registerReceiver(receiver, filter, null, handler);

        // Fallback for devices that do not deliver the broadcasts to a background app.
        context.getContentResolver().registerContentObserver(
                android.provider.Settings.System.CONTENT_URI,
                true,
                new ContentObserver(handler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        onVolumeChanged(manager);
                    }
                }
        );
    }

    private static boolean isMuted(AudioManager manager) {
        // isStreamMute() also covers muting that keeps the volume index.
        return manager.getStreamVolume(AudioManager.STREAM_MUSIC) == 0
                || manager.isStreamMute(AudioManager.STREAM_MUSIC);
    }

    /**
     * Called on the listener thread.
     */
    private static void onVolumeChanged(AudioManager manager) {
        try {
            MediaController controller = mediaController;
            if (controller == null) return;

            boolean nowMuted = isMuted(manager);
            if (nowMuted == muted) return;
            muted = nowMuted;

            PlaybackState state = controller.getPlaybackState();
            int playbackState = state == null ? PlaybackState.STATE_NONE : state.getState();

            if (nowMuted) {
                if (playbackState == PlaybackState.STATE_PLAYING
                        || playbackState == PlaybackState.STATE_BUFFERING) {
                    Logger.printDebug(() -> "Pausing playback, volume is muted");
                    pausedByMute = true;
                    controller.getTransportControls().pause();
                }
            } else if (pausedByMute) {
                pausedByMute = false;
                if (playbackState != PlaybackState.STATE_PLAYING) {
                    Logger.printDebug(() -> "Resuming playback, volume is unmuted");
                    controller.getTransportControls().play();
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onVolumeChanged failure", ex);
        }
    }
}
