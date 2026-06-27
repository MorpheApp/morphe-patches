/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */
package app.morphe.extension.music.patches.listenbrainz;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.patches.lastfm.LastFM;
import app.morphe.extension.music.patches.lastfm.LastFMTokenStore;
import app.morphe.extension.shared.Logger;

public class ScrobbleManager {
    private static ScrobbleManager instance;

    public static synchronized ScrobbleManager getInstance() {
        if (instance == null) {
            instance = new ScrobbleManager();
        }
        return instance;
    }

    private final Handler handler = new Handler(Looper.getMainLooper());

    private String currentTitle;
    private String currentArtist;
    private String currentAlbum;
    private String currentSongId;
    private int currentDuration; // in seconds

    private long songStartedAt; // epoch in seconds
    private boolean songStarted = false;

    // ListenBrainz Timer State
    private long lbScrobbleRemainingMillis = 0L;
    private long lbScrobbleTimerStartedAt = 0L;
    private boolean lbScrobbled = false;
    private Runnable lbRunnable;

    // Last.fm Timer State
    private long lfScrobbleRemainingMillis = 0L;
    private long lfScrobbleTimerStartedAt = 0L;
    private boolean lfScrobbled = false;
    private Runnable lfRunnable;

    private ScrobbleManager() {}

