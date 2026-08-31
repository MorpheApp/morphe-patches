/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;

import app.morphe.extension.music.settings.Settings;

import java.util.Objects;

/**
 * Mirrors the currently sung lyric line into the MediaSession title so it shows on the
 * lock screen, Android Auto and Bluetooth displays. The artist field is rewritten to
 * {@code "artist - title"} so the track identity is preserved.
 *
 * <p>The app's {@link MediaSession#setMetadata(MediaMetadata)} call site is observed to
 * capture the {@link MediaSession} instance and the original metadata. Modified metadata is
 * then pushed from a ticker via the captured session. Because that push goes through the
 * framework directly, it does not re-enter the hooked app call site, so the lyrics and
 * scrobbling observers (which read the original metadata at that site) are never affected.
 *
 * <p>All fields other than title and artist, notably the album art, are preserved by copying
 * the original metadata with {@link MediaMetadata.Builder}.
 */
public final class LockScreenLyrics {

    /** How often to check whether the current lyric line changed. */
    private static final long TICK_INTERVAL_MS = 300;

    @Nullable
    private static volatile WeakReference<MediaSession> sessionRef;
    @Nullable
    private static volatile MediaMetadata originalMetadata;
    @Nullable
    private static volatile String realTitle;
    @Nullable
    private static volatile String realArtist;

    /** Title pushed on the last tick, to avoid redundant {@code setMetadata} calls. */
    @Nullable
    private static volatile String lastPushedTitle;

    /** Set when the app pushes fresh metadata, forcing a repush on the next tick. */
    private static volatile boolean needsRepush;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable ticker = LockScreenLyrics::tick;

    private LockScreenLyrics() {
    }

    /**
     * Observed at the app's {@code MediaSession.setMetadata} call site. Captures the session
     * and original metadata and (re)starts the ticker when the feature is enabled.
     */
    public static void onMediaSessionSetMetadata(MediaSession session, MediaMetadata original) {
        if (session == null || original == null) {
            return;
        }

        sessionRef = new WeakReference<>(session);
        originalMetadata = original;
        realTitle = original.getString(MediaMetadata.METADATA_KEY_TITLE);
        realArtist = original.getString(MediaMetadata.METADATA_KEY_ARTIST);

        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MEDIASESSION.get()) {
            stopTicker();
            lastPushedTitle = null;
            return;
        }

        LyricsManager.getInstance().onDisplayedTrackChanged(realTitle, realArtist);
        lastPushedTitle = null;
        needsRepush = true;
        scheduleTick();
    }

    private static void scheduleTick() {
        handler.removeCallbacks(ticker);
        handler.postDelayed(ticker, TICK_INTERVAL_MS);
    }

    private static void stopTicker() {
        handler.removeCallbacks(ticker);
    }

    private static void tick() {
        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MEDIASESSION.get()
                || sessionRef == null || originalMetadata == null) {
            stopTicker();
            lastPushedTitle = null;
            return;
        }

        final MediaSession session = sessionRef.get();
        if (session == null) {
            // The session was released; wait for the next metadata update.
            stopTicker();
            lastPushedTitle = null;
            return;
        }

        final String newTitle = getCurrentLine();
        if (!needsRepush && newTitle.equals(lastPushedTitle)) {
            scheduleTick();
            return;
        }

        lastPushedTitle = newTitle;
        needsRepush = false;
        session.setMetadata(buildMetadata(originalMetadata, newTitle));

        scheduleTick();
    }

    private static boolean lyricsMatch() {
        final LyricsManager manager = LyricsManager.getInstance();
        final TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            return false;
        }
        // The manager stores cleaned metadata, while realTitle/realArtist are raw, so both
        // sides must be normalized before comparing.
        final String cleanedTitle = MetadataCleaner.cleanTitle(realTitle);
        final String cleanedArtist = MetadataCleaner.cleanArtist(realArtist);
        return Objects.equals(track.title(), cleanedTitle)
                && Objects.equals(track.artist(), cleanedArtist)
                && manager.areLyricsAvailable();
    }

    private static String getCurrentLine() {
        final LyricsManager manager = LyricsManager.getInstance();
        final String line = lyricsMatch() ? manager.getCurrentLineText() : null;
        if (line == null || line.isEmpty()) {
            return realTitle == null ? "" : realTitle;
        }
        return line;
    }

    private static MediaMetadata buildMetadata(MediaMetadata original, String title) {
        final MediaMetadata.Builder builder = new MediaMetadata.Builder(original);
        if (title != null) {
            builder.putString(MediaMetadata.METADATA_KEY_TITLE, title);
        }
        final String artist = realArtist == null ? "" : realArtist;
        if (lyricsMatch() && realTitle != null && !realTitle.isEmpty()) {
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, artist + " - " + realTitle);
        } else {
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        }
        return builder.build();
    }
}
