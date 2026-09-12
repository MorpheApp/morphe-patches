/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;

/**
 * Mirrors the currently sung lyric line into the in-app miniplayer: the title shows the current
 * line and the subtitle shows {@code "artist - title"}. When no synced or word-level lyrics are
 * available the app's own title and artist are left untouched.
 *
 * <p>The miniplayer view hierarchy is captured from the constructor injection point, and a ticker
 * updates the two {@link TextView}s as playback progresses.
 */
public final class MiniPlayerLyrics {

    private static volatile int titleId;
    private static volatile int subtitleId;
    @Nullable
    private static volatile WeakReference<TextView> titleRef;
    @Nullable
    private static volatile WeakReference<TextView> subtitleRef;

    /** Track the system is currently displaying, captured from {@link MediaSession} metadata. */
    @Nullable
    private static volatile String displayTitle;
    @Nullable
    private static volatile String displayArtist;

    /** Drives the periodic check that mirrors the current line into the mini player. */
    private static final LyricsTicker ticker = new LyricsTicker(MiniPlayerLyrics::tick);

    /** Listener that re-schedules the ticker immediately when lyrics finish loading. */
    private static final LyricsManager.Listener lyricsListener = (state, lyrics) -> {
        if (state == LyricsManager.State.LOADED || state == LyricsManager.State.NOT_FOUND) {
            ticker.schedule();
        }
    };

    private MiniPlayerLyrics() {
    }

    public static void onMediaSessionSetMetadata(MediaSession session, MediaMetadata original) {
        if (original == null) {
            return;
        }
        final String title = original.getString(MediaMetadata.METADATA_KEY_TITLE);
        final String artist = original.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (title == null || title.isBlank() || artist == null || artist.isBlank()) {
            return;
        }
        final String[] parsed = MetadataCleaner.parseTitleAndArtist(title);
        displayTitle = parsed != null ? parsed[1] : MetadataCleaner.cleanTitle(title);
        displayArtist = parsed != null ? parsed[0] : MetadataCleaner.cleanArtist(artist);

        android.net.Uri mediaUri = null;
        final String uriString = original.getString(android.media.MediaMetadata.METADATA_KEY_MEDIA_URI);
        if (uriString != null) {
            mediaUri = android.net.Uri.parse(uriString);
        }
        LyricsManager.getInstance().onDisplayedTrackChanged(title, artist, mediaUri);
    }

    /**
     * Injection point. Captures the miniplayer title and subtitle TextViews and (re)starts the
     * ticker when the feature is enabled.
     */
    public static void onMiniPlayerViewCreated(View view) {
        if (view == null) {
            return;
        }

        if (titleId == 0) {
            titleId = ResourceUtils.getIdentifier(ResourceType.ID, "mini_player_title");
        }
        if (subtitleId == 0) {
            subtitleId = ResourceUtils.getIdentifier(ResourceType.ID, "mini_player_subtitle");
        }
        if (titleId == 0 || subtitleId == 0) {
            return;
        }

        if (!(view.findViewById(titleId) instanceof TextView title)
                || !(view.findViewById(subtitleId) instanceof TextView subtitle)) {
            return;
        }

        titleRef = new WeakReference<>(title);
        subtitleRef = new WeakReference<>(subtitle);

        final TrackInfo current = LyricsManager.getInstance().getCurrentTrack();
        if (current != null) {
            displayTitle = current.title();
            displayArtist = current.artist();
        }

        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MINIPLAYER.get()) {
            ticker.stop();
            LyricsManager.getInstance().removeListener(lyricsListener);
            return;
        }

        LyricsManager.getInstance().addListener(lyricsListener);
        ticker.schedule();
    }

    private static void tick() {
        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MINIPLAYER.get()) {
            ticker.stop();
            LyricsManager.getInstance().removeListener(lyricsListener);
            return;
        }

        final TextView title = titleRef != null ? titleRef.get() : null;
        final TextView subtitle = subtitleRef != null ? subtitleRef.get() : null;
        if (title == null || subtitle == null) {
            ticker.stop();
            LyricsManager.getInstance().removeListener(lyricsListener);
            return;
        }

        final LyricsManager manager = LyricsManager.getInstance();
        final TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            ticker.schedule();
            return;
        }

        final boolean synced = manager.areLyricsAvailable()
                && Objects.equals(track.title(), displayTitle)
                && Objects.equals(track.artist(), displayArtist);

        if (synced) {
            final String line = manager.getCurrentLineText();
            final String newTitle = (line == null || line.isEmpty()) ? track.title() : line;
            final String actualTitle = title.getText() != null ? title.getText().toString() : null;
            if (!newTitle.equals(actualTitle)) {
                title.setText(newTitle);
            }
            final String newSubtitle;
            if (Settings.LYRICS_DISPLAY_ARTIST_FIRST.get()) {
                newSubtitle = track.artist() + " - " + track.title();
            } else {
                newSubtitle = track.title() + " - " + track.artist();
            }
            final String actualSubtitle = subtitle.getText() != null ? subtitle.getText().toString() : null;
            if (!newSubtitle.equals(actualSubtitle)) {
                subtitle.setText(newSubtitle);
            }
        } else {
            final String actualTitle = title.getText() != null ? title.getText().toString() : null;
            if (!track.title().equals(actualTitle)) {
                title.setText(track.title());
            }
            final String actualSubtitle = subtitle.getText() != null ? subtitle.getText().toString() : null;
            if (!track.artist().equals(actualSubtitle)) {
                subtitle.setText(track.artist());
            }
        }

        ticker.schedule();
    }
}
