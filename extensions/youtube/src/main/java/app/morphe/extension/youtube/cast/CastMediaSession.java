/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5484
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.cast;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.VolumeProvider;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONObject;

/**
 * Media session of a cast, so the system media controls, the lock screen and the volume keys
 * control the playback on the Cast device.
 */
final class CastMediaSession {

    interface Controls {
        void play();

        void pause();

        void seek(double seconds);

        void stop();

        void setVolume(double level);
    }

    private static final String TAG = "MorpheCastAdFree";
    /**
     * Volume steps, like the 5% steps of Cast devices.
     */
    private static final int VOLUME_STEPS = 20;
    private static final long ACTIONS = PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE
            | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_SEEK_TO | PlaybackState.ACTION_STOP;

    private final MediaSession session;
    private final VolumeProvider volume;
    private final String title;
    private long durationMilliseconds = -1;

    /**
     * The callbacks are called on the main thread.
     */
    CastMediaSession(Context context, String title, Controls controls) {
        this.title = title;
        session = new MediaSession(context, TAG);
        session.setCallback(new MediaSession.Callback() {
            @Override
            public void onPlay() {
                controls.play();
            }

            @Override
            public void onPause() {
                controls.pause();
            }

            @Override
            public void onSeekTo(long position) {
                controls.seek(position / 1000.0);
            }

            @Override
            public void onStop() {
                controls.stop();
            }
        }, new Handler(Looper.getMainLooper()));
        volume = new VolumeProvider(VolumeProvider.VOLUME_CONTROL_ABSOLUTE, VOLUME_STEPS, VOLUME_STEPS) {
            @Override
            public void onSetVolumeTo(int steps) {
                setVolume(steps);
            }

            @Override
            public void onAdjustVolume(int direction) {
                setVolume(getCurrentVolume() + direction);
            }

            private void setVolume(int steps) {
                final int clamped = Math.max(0, Math.min(VOLUME_STEPS, steps));
                setCurrentVolume(clamped);
                controls.setVolume((double) clamped / VOLUME_STEPS);
            }
        };
        session.setPlaybackToRemote(volume);
        setMetadata();
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(PlaybackState.STATE_CONNECTING, 0, 0)
                .setActions(PlaybackState.ACTION_STOP)
                .build());
        session.setActive(true);
    }

    MediaSession.Token getToken() {
        return session.getSessionToken();
    }

    /**
     * @param status Media status of the receiver.
     */
    void update(JSONObject status) {
        @Nullable JSONObject media = status.optJSONObject("media");
        if (media != null && media.has("duration")) {
            final long duration = (long) (media.optDouble("duration") * 1000);
            if (duration != durationMilliseconds) {
                durationMilliseconds = duration;
                setMetadata();
            }
        }
        final int state = switch (status.optString("playerState")) {
            case "PLAYING" -> PlaybackState.STATE_PLAYING;
            case "PAUSED" -> PlaybackState.STATE_PAUSED;
            case "BUFFERING", "LOADING" -> PlaybackState.STATE_BUFFERING;
            default -> PlaybackState.STATE_STOPPED;
        };
        session.setPlaybackState(new PlaybackState.Builder()
                .setState(state, (long) (status.optDouble("currentTime") * 1000),
                        state == PlaybackState.STATE_PLAYING ? (float) status.optDouble("playbackRate", 1) : 0,
                        SystemClock.elapsedRealtime())
                .setActions(ACTIONS)
                .build());
    }

    /**
     * @param level Volume of the Cast device, from 0 to 1.
     */
    void setVolume(double level) {
        volume.setCurrentVolume((int) Math.round(level * VOLUME_STEPS));
    }

    void release() {
        session.setActive(false);
        session.release();
    }

    private void setMetadata() {
        MediaMetadata.Builder metadata = new MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title);
        if (durationMilliseconds > 0) {
            metadata.putLong(MediaMetadata.METADATA_KEY_DURATION, durationMilliseconds);
        }
        session.setMetadata(metadata.build());
    }
}
