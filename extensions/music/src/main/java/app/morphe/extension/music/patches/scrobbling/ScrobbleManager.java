/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.scrobbling.lastfm.LastFM;
import app.morphe.extension.music.patches.scrobbling.listenbrainz.ListenBrainz;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * All methods must be called on main thread.
 */
public class ScrobbleManager {
    private static ScrobbleManager instance;

    public static ScrobbleManager getInstance() {
        Utils.verifyOnMainThread();
        if (instance == null) {
            instance = new ScrobbleManager();
        }
        return instance;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private String currentTitle;
    private String currentArtist;
    private String currentAlbum;
    private String currentSongId;
    private int currentDurationSeconds;

    private long songStartedAtSeconds;
    private boolean songStarted;

    // ListenBrainz Timer State
    private long lbScrobbleRemainingMillis;
    private long lbScrobbleTimerStartedAt;
    private boolean lbScrobbled;
    private Runnable lbRunnable;

    // Last.fm Timer State
    private long lfScrobbleRemainingMillis;
    private long lfScrobbleTimerStartedAt;
    private boolean lfScrobbled;
    private Runnable lfRunnable;

    private ScrobbleManager() {}

    public void onSetMetadata(MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null) return;

        try {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
            String songId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            long durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            int duration = (int) (durationMs / 1000);

            if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
                return;
            }

            // Check if it is a new song
            if (!title.equals(currentTitle) || !artist.equals(currentArtist)) {
                Logger.printDebug(() -> "new song detected: " + title + " - " + artist);
                stopTimers();
                songStarted = false;
                lbScrobbled = false;
                lfScrobbled = false;

                currentTitle = title;
                currentArtist = artist;
                currentAlbum = album;
                currentSongId = songId;
                currentDurationSeconds = duration;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSetMetadata failure", ex);
        }
    }

    public void onSetPlaybackState(PlaybackState state) {
        Utils.verifyOnMainThread();
        if (state == null) return;
        boolean isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
        onPlayerStateChanged(isPlaying);
    }

    public void onLikeClicked(String serviceName, String videoId) {
        Utils.verifyOnMainThread();
        if (serviceName == null || videoId == null) return;
        Logger.printDebug(() -> "onLikeClicked - serviceName: " + serviceName + " videoId: " + videoId);

        // Check if Last.fm scrobbling and love-on-like are enabled
        if (!Settings.LASTFM_SCROBBLING.get() || !Settings.LASTFM_LOVE_ON_LIKE.get()) {
            return;
        }

        // We only care about the currently playing song
        if (currentSongId != null && !videoId.equals(currentSongId)) {
            return;
        }

        if (currentTitle == null || currentArtist == null) {
            return;
        }

        String sk = Settings.LASTFM_SESSION_KEY.get();
        if (sk.isBlank()) {
            return;
        }

        if ("like/like".equals(serviceName)) {
            LastFM.love(sk, currentArtist, currentTitle);
        } else if ("like/removelike".equals(serviceName) || "like/dislike".equals(serviceName)) {
            LastFM.unlove(sk, currentArtist, currentTitle);
        }
    }


    private void onPlayerStateChanged(boolean isPlaying) {
        Utils.verifyOnMainThread();
        if (currentTitle == null || currentArtist == null) return;

        if (isPlaying) {
            if (!songStarted) {
                onSongStart();
            } else {
                onSongResume();
            }
        } else {
            onSongPause();
        }
    }

    private void onSongStart() {
        Utils.verifyOnMainThread();
        songStartedAtSeconds = System.currentTimeMillis() / 1000;
        songStarted = true;

        // ListenBrainz
        if (Settings.LISTENBRAINZ_SCROBBLING.get()) {
            startListenBrainzTimer();
            if (Settings.LISTENBRAINZ_NOW_PLAYING.get()) {
                ListenBrainz.updateNowPlayingAsync(currentArtist, currentTitle, currentSongId, currentAlbum, currentDurationSeconds);
            }
        }

        // Last.fm
        if (Settings.LASTFM_SCROBBLING.get()) {
            String sk = Settings.LASTFM_SESSION_KEY.get();
            if (!sk.isBlank()) {
                startLastFMTimer();
                if (Settings.LASTFM_NOW_PLAYING.get()) {
                    LastFM.updateNowPlaying(sk, currentArtist, currentTitle, currentAlbum, currentDurationSeconds);
                }
            }
        }
    }

    private void onSongResume() {
        // ListenBrainz
        if (Settings.LISTENBRAINZ_SCROBBLING.get() && !lbScrobbled && lbScrobbleRemainingMillis > 0) {
            cancelListenBrainzRunnable();
            lbScrobbleTimerStartedAt = System.currentTimeMillis();
            scheduleListenBrainzScrobble(lbScrobbleRemainingMillis);
        }

        // Last.fm
        if (Settings.LASTFM_SCROBBLING.get() && !lfScrobbled && lfScrobbleRemainingMillis > 0) {
            String sk = Settings.LASTFM_SESSION_KEY.get();
            if (!sk.isEmpty()) {
                cancelLastFMRunnable();
                lfScrobbleTimerStartedAt = System.currentTimeMillis();
                scheduleLastFMScrobble(lfScrobbleRemainingMillis);
            }
        }
    }

