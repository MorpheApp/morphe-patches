/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
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
@SuppressWarnings("unused")
public final class MiniPlayerLyrics {

    private static WeakReference<TextView> titleRef = new WeakReference<>(null);
    private static WeakReference<TextView> subtitleRef = new WeakReference<>(null);
    private static int titleId;
    private static int subtitleId;

    /** Track the system is currently displaying, captured from {@link MediaSession} metadata. */
    @Nullable
    private static String displayTitle;
    @Nullable
    private static String displayArtist;

    @Nullable
    private static String cachedSubtitle;

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

    private static void disableFeature() {
        ticker.stop();
        LyricsManager.getInstance().removeListener(lyricsListener);
    }

    public static void onMediaSessionSetMetadata(MediaSession session, MediaMetadata original) {
        try {
            if (original == null) {
                return;
            }
            String title = original.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = original.getString(MediaMetadata.METADATA_KEY_ARTIST);
            if (title == null || title.trim().isEmpty()
                    || artist == null || artist.trim().isEmpty()) {
                return;
            }
            String[] parsed = MetadataCleaner.parseCleanTitleAndArtist(title, artist);
            displayTitle = parsed[1];
            displayArtist = parsed[0];
            cachedSubtitle = null; // invalidate on track change

            android.net.Uri mediaUri = LyricsManager.parseMediaUri(original);
            LyricsManager.getInstance().onDisplayedTrackChanged(title, artist, mediaUri);
        } catch (Exception ex) {
            Logger.printException(() -> "onMediaSessionSetMetadata failure", ex);
        }
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

        TrackInfo current = LyricsManager.getInstance().getCurrentTrack();
        if (current != null) {
            displayTitle = current.title();
            displayArtist = current.artist();
        }

        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MINIPLAYER.get()) {
            disableFeature();
            return;
        }

        LyricsManager.getInstance().addListener(lyricsListener);
        ticker.schedule();
    }

    private static void tick() {
        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MINIPLAYER.get()) {
            disableFeature();
            return;
        }

        TextView title = titleRef.get();
        TextView subtitle = subtitleRef.get();
        if (title == null || subtitle == null) {
            disableFeature();
            return;
        }

        LyricsManager manager = LyricsManager.getInstance();
        TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            ticker.schedule();
            return;
        }

        final boolean synced = manager.areLyricsSynced()
                && Objects.equals(track.title(), displayTitle)
                && Objects.equals(track.artist(), displayArtist);

        if (synced) {
            String line = manager.getCurrentLineText();
            String newTitle = line.isEmpty() ? track.title() : line;
            if (!TextUtils.equals(newTitle, title.getText())) {
                title.setText(newTitle);
            }
            if (cachedSubtitle == null) {
                cachedSubtitle = track.displayWith(Settings.LYRICS_DISPLAY_ARTIST_FIRST.get());
            }
            if (!TextUtils.equals(cachedSubtitle, subtitle.getText())) {
                subtitle.setText(cachedSubtitle);
            }
        } else {
            if (!TextUtils.equals(track.title(), title.getText())) {
                title.setText(track.title());
            }
            if (!TextUtils.equals(track.artist(), subtitle.getText())) {
                subtitle.setText(track.artist());
            }
        }

        ticker.schedule();
    }
}
