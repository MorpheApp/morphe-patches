/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.session;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Mirrors the currently sung lyric line into the MediaSession title so it shows on the
 * lock screen, Android Auto and Bluetooth displays. The artist field is rewritten to
 * {@code "artist - title"} so the track identity is preserved.
 *
 * <p>The app's {@link MediaSession#setMetadata(MediaSession)} call site is observed to
 * capture the {@link MediaSession} instance and the original metadata. Modified metadata is
 * then pushed from a ticker via the captured session. Because that push goes through the
 * framework directly, it does not re-enter the hooked app call site, so the lyrics and
 * scrobbling observers (which read the original metadata at that site) are never affected.
 *
 * <p>All fields other than title and artist, notably the album art, are preserved by copying
 * the original metadata with {@link MediaMetadata.Builder}. The display variants of the title
 * and of the artist are rewritten as well, because the lock screen prefers them over the plain
 * ones when both are present.
 *
 * <p>The MediaSession push only supports Android 11 and above: Android 10 lock screens render
 * the media notification (whose text is baked into the RemoteViews at post time) instead of
 * reading session metadata, so a line pushed here would never be shown there. Android 10 and
 * older are not adapted.
 */
@SuppressWarnings("unused")
public final class LockScreenLyrics {

    @Nullable
    private static volatile WeakReference<MediaSession> sessionRef;
    @Nullable
    private static volatile MediaMetadata originalMetadata;
    @Nullable
    private static MediaMetadata.Builder metadataBuilder;
    @Nullable
    private static volatile String realTitle;
    @Nullable
    private static volatile String realArtist;

    @Nullable
    private static String cachedCleanedTitle;
    @Nullable
    private static String cachedCleanedArtist;

    /** Title pushed on the last tick, to avoid redundant {@code setMetadata} calls. */
    @Nullable
    private static volatile String lastPushedTitle;

    /** Set when the app pushes fresh metadata, so the next tick pushes it again. */
    private static volatile boolean needsRepush;

    /** Drives the periodic check that mirrors the current line into the MediaSession. */
    private static final LyricsTicker ticker = new LyricsTicker(LockScreenLyrics::tick);

    /**
     * Re-arms the ticker whenever lyrics arrive or disappear, so a late lookup does not
     * leave the mirror stuck on the track title.
     */
    private static final LyricsManager.Listener lyricsListener = (state, lyrics) -> {
        if (state == LyricsManager.State.LOADED || state == LyricsManager.State.NOT_FOUND) {
            ticker.schedule();
        }
    };

    @Nullable
    private static volatile PlaybackState playbackState;

    private static volatile long observedPositionMs;
    private static volatile long observedAtElapsedMs;

    private static final long MAX_NUDGE_RECEIPT_AGE_MS = 6 * 60 * 60 * 1000L;

    private LockScreenLyrics() {
    }

