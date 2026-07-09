/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.scrobbling;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.util.Pair;

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
    private boolean isPlayerPlaying;

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

    private String cleanTitle(String title) {
        if (title == null) return null;
        String clean = title;
        if (Settings.SCROBBLING_METADATA_CLEANUP.get()) {
            clean = clean.replaceAll("(?i)\\s*[（(\\[](official\\s+)?(video|audio|music\\s+video|lyric\\s+video|visualizer)[）)\\]]", "");
            clean = clean.replaceAll("(?i)\\s*[（(\\[](\\d{4}\\s+)?remaster(ed)?(\\s+\\d{4})?[）)\\]]", "");
            clean = clean.replaceAll("(?i)\\s*[（(\\[]live(\\s+at\\s+.*|\\s+\\d{4})?[）)\\]]", "");
            clean = clean.replaceAll("(?i)\\s*[（(\\[](mono|stereo|hq|hd)[）)\\]]", "");
            clean = applyCustomRegex(clean);
        }
        return clean.replaceAll("\\s+", " ").trim();
    }

    private String cleanArtist(String artist) {
        if (artist == null) return null;
        String clean = artist;
        if (Settings.SCROBBLING_METADATA_CLEANUP.get()) {
            clean = clean.replaceAll("(?i)\\s*-\\s*topic$", "");
            clean = applyCustomRegex(clean);
        }
        return clean.replaceAll("\\s+", " ").trim();
    }

    private String cleanAlbum(String album) {
        if (album == null) return null;
        String clean = album;
        if (Settings.SCROBBLING_METADATA_CLEANUP.get()) {
            clean = clean.replaceAll("(?i)\\s*[（(\\[](\\d{4}\\s+)?remaster(ed)?(\\s+\\d{4})?[）)\\]]", "");
            clean = applyCustomRegex(clean);
        }
        return clean.replaceAll("\\s+", " ").trim();
    }

    private Pair<String, String> resolveTitleAndArtist(String rawTitle, String rawArtist) {
        String title = cleanTitle(rawTitle);
        String artist = cleanArtist(rawArtist);

        if (Settings.SCROBBLING_PARSE_TITLE.get() && rawTitle != null) {
            String separator = " - ";
            final int separatorLength = separator.length();
            final int separatorIndex = rawTitle.indexOf(separator);
            if (separatorIndex > 0 && separatorIndex < rawTitle.length() - separatorLength) {
                String parsedArtist = cleanArtist(rawTitle.substring(0, separatorIndex).trim());
                String parsedTrack = cleanTitle(rawTitle.substring(separatorIndex + separatorLength).trim());
                if (!parsedArtist.isEmpty() && !parsedTrack.isEmpty()) {
                    title = parsedTrack;
                    artist = parsedArtist;
                }
            }
        }

        return new Pair<>(title, artist);
    }

    private static String applyCustomRegex(String input) {
        String customRegex = Settings.SCROBBLING_CUSTOM_REGEX.get();
        if (customRegex.isBlank()) return input;
        try {
            return input.replaceAll(customRegex, "");
        } catch (Exception ex) {
            Logger.printException(() -> "Error applying custom regex: " + customRegex, ex);
            return input;
        }
    }

    public void onSetMetadata(MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null) return;

        try {
            String songId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            String album = cleanAlbum(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM));
            Pair<String, String> resolved = resolveTitleAndArtist(
                    metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
                    metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            );
            String title = resolved.first;
            String artist = resolved.second;
            final int duration = (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000);
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

                if (isPlayerPlaying) {
                    onSongStart();
                }
            } else {
                // If it is the same song, but some metadata got updated (e.g. duration, album, songId)
                boolean updated = false;
                if (duration > currentDurationSeconds) {
                    currentDurationSeconds = duration;
                    updated = true;
                    Logger.printDebug(() -> "Updated duration for " + title + ": " + duration + "s");
                }
                if (album != null && !album.isBlank() && (currentAlbum == null || currentAlbum.isBlank())) {
                    currentAlbum = album;
                    updated = true;
                    Logger.printDebug(() -> "Updated album for " + title + ": " + album);
                }
                if (songId != null && !songId.isBlank() && (currentSongId == null || currentSongId.isBlank())) {
                    currentSongId = songId;
                    updated = true;
                    Logger.printDebug(() -> "Updated songId for " + title + ": " + songId);
                }

                if (updated && songStarted) {
                    if (isPlayerPlaying) {
                        // If playback is already active, check if we need to start/update the scrobble timers
                        // because they might have been skipped initially due to 0/invalid duration.
                        if (Settings.LISTENBRAINZ_SCROBBLING.get()) {
                            if (!lbScrobbled && lbRunnable == null && lbScrobbleTimerStartedAt == 0L) {
                                startListenBrainzTimer();
                            }
                            if (Settings.LISTENBRAINZ_NOW_PLAYING.get()) {
                                ListenBrainz.updateNowPlayingAsync(currentArtist, currentTitle, currentSongId, currentAlbum, currentDurationSeconds);
                            }
                        }
                        if (Settings.LASTFM_SCROBBLING.get()) {
                            String sk = Settings.LASTFM_SESSION_KEY.get();
                            if (!sk.isBlank()) {
                                if (!lfScrobbled && lfRunnable == null && lfScrobbleTimerStartedAt == 0L) {
                                    startLastFMTimer();
                                }
                                if (Settings.LASTFM_NOW_PLAYING.get()) {
                                    LastFM.updateNowPlaying(sk, currentArtist, currentTitle, currentAlbum, currentDurationSeconds);
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onSetMetadata failure", ex);
        }
    }

    public void onSetPlaybackState(PlaybackState state) {
        Utils.verifyOnMainThread();
        if (state == null) return;
        isPlayerPlaying = state.getState() == PlaybackState.STATE_PLAYING;
        onPlayerStateChanged(isPlayerPlaying);
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
        final long totalDelayMs = Math.min(thresholdMs, (long) delaySeconds * 1000L);

        final long elapsedMs = Math.max(0, System.currentTimeMillis() - (songStartedAtSeconds * 1000L));

        lbScrobbleRemainingMillis = totalDelayMs - elapsedMs;

        if (lbScrobbleRemainingMillis <= 0) {
            scrobbleListenBrainz();
            return;
        }

        if (isPlayerPlaying) {
            lbScrobbleTimerStartedAt = System.currentTimeMillis();
            scheduleListenBrainzScrobble(lbScrobbleRemainingMillis);
        } else {
            lbScrobbleTimerStartedAt = 0L;
        }
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
        final long totalDelayMs = Math.min(thresholdMs, (long) delaySeconds * 1000L);

        final long elapsedMs = Math.max(0, System.currentTimeMillis() - (songStartedAtSeconds * 1000L));

        lfScrobbleRemainingMillis = totalDelayMs - elapsedMs;

        if (lfScrobbleRemainingMillis <= 0) {
            scrobbleLastFM();
            return;
        }

        if (isPlayerPlaying) {
            lfScrobbleTimerStartedAt = System.currentTimeMillis();
            scheduleLastFMScrobble(lfScrobbleRemainingMillis);
        } else {
            lfScrobbleTimerStartedAt = 0L;
        }
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