    public synchronized void onSetMetadata(MediaMetadata metadata) {
        if (metadata == null) return;

        try {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM);
            String songId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            long durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            int duration = (int) (durationMs / 1000);

            if (title == null || title.trim().isEmpty() || artist == null || artist.trim().isEmpty()) {
                return;
            }

            // Check if it is a new song
            if (!title.equals(currentTitle) || !artist.equals(currentArtist)) {
                Logger.printInfo(() -> "ScrobbleManager: new song detected: " + title + " - " + artist);
                stopTimers();
                songStarted = false;
                lbScrobbled = false;
                lfScrobbled = false;

                currentTitle = title;
                currentArtist = artist;
                currentAlbum = album;
                currentSongId = songId;
                currentDuration = duration;
            }
        } catch (Exception e) {
            Logger.printException(() -> "ScrobbleManager error parsing metadata", e);
        }
    }

    public synchronized void onSetPlaybackState(PlaybackState state) {
        if (state == null) return;
        boolean isPlaying = state.getState() == PlaybackState.STATE_PLAYING;
        onPlayerStateChanged(isPlaying);
    }

    private synchronized void onPlayerStateChanged(boolean isPlaying) {
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

    private synchronized void onSongStart() {
        songStartedAt = System.currentTimeMillis() / 1000;
        songStarted = true;

        // ListenBrainz
        if (Settings.LISTENBRAINZ_SCROBBLING.get()) {
            startListenBrainzTimer();
            if (Settings.LISTENBRAINZ_NOW_PLAYING.get()) {
                ListenBrainz.updateNowPlayingAsync(currentArtist, currentTitle, currentSongId, currentAlbum, currentDuration);
            }
        }

        // Last.fm
        if (Settings.LASTFM_SCROBBLING.get()) {
            String sk = LastFMTokenStore.retrieveSessionKey();
            if (sk != null && !sk.trim().isEmpty()) {
                startLastFMTimer();
                if (Settings.LASTFM_NOW_PLAYING.get()) {
                    LastFM.updateNowPlaying(sk, currentArtist, currentTitle, currentAlbum, currentDuration);
                }
            }
        }
    }

    private synchronized void onSongResume() {
        // ListenBrainz
        if (Settings.LISTENBRAINZ_SCROBBLING.get() && !lbScrobbled && lbScrobbleRemainingMillis > 0) {
            cancelListenBrainzRunnable();
            lbScrobbleTimerStartedAt = System.currentTimeMillis();
            scheduleListenBrainzScrobble(lbScrobbleRemainingMillis);
        }

        // Last.fm
        if (Settings.LASTFM_SCROBBLING.get() && !lfScrobbled && lfScrobbleRemainingMillis > 0) {
            String sk = LastFMTokenStore.retrieveSessionKey();
            if (sk != null && !sk.trim().isEmpty()) {
                cancelLastFMRunnable();
                lfScrobbleTimerStartedAt = System.currentTimeMillis();
                scheduleLastFMScrobble(lfScrobbleRemainingMillis);
            }
        }
    }

    private synchronized void onSongPause() {
        if (!songStarted) return;
        pauseTimers();
    }

    private synchronized void startListenBrainzTimer() {
        cancelListenBrainzRunnable();

        int minSongDuration = Settings.LISTENBRAINZ_MIN_SONG_DURATION.get();
        if (currentDuration <= minSongDuration) {
            Logger.printInfo(() -> "ListenBrainz: duration " + currentDuration + "s <= minimum " + minSongDuration + "s, skipping scrobble");
            return;
        }

        float delayPercent = Settings.LISTENBRAINZ_DELAY_PERCENT.get() / 100.0f;
        int delaySeconds = Settings.LISTENBRAINZ_DELAY_SECONDS.get();

        long thresholdMs = (long) (currentDuration * 1000L * delayPercent);
        lbScrobbleRemainingMillis = Math.min(thresholdMs, (long) delaySeconds * 1000L);

        if (lbScrobbleRemainingMillis <= 0) {
            scrobbleListenBrainz();
            return;
        }

        lbScrobbleTimerStartedAt = System.currentTimeMillis();
        scheduleListenBrainzScrobble(lbScrobbleRemainingMillis);
    }

    private synchronized void startLastFMTimer() {
        cancelLastFMRunnable();

        int minSongDuration = Settings.LASTFM_MIN_SONG_DURATION.get();
        if (currentDuration <= minSongDuration) {
            Logger.printInfo(() -> "Last.fm: duration " + currentDuration + "s <= minimum " + minSongDuration + "s, skipping scrobble");
            return;
        }

        float delayPercent = Settings.LASTFM_DELAY_PERCENT.get() / 100.0f;
        int delaySeconds = Settings.LASTFM_DELAY_SECONDS.get();

        long thresholdMs = (long) (currentDuration * 1000L * delayPercent);
        lfScrobbleRemainingMillis = Math.min(thresholdMs, (long) delaySeconds * 1000L);

        if (lfScrobbleRemainingMillis <= 0) {
            scrobbleLastFM();
            return;
        }

        lfScrobbleTimerStartedAt = System.currentTimeMillis();
        scheduleLastFMScrobble(lfScrobbleRemainingMillis);
    }

    private synchronized void pauseTimers() {
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
            long elapsed = System.currentTimeMillis() - lfScrobbleTimerStartedAt;
            lfScrobbleRemainingMillis -= elapsed;
            if (lfScrobbleRemainingMillis < 0) {
                lfScrobbleRemainingMillis = 0;
            }
            lfScrobbleTimerStartedAt = 0L;
        }
    }

    private synchronized void stopTimers() {
        cancelListenBrainzRunnable();
        lbScrobbleRemainingMillis = 0L;
        lbScrobbleTimerStartedAt = 0L;

        cancelLastFMRunnable();
        lfScrobbleRemainingMillis = 0L;
        lfScrobbleTimerStartedAt = 0L;
    }

    private void scheduleListenBrainzScrobble(long delayMs) {
        lbRunnable = () -> {
            synchronized (ScrobbleManager.this) {
                scrobbleListenBrainz();
                lbRunnable = null;
            }
        };
        handler.postDelayed(lbRunnable, delayMs);
    }

    private void scheduleLastFMScrobble(long delayMs) {
        lfRunnable = () -> {
            synchronized (ScrobbleManager.this) {
                scrobbleLastFM();
                lfRunnable = null;
            }
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

    private synchronized void scrobbleListenBrainz() {
        if (lbScrobbled) return;
        ListenBrainz.scrobbleAsync(currentArtist, currentTitle, songStartedAt, currentSongId, currentAlbum, currentDuration);
        lbScrobbled = true;
    }

    private synchronized void scrobbleLastFM() {
        if (lfScrobbled) return;
        String sk = LastFMTokenStore.retrieveSessionKey();
        if (sk != null && !sk.trim().isEmpty()) {
            LastFM.scrobble(sk, currentArtist, currentTitle, currentAlbum, currentDuration, songStartedAt);
        }
        lfScrobbled = true;
    }
}
