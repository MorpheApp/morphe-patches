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
public final class MiniPlayerLyrics {

    /** How often to check whether the current lyric line changed. */
    private static final long TICK_INTERVAL_MS = 300;

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

    /** Title pushed on the last tick, to avoid redundant {@code setText} calls. */
    @Nullable
    private static volatile String lastPushedTitle;
    @Nullable
    private static volatile String lastPushedSubtitle;

    private static final Handler handler = new Handler(Looper.getMainLooper());
    private static final Runnable ticker = MiniPlayerLyrics::tick;

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
        displayTitle = MetadataCleaner.cleanTitle(title);
        displayArtist = MetadataCleaner.cleanArtist(artist);
        LyricsManager.getInstance().onDisplayedTrackChanged(title, artist);
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
            Logger.printDebug(() -> "MiniPlayerLyrics: missing resource id (title=" + titleId
                    + ", subtitle=" + subtitleId + ")");
            return;
        }

        if (!(view.findViewById(titleId) instanceof TextView title)
                || !(view.findViewById(subtitleId) instanceof TextView subtitle)) {
            Logger.printDebug(() -> "MiniPlayerLyrics: title or subtitle TextView not found");
            return;
        }

        titleRef = new WeakReference<>(title);
        subtitleRef = new WeakReference<>(subtitle);
        lastPushedTitle = null;
        lastPushedSubtitle = null;

        final TrackInfo current = LyricsManager.getInstance().getCurrentTrack();
        final CharSequence shownTitle = title.getText();
        final String cleanedShown = shownTitle == null ? "" : MetadataCleaner.cleanTitle(shownTitle.toString());
        displayTitle = cleanedShown;
        if (current != null && !cleanedShown.isEmpty() && !cleanedShown.equals(current.title())) {
            LyricsManager.getInstance().clearLyrics();
        }

        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MINIPLAYER.get()) {
            stopTicker();
            return;
        }

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
        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MINIPLAYER.get()) {
            stopTicker();
            lastPushedTitle = null;
            lastPushedSubtitle = null;
            return;
        }

        final TextView title = titleRef != null ? titleRef.get() : null;
        final TextView subtitle = subtitleRef != null ? subtitleRef.get() : null;
        if (title == null || subtitle == null) {
            // The view was released; wait for the next construction.
            stopTicker();
            lastPushedTitle = null;
            lastPushedSubtitle = null;
            return;
        }

        final LyricsManager manager = LyricsManager.getInstance();
        final TrackInfo track = manager.getCurrentTrack();
        if (track == null) {
            scheduleTick();
            return;
        }

        final boolean synced = manager.areLyricsAvailable()
                && Objects.equals(track.title(), displayTitle)
                && Objects.equals(track.artist(), displayArtist);

        if (synced) {
            final String line = manager.getCurrentLineText();
            final String newTitle = (line == null || line.isEmpty()) ? track.title() : line;
            if (!newTitle.equals(lastPushedTitle)) {
                title.setText(newTitle);
                lastPushedTitle = newTitle;
            }
            final String newSubtitle = track.artist() + " - " + track.title();
            if (!newSubtitle.equals(lastPushedSubtitle)) {
                subtitle.setText(newSubtitle);
                lastPushedSubtitle = newSubtitle;
            }
        } else {
            if (!track.title().equals(lastPushedTitle)) {
                title.setText(track.title());
                lastPushedTitle = track.title();
            }
            if (!track.artist().equals(lastPushedSubtitle)) {
                subtitle.setText(track.artist());
                lastPushedSubtitle = track.artist();
            }
        }

        scheduleTick();
    }
}
