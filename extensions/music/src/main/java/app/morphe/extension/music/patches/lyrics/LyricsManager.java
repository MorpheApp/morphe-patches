/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2269
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.patches.lyrics.requests.KuGouProvider;
import app.morphe.extension.music.patches.lyrics.requests.LrcLibProvider;
import app.morphe.extension.music.patches.lyrics.requests.LyricsProvider;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Fetches lyrics for the currently playing track and tracks playback position.
 *
 * <p>Playback position is derived from {@link PlaybackState} rather than from the
 * player time hook, because the player hook only ticks once per second while
 * synced lyrics need a position accurate to a few tens of milliseconds.
 */
public final class LyricsManager {

    public enum State {
        IDLE,
        LOADING,
        LOADED,
        NOT_FOUND,
        ERROR
    }

    public interface Listener {

        /** Called on the main thread whenever the state or the lyrics change. */
        void onLyricsChanged(State state, @Nullable Lyrics lyrics);
    }

    private static final LyricsManager INSTANCE = new LyricsManager();

    /** A single thread is enough, and it keeps the requests for one track ordered. */
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private final List<Listener> listeners = new ArrayList<>(2);

    @Nullable
    private TrackInfo currentTrack;

    @Nullable
    private Lyrics currentLyrics;

    private State state = State.IDLE;

    /**
     * Incremented for every track change so that a late response for a previous
     * track is discarded instead of being shown for the current one.
     */
    private int requestId;

    private long positionMs;
    private long positionUpdatedAtUptimeMs;
    private float playbackSpeed = 1f;
    private boolean playing;

    private LyricsManager() {
    }

    public static LyricsManager getInstance() {
        return INSTANCE;
    }

    public void addListener(Listener listener) {
        Utils.verifyOnMainThread();
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        listener.onLyricsChanged(state, currentLyrics);
    }

    public void removeListener(Listener listener) {
        Utils.verifyOnMainThread();
        listeners.remove(listener);
    }

    @Nullable
    public TrackInfo getCurrentTrack() {
        return currentTrack;
    }

    /** Whether lyrics for the current track are loaded and ready to show. */
    public boolean hasLyrics() {
        return state == State.LOADED && currentLyrics != null && !currentLyrics.isEmpty();
    }

    /**
     * Current playback position including the user configured offset,
     * extrapolated from the last {@link PlaybackState} update.
     */
    public long getPositionMs() {
        long position = positionMs;
        if (playing && positionUpdatedAtUptimeMs != 0) {
            final long elapsed = SystemClock.uptimeMillis() - positionUpdatedAtUptimeMs;
            position += (long) (elapsed * playbackSpeed);
        }
        return position - Settings.LYRICS_OFFSET_MS.get();
    }

    /**
     * Injection point relay. Called on the main thread.
     */
    public void onSetMetadata(@Nullable MediaMetadata metadata) {
        Utils.verifyOnMainThread();
        if (metadata == null || !Settings.LYRICS_ENABLED.get()) {
            return;
        }

        String rawTitle = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
        String rawArtist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (rawTitle == null || rawTitle.isBlank() || rawArtist == null || rawArtist.isBlank()) {
            return;
        }

        TrackInfo track = new TrackInfo(
                MetadataCleaner.cleanTitle(rawTitle),
                MetadataCleaner.cleanArtist(rawArtist),
                MetadataCleaner.cleanAlbum(metadata.getString(MediaMetadata.METADATA_KEY_ALBUM)),
                (int) (metadata.getLong(MediaMetadata.METADATA_KEY_DURATION) / 1000)
        );

        if (track.title().isEmpty() || track.artist().isEmpty()) {
            return;
        }

        if (track.equals(currentTrack)) {
            return;
        }

        currentTrack = track;
        // A new track starts at zero, and the first playback state update corrects it.
        positionMs = 0;
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();

        load(track);
    }

    /**
     * Injection point relay. Called on the main thread.
     */
    public void onSetPlaybackState(@Nullable PlaybackState playbackState) {
        Utils.verifyOnMainThread();
        if (playbackState == null) {
            return;
        }

        playing = playbackState.getState() == PlaybackState.STATE_PLAYING;
        positionMs = playbackState.getPosition();
        positionUpdatedAtUptimeMs = SystemClock.uptimeMillis();

        final float speed = playbackState.getPlaybackSpeed();
        // A paused state reports a speed of zero, which would freeze extrapolation
        // even after playback resumes, so only positive speeds are kept.
        if (speed > 0) {
            playbackSpeed = speed;
        }
    }

    private void load(TrackInfo track) {
        final int id = ++requestId;
        setState(State.LOADING, null);

        // The disk cache is read on the background thread as well, because touching
        // storage on the main thread stutters the player animation.
        executor.execute(() -> {
            Lyrics cached = LyricsCache.get(track);
            if (cached != null) {
                Utils.runOnMainThread(() -> publish(id, cached));
                return;
            }

            if (!Utils.isNetworkConnected()) {
                Utils.runOnMainThread(() -> {
                    if (id == requestId) {
                        setState(State.ERROR, null);
                    }
                });
                return;
            }

            Lyrics result = null;
            boolean failed = false;

            for (LyricsProvider provider : providersInOrder()) {
                Lyrics fetched = null;
                try {
                    fetched = provider.fetch(track);
                } catch (Exception ex) {
                    failed = true;
                    Logger.printException(() -> "Lyrics request failed: " + provider.name(), ex);
                }

                if (fetched != null) {
                    result = fetched;
                    // An explicit "no lyrics exist" answer is final, so the remaining
                    // providers are only tried when this one knows nothing at all.
                    if (fetched != Lyrics.NOT_FOUND && !fetched.isEmpty()) {
                        break;
                    }
                }
            }

            if (result == null && failed) {
                Utils.runOnMainThread(() -> {
                    if (id == requestId) {
                        setState(State.ERROR, null);
                    }
                });
                return;
            }

            Lyrics resolved = result == null ? Lyrics.NOT_FOUND : result;
            LyricsCache.put(track, resolved);
            Utils.runOnMainThread(() -> publish(id, resolved));
        });
    }

    private void publish(int id, Lyrics lyrics) {
        if (id != requestId) {
            Logger.printDebug(() -> "Discarding lyrics of a previous track");
            return;
        }

        if (lyrics == Lyrics.NOT_FOUND || lyrics.isEmpty()) {
            setState(State.NOT_FOUND, null);
        } else {
            setState(State.LOADED, lyrics);
        }
    }

    private void setState(State newState, @Nullable Lyrics lyrics) {
        state = newState;
        currentLyrics = lyrics;

        // A listener may remove itself while being notified.
        for (Listener listener : new ArrayList<>(listeners)) {
            try {
                listener.onLyricsChanged(newState, lyrics);
            } catch (Exception ex) {
                Logger.printException(() -> "Lyrics listener failure", ex);
            }
        }
    }

    private static List<LyricsProvider> providersInOrder() {
        List<LyricsProvider> providers = new ArrayList<>(2);
        switch (Settings.LYRICS_SOURCE.get()) {
            case LRCLIB:
                providers.add(new LrcLibProvider());
                break;
            case KUGOU:
                providers.add(new KuGouProvider());
                break;
            case LRCLIB_THEN_KUGOU:
            default:
                providers.add(new LrcLibProvider());
                providers.add(new KuGouProvider());
                break;
        }
        return providers;
    }
}
