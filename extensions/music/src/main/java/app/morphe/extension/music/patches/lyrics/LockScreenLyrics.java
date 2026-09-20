/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.MediaSession;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.music.settings.Settings;

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
 * the original metadata with {@link MediaMetadata.Builder}.
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
        metadataBuilder = new MediaMetadata.Builder(original);
        realTitle = original.getString(MediaMetadata.METADATA_KEY_TITLE);
        realArtist = original.getString(MediaMetadata.METADATA_KEY_ARTIST);

        String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(realTitle, realArtist);
        cachedCleanedTitle = parsed[1];
        cachedCleanedArtist = parsed[0];

        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MEDIASESSION.get()) {
            ticker.stop();
            lastPushedTitle = null;
            return;
        }

        android.net.Uri mediaUri = LyricsManager.parseMediaUri(original);
        LyricsManager.getInstance().onDisplayedTrackChanged(realTitle, realArtist, mediaUri);
        lastPushedTitle = null;
        needsRepush = true;
        ticker.schedule();
    }

    private static void tick() {
        WeakReference<MediaSession> reference = sessionRef;
        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MEDIASESSION.get()
                || reference == null || originalMetadata == null) {
            ticker.stop();
            lastPushedTitle = null;
            return;
        }

        MediaSession session = reference.get();
        if (session == null) {
            // The session was released; wait for the next metadata update.
            ticker.stop();
            lastPushedTitle = null;
            return;
        }

        boolean matched = lyricsMatch();

        String newTitle = getCurrentLine(matched);
        if (!needsRepush && newTitle.equals(lastPushedTitle)) {
            ticker.schedule();
            return;
        }

        lastPushedTitle = newTitle;
        needsRepush = false;
        session.setMetadata(buildMetadata(newTitle, matched));

        ticker.schedule();
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

    private static String getCurrentLine(boolean matched) {
        LyricsManager manager = LyricsManager.getInstance();
        String line = matched ? manager.getCurrentLineText() : null;
        if (line == null || line.isEmpty()) {
            return realTitle == null ? "" : realTitle;
        }
        return line;
    }

    private static MediaMetadata buildMetadata(String title, boolean matched) {
        MediaMetadata.Builder builder = metadataBuilder;
        if (builder == null) {
            return null;
        }
        if (title != null) {
            builder.putString(MediaMetadata.METADATA_KEY_TITLE, title);
        }
        String artist = realArtist == null ? "" : realArtist;
        String trackTitle = realTitle;
        if (matched && trackTitle != null && !trackTitle.isEmpty()) {
            String display = new TrackInfo(trackTitle, artist, "", 0)
                    .displayWith(Settings.LYRICS_DISPLAY_ARTIST_FIRST.get());
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, display);
        } else {
            builder.putString(MediaMetadata.METADATA_KEY_ARTIST, artist);
        }
        return builder.build();
    }
}
