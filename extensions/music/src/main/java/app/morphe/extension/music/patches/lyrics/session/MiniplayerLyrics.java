/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2625
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.session;

import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.text.TextUtils;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Objects;

import app.morphe.extension.music.patches.lyrics.LyricsManager;
import app.morphe.extension.music.patches.lyrics.model.MetadataCleaner;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
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
public final class MiniplayerLyrics {

    private static WeakReference<TextView> titleRef = new WeakReference<>(null);
    private static WeakReference<TextView> subtitleRef = new WeakReference<>(null);
    private static int titleId;
    private static int subtitleId;

    /** Track the system is currently displaying, captured from {@link MediaSession} metadata. */
    @Nullable
    private static String displayTitle;
    @Nullable
    private static String displayArtist;

    /**
     * The metadata's untouched title and artist. {@link LyricsManager#getCurrentTrack()}
     * carries the cleaned pair that the lyrics lookup wants, whose regex normalization folds
     * fullwidth punctuation to ASCII, so the mirrored text is built from these instead - the
     * same strings the media session shows.
     */
    @Nullable
    private static String rawDisplayTitle;
    @Nullable
    private static String rawDisplayArtist;

    @Nullable
    private static String cachedSubtitle;

    private static boolean mirrored;

    @Nullable
    private static CharSequence preMirrorTitle;
    @Nullable
    private static CharSequence preMirrorSubtitle;
    @Nullable
    private static TrackInfo preMirrorTrack;

    @Nullable
    private static String lastMirrorTitle;
    @Nullable
    private static String lastMirrorSubtitle;

    /** Drives the periodic check that mirrors the current line into the mini player. */
    private static final LyricsTicker ticker = new LyricsTicker(MiniplayerLyrics::tick);

    /** Listener that re-schedules the ticker immediately when lyrics finish loading. */
    private static final LyricsManager.Listener lyricsListener = (state, lyrics) -> {
        if (state == LyricsManager.State.LOADED || state == LyricsManager.State.NOT_FOUND) {
            ticker.schedule();
        }
    };

    private MiniplayerLyrics() {
    }

    private static void disableFeature() {
        restoreIfMirrored();
        ticker.stop();
        LyricsManager.getInstance().removeListener(lyricsListener);
    }

    /**
     * Starts or stops mirroring to match the current settings. Called when a lyrics setting
     * changes and when the displayed track changes, so toggling the feature takes effect
     * without recreating the miniplayer.
     */
    public static void onSettingsChanged() {
        cachedSubtitle = null;
        if (!Settings.LYRICS_ENABLED.get() || !Settings.LYRICS_MINIPLAYER.get()
                || titleRef.get() == null || subtitleRef.get() == null) {
            disableFeature();
            return;
        }
        LyricsManager.getInstance().addListener(lyricsListener);
        ticker.schedule();
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
            rawDisplayTitle = title;
            rawDisplayArtist = artist;
            cachedSubtitle = null; // invalidate on track change

            android.net.Uri mediaUri = LyricsManager.parseMediaUri(original);
            LyricsManager.getInstance().onDisplayedTrackChanged(title, artist, mediaUri);
            onSettingsChanged();
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

        try {
            captureMiniPlayer(view);
        } catch (Exception ex) {
            Logger.printException(() -> "onMiniPlayerViewCreated failure", ex);
        }
    }

    private static void captureMiniPlayer(View view) {
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

        onSettingsChanged();
    }

    private static void tick() {
        try {
            update();
        } catch (Exception ex) {
            Logger.printException(() -> "tick failure", ex);
            ticker.stop();
        }
    }

    private static void update() {
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
            String newTitle = line.isEmpty() ? displayTitleOr(track) : line;
            if (cachedSubtitle == null) {
                cachedSubtitle = buildSubtitle(track);
            }
            if (!mirrored) {
                preMirrorTitle = title.getText();
                preMirrorSubtitle = subtitle.getText();
                preMirrorTrack = track;
            }
            if (!TextUtils.equals(newTitle, title.getText())) {
                title.setText(newTitle);
            }
            lastMirrorTitle = newTitle;
            if (!TextUtils.equals(cachedSubtitle, subtitle.getText())) {
                subtitle.setText(cachedSubtitle);
            }
            lastMirrorSubtitle = cachedSubtitle;
            mirrored = true;
        } else {
            restoreIfMirrored();
        }

        ticker.schedule();
    }

    private static void restoreIfMirrored() {
        if (!mirrored) {
            return;
        }
        mirrored = false;
        TextView title = titleRef.get();
        TextView subtitle = subtitleRef.get();
        TrackInfo track = LyricsManager.getInstance().getCurrentTrack();
        if (title == null || subtitle == null || track == null) {
            return;
        }
        final boolean sameTrack = track.equals(preMirrorTrack);
        restoreField(title, lastMirrorTitle, sameTrack ? preMirrorTitle : null, displayTitleOr(track));
        restoreField(subtitle, lastMirrorSubtitle, sameTrack ? preMirrorSubtitle : null,
                displayArtistOr(track));
    }

    /** The title the app itself shows: the untouched metadata title when it is known. */
    private static String displayTitleOr(TrackInfo track) {
        final String raw = rawDisplayTitle;
        return raw != null && !raw.trim().isEmpty() ? raw : track.title();
    }

    /** The artist the app itself shows: the untouched metadata artist when it is known. */
    private static String displayArtistOr(TrackInfo track) {
        final String raw = rawDisplayArtist;
        return raw != null && !raw.trim().isEmpty() ? raw : track.artist();
    }

    /**
     * The {@code "artist - title"} line the app and the media session show. The cleaned track
     * feeds the lyrics lookup only: its regex normalization strips decorations and folds
     * fullwidth punctuation to ASCII, which must not leak into what is displayed.
     */
    private static String buildSubtitle(TrackInfo track) {
        final String rawTitle = rawDisplayTitle;
        final String rawArtist = rawDisplayArtist;
        if (rawTitle == null || rawTitle.trim().isEmpty()
                || rawArtist == null || rawArtist.trim().isEmpty()) {
            return track.displayWith(Settings.LYRICS_DISPLAY_ARTIST_FIRST.get());
        }
        return new TrackInfo(rawTitle, rawArtist, "", 0)
                .displayWith(Settings.LYRICS_DISPLAY_ARTIST_FIRST.get());
    }

    private static void restoreField(TextView view, @Nullable String lastMirrored,
            @Nullable CharSequence preMirror, @Nullable String fallback) {
        final CharSequence current = view.getText();
        if (!TextUtils.equals(current, lastMirrored)) {
            return;
        }
        final CharSequence restore = (preMirror != null && !preMirror.toString().isEmpty())
                ? preMirror
                : fallback;
        if (restore != null && !TextUtils.equals(current, restore)) {
            view.setText(restore);
        }
    }
}