    private void onSongPause() {
        if (!songStarted) return;
        pauseTimers();
    }

    private void startListenBrainzTimer() {
        cancelListenBrainzRunnable();

        final int minSongDuration = Settings.LISTENBRAINZ_MIN_SONG_DURATION.get();
        if (currentDurationSeconds <= minSongDuration) {
            Logger.printDebug(() -> "DurationL " + currentDurationSeconds
                    + "s <= minimum: " + minSongDuration + "s, skipping scrobble");
            return;
        }

        final float delayPercent = Settings.LISTENBRAINZ_DELAY_PERCENT.get() / 100.0f;
        final int delaySeconds = Settings.LISTENBRAINZ_DELAY_SECONDS.get();

        final long thresholdMs = (long) (currentDurationSeconds * 1000L * delayPercent);
        lbScrobbleRemainingMillis = Math.min(thresholdMs, (long) delaySeconds * 1000L);

        if (lbScrobbleRemainingMillis <= 0) {
            scrobbleListenBrainz();
            return;
        }

        lbScrobbleTimerStartedAt = System.currentTimeMillis();
        scheduleListenBrainzScrobble(lbScrobbleRemainingMillis);
    }

    private void startLastFMTimer() {
        cancelLastFMRunnable();

        final int minSongDuration = Settings.LASTFM_MIN_SONG_DURATION.get();
        if (currentDurationSeconds <= minSongDuration) {
            Logger.printDebug(() -> "Last.fm: duration " + currentDurationSeconds
                    + "s <= minimum " + minSongDuration + "s, skipping scrobble");
            return;
        }

        final float delayPercent = Settings.LASTFM_DELAY_PERCENT.get() / 100.0f;
        final int delaySeconds = Settings.LASTFM_DELAY_SECONDS.get();

        final long thresholdMs = (long) (currentDurationSeconds * 1000L * delayPercent);
        lfScrobbleRemainingMillis = Math.min(thresholdMs, (long) delaySeconds * 1000L);

        if (lfScrobbleRemainingMillis <= 0) {
            scrobbleLastFM();
            return;
        }

        lfScrobbleTimerStartedAt = System.currentTimeMillis();
        scheduleLastFMScrobble(lfScrobbleRemainingMillis);
    }

    private void pauseTimers() {
        // ListenBrainz
        cancelListenBrainzRunnable();
        if (lbScrobbleTimerStartedAt != 0L) {
            long elapsed = System.currentTimeMillis() - lbScrobbleTimerStartedAt;
            lbScrobbleRemainingMillis -= elapsed;
            if (lbScrobbleRemainingMillis < 0) {
                lbScrobbleRemainingMillis = 0;
            }
            lbScrobbleTimerStartedAt = 0L;
        }

        // Last.fm
        cancelLastFMRunnable();
        if (lfScrobbleTimerStartedAt != 0L) {
            final long elapsed = System.currentTimeMillis() - lfScrobbleTimerStartedAt;
            lfScrobbleRemainingMillis -= elapsed;
            if (lfScrobbleRemainingMillis < 0) {
                lfScrobbleRemainingMillis = 0;
            }
            lfScrobbleTimerStartedAt = 0L;
        }
    }

    private void stopTimers() {
        cancelListenBrainzRunnable();
        lbScrobbleRemainingMillis = 0L;
        lbScrobbleTimerStartedAt = 0L;

        cancelLastFMRunnable();
        lfScrobbleRemainingMillis = 0L;
        lfScrobbleTimerStartedAt = 0L;
    }

    private void scheduleListenBrainzScrobble(long delayMs) {
        lbRunnable = () -> {
            scrobbleListenBrainz();
            lbRunnable = null;
        };
        handler.postDelayed(lbRunnable, delayMs);
    }

    private void scheduleLastFMScrobble(long delayMs) {
        lfRunnable = () -> {
            scrobbleLastFM();
            lfRunnable = null;
        };
        handler.postDelayed(lfRunnable, delayMs);
    }

    private void cancelListenBrainzRunnable() {
        if (lbRunnable != null) {
            handler.removeCallbacks(lbRunnable);
            lbRunnable = null;
        }
    }

    private void cancelLastFMRunnable() {
        if (lfRunnable != null) {
            handler.removeCallbacks(lfRunnable);
            lfRunnable = null;
        }
    }

    private void scrobbleListenBrainz() {
        if (lbScrobbled) return;
        ListenBrainz.scrobbleAsync(currentArtist, currentTitle, songStartedAtSeconds,
                currentSongId, currentAlbum, currentDurationSeconds);
        lbScrobbled = true;
    }

    private void scrobbleLastFM() {
        if (lfScrobbled) return;
        String sk = Settings.LASTFM_SESSION_KEY.get();
        if (!sk.isBlank()) {
            LastFM.scrobble(sk, currentArtist, currentTitle, currentAlbum,
                    currentDurationSeconds, songStartedAtSeconds);
        }
        lfScrobbled = true;
    }

    /**
     * Safe to call from any thread.
     */
    public void runOnBackgroundThread(Runnable runnable) {
        executor.submit(runnable);
    }
}
