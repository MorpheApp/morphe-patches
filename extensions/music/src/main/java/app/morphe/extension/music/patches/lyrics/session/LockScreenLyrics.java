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
import app.morphe.extension.music.patches.lyrics.LyricsPresentation;
import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

/**
 * Mirrors the currently sung lyric line into the MediaSession title so it shows on the
 * lock screen, Android Auto and Bluetooth displays. The artist field is rewritten to
 * {@code "artist - title"} so the track identity is preserved.
 *
 * <p>Observed at the app's {@link MediaSession#setMetadata(MediaMetadata)} call site to capture
 * the session and the original metadata; modified metadata is pushed from a ticker via the
 * framework directly, so it never re-enters the hooked call site and the lyrics/scrobbling
 * observers reading the original metadata are unaffected. Other fields, notably album art, are
 * preserved by copying the original metadata, including the display variants of title and artist.
 *
 * <p>Android 11+ only: Android 10 lock screens render the media notification (text baked into the
 * RemoteViews at post time) instead of session metadata, so a pushed line would never be shown.
 */
@SuppressWarnings("unused")
public final class LockScreenLyrics {

    private static final long MAX_NUDGE_RECEIPT_AGE_MS = 6 * 60 * 60 * 1000L;
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
    /** Title and artist pushed on the last tick, to avoid redundant {@code setMetadata} calls. */
    @Nullable
    private static volatile String lastPushedTitle;
    @Nullable
    private static String lastPushedArtist;
    /** Set when the app pushes fresh metadata, so the next tick pushes it again. */
    private static volatile boolean needsRepush;
    /** Drives the periodic check that mirrors the current line into the MediaSession. */
    private static final LyricsTicker ticker = new LyricsTicker(LockScreenLyrics::tick);
    @Nullable
    private static volatile PlaybackState playbackState;
    /**
     * Re-arms the ticker whenever lyrics arrive or disappear, so a late lookup does not
     * leave the mirror stuck on the track title.
     */
    private static final LyricsManager.Listener lyricsListener = (state, lyrics) -> {
        if (state == LyricsManager.State.LOADED || state == LyricsManager.State.NOT_FOUND) {
            ticker.schedule();
        }
    };
    private static volatile long observedPositionMs;
    private static volatile long observedAtElapsedMs;

    private LockScreenLyrics() {
    }

    /** Pushes the current line again, as a lyrics setting changed how it is shown. */
    public static void onSettingsChanged() {
        needsRepush = true;
        tick();
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

            String rt = original.getString(MediaMetadata.METADATA_KEY_TITLE);
            String ra = original.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (!Objects.equals(rt, realTitle) || !Objects.equals(ra, realArtist)) {
                LyricsPresentation.resetTrackCycle();
            }
            realTitle = rt;
            realArtist = ra;

            String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(rt, ra);
            cachedCleanedTitle = parsed[1];
            cachedCleanedArtist = parsed[0];

            if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MEDIASESSION.get()) {
                LyricsManager.getInstance().removeListener(lyricsListener);
                ticker.stop();
                lastPushedTitle = null;
                return;
            }

            android.net.Uri mediaUri = LyricsManager.parseMediaUri(original);
            LyricsManager.getInstance().onDisplayedTrackChanged(rt, ra, mediaUri);
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
        String artist = getTrackLine();
        if (!needsRepush && newTitle.equals(lastPushedTitle) && artist.equals(lastPushedArtist)) {
            ticker.schedule();
            return;
        }

        MediaMetadata metadata = buildMetadata(newTitle, artist);
        if (metadata == null) {
            ticker.schedule();
            return;
        }

        session.setMetadata(metadata);
        LyricNotificationRefresh.refresh(session, newTitle,
                metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE));
        lastPushedTitle = newTitle;
        lastPushedArtist = artist;
        needsRepush = false;
        nudgePlaybackState(session);

        ticker.schedule();
    }

    /**
     * Re-dispatches the app's playback state with a rebased position so media surfaces that
     * refresh only on a state change pick up the line just pushed. The position continues from
     * the receipt-time reading on the {@link SystemClock#elapsedRealtime} timebase
     * {@link PlaybackState} mandates, so the seek bar keeps advancing instead of jumping
     * backwards. Pushed through the framework, so the hooked app call site is not re-entered.
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
            String title = original.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            String text = original.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            LyricNotificationRefresh.refresh(session,
                    title != null ? title : original.getString(MediaMetadata.METADATA_KEY_TITLE),
                    text != null ? text : original.getString(MediaMetadata.METADATA_KEY_ARTIST));
            nudgePlaybackState(session);
        }
        lastPushedTitle = null;
        needsRepush = false;
    }

    @Nullable
    public static MediaSession mediaSession() {
        WeakReference<MediaSession> reference = sessionRef;
        return reference != null ? reference.get() : null;
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
        if (line == null) {
            String rt = realTitle;
            return rt == null ? "" : rt;
        }
        return line;
    }

    /** The track format shown as the artist, or the plain artist if the title is unknown. */
    private static String getTrackLine() {
        String ra = realArtist;
        String artist = ra == null ? "" : ra;
        String rt = realTitle;
        return rt == null || rt.isEmpty() ? artist : LyricsPresentation.trackLabel(rt, artist);
    }

    @Nullable
    private static MediaMetadata buildMetadata(String title, String artist) {
        MediaMetadata.Builder builder = metadataBuilder;
        if (builder == null) {
            return null;
        }

        builder.putString(MediaMetadata.METADATA_KEY_TITLE, title);
        builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE, title);
        builder.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        builder.putString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, artist);
        return builder.build();
    }


}