    /**
     * Observed at the app's {@code MediaSession.setMetadata} call site. Captures the session
     * and original metadata and (re)starts the ticker when the feature is enabled.
     */
    public static void onMediaSessionSetMetadata(MediaSession session, MediaMetadata original) {
        try {
            if (session == null || original == null) {
                return;
            }

            sessionRef = new WeakReference<>(session);
            originalMetadata = original;
            metadataBuilder = new MediaMetadata.Builder(original);
            realTitle = original.getString(MediaMetadata.METADATA_KEY_TITLE);
            realArtist = original.getString(MediaMetadata.METADATA_KEY_ARTIST);

            String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(realTitle, realArtist);
            cachedCleanedTitle = parsed[1];
            cachedCleanedArtist = parsed[0];

            if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MEDIASESSION.get()) {
                LyricsManager.getInstance().removeListener(lyricsListener);
                ticker.stop();
                lastPushedTitle = null;
                return;
            }

            android.net.Uri mediaUri = LyricsManager.parseMediaUri(original);
            LyricsManager.getInstance().onDisplayedTrackChanged(realTitle, realArtist, mediaUri);
            LyricsManager.getInstance().addListener(lyricsListener);
            lastPushedTitle = null;
            needsRepush = true;
            ticker.schedule();
        } catch (Exception ex) {
            Logger.printException(() -> "onMediaSessionSetMetadata failure", ex);
        }
    }

    /**
     * Observed at the app's {@code MediaSession.setPlaybackState} call site. Captures the
     * latest playback state so a refresh nudge can be attached to line pushes, and revives
     * the ticker when the feature is enabled, because state updates keep arriving while no
     * metadata pushes are running.
     */
    public static void onPlaybackState(@Nullable PlaybackState state) {
        if (state == null) {
            return;
        }

        observedPositionMs = state.getPosition();
        observedAtElapsedMs = SystemClock.elapsedRealtime();
        playbackState = state;

        if (Settings.LYRICS_ENABLED.get() && Settings.LYRICS_MEDIASESSION.get()
                && sessionRef != null) {
            ticker.schedule();
        }
    }

    private static void tick() {
        try {
            push();
        } catch (Exception ex) {
            Logger.printException(() -> "tick failure", ex);
            lastPushedTitle = null;
            ticker.schedule();
        }
    }

    private static void push() {
        WeakReference<MediaSession> reference = sessionRef;
        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MEDIASESSION.get()
                || reference == null || originalMetadata == null) {
            restoreIfPushed();
            LyricsManager.getInstance().removeListener(lyricsListener);
            ticker.stop();
            return;
        }

        MediaSession session = reference.get();
        if (session == null) {
            // The session was released; wait for the next metadata update.
            ticker.stop();
            lastPushedTitle = null;
            return;
        }

        if (!lyricsMatch()) {
            restoreIfPushed();
            ticker.schedule();
            return;
        }

        String newTitle = getCurrentLine();
        if (!needsRepush && newTitle.equals(lastPushedTitle)) {
            ticker.schedule();
            return;
        }

        MediaMetadata metadata = buildMetadata(newTitle);
        if (metadata == null) {
            ticker.schedule();
            return;
        }

        session.setMetadata(metadata);
        lastPushedTitle = newTitle;
        needsRepush = false;
        nudgePlaybackState(session);

        ticker.schedule();
    }

    /**
     * Re-dispatches the app's playback state with a rebased position so media surfaces that
     * refresh their metadata only when the playback state changes pick up the line that was
     * just pushed. The position continues from the receipt-time reading on the
     * {@link SystemClock#elapsedRealtime} timebase that {@link PlaybackState} mandates, so
     * the system seek bar keeps advancing instead of jumping backwards. The push goes
     * through the framework directly and does not re-enter the hooked app call site.
     */
    private static void nudgePlaybackState(MediaSession session) {
        PlaybackState captured = playbackState;
        if (captured == null) {
            return;
        }

        final long now = SystemClock.elapsedRealtime();
        final long receiptAge = now - observedAtElapsedMs;
        final float speed = captured.getPlaybackSpeed();

        if (captured.getState() != PlaybackState.STATE_PLAYING || speed <= 0f
                || observedAtElapsedMs == 0
                || receiptAge < 0 || receiptAge > MAX_NUDGE_RECEIPT_AGE_MS) {
            session.setPlaybackState(captured);
            return;
        }

        long position = observedPositionMs + (long) (receiptAge * speed);
        PlaybackState nudged = new PlaybackState.Builder(captured)
                .setState(captured.getState(), position, speed, now)
                .build();
        session.setPlaybackState(nudged);
    }

    private static void restoreIfPushed() {
        if (lastPushedTitle == null) {
            return;
        }
        WeakReference<MediaSession> reference = sessionRef;
        MediaSession session = reference != null ? reference.get() : null;
        MediaMetadata original = originalMetadata;
        if (session != null && original != null) {
            session.setMetadata(original);
            nudgePlaybackState(session);
        }
        lastPushedTitle = null;
        needsRepush = false;
    }

    private static boolean lyricsMatch() {
        LyricsManager manager = LyricsManager.getInstance();
        TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            return false;
        }
        return Objects.equals(track.title(), cachedCleanedTitle)
                && Objects.equals(track.artist(), cachedCleanedArtist)
                && manager.areLyricsSynced();
    }

    private static String getCurrentLine() {
        String line = LyricsManager.getInstance().getCurrentLineText();
        if (line == null || line.isEmpty()) {
            return realTitle == null ? "" : realTitle;
        }
        return line;
    }

    private static MediaMetadata buildMetadata(String title) {
        MediaMetadata.Builder builder = metadataBuilder;
        if (builder == null) {
            return null;
        }
        String artist = realArtist == null ? "" : realArtist;
        String display = artist;
        if (realTitle != null && !realTitle.isEmpty()) {
            display = new TrackInfo(realTitle, artist, "", 0)
                    .displayWith(Settings.LYRICS_DISPLAY_ARTIST_FIRST.get());
        }
        builder.putString(MediaMetadata.METADATA_KEY_TITLE, title);
        builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title);
        builder.putString(MediaMetadata.METADATA_KEY_ARTIST, display);
        builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, display);
        return builder.build();
    }
}
