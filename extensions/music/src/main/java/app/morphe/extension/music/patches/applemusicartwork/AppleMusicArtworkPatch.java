/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5082
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.applemusicartwork;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.Nullable;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Shows the animated album artwork of Apple Music over the player artwork.
 */
@SuppressWarnings("unused")
public final class AppleMusicArtworkPatch {

    private static final boolean ENABLED = Settings.APPLE_MUSIC_ARTWORK.get();

    private static final String CACHE_FOLDER_NAME = "apple_music_artwork";
    private static final int MAX_CACHED_ARTWORKS = 40;

    /**
     * Index of the stream variant used for each song, after a variant failed to play.
     */
    private static final String VARIANT_PREFERENCES_NAME = "apple_music_artwork";

    /**
     * Searches and downloads one artwork at a time.
     */
    private static final ExecutorService executor = Executors.newSingleThreadExecutor();

    private static final class Song {
        final String title;
        final String artist;
        @Nullable
        final String album;
        final String key;

        Song(String title, String artist, @Nullable String album) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.key = title + '|' + artist + '|' + album;
        }

        @Override
        public String toString() {
            return key;
        }
    }

    /**
     * Song that is played. Set on the main thread.
     */
    @Nullable
    private static volatile Song currentSong;

    private static volatile boolean musicPlaying;

    // Main thread only.

    /**
     * Downloaded artwork of the current song, or null if it has none or it is still searched.
     */
    @Nullable
    private static File artworkFile;

    /**
     * If the artwork of the current song already failed to play once.
     */
    private static boolean artworkRetried;

    private static WeakReference<ImageView> thumbnailRef = new WeakReference<>(null);

    private static WeakReference<AnimatedArtworkView> artworkViewRef = new WeakReference<>(null);

    private static final List<WeakReference<AnimatedArtworkView>> extraArtworkViews = new ArrayList<>();

    public interface ArtworkFileListener {
        void onArtworkFileChanged(@Nullable File file);
    }

    private static final List<WeakReference<ArtworkFileListener>> artworkFileListeners = new ArrayList<>();

    public static void addArtworkFileListener(ArtworkFileListener listener) {
        artworkFileListeners.removeIf(ref -> ref.get() == null || ref.get() == listener);
        artworkFileListeners.add(new WeakReference<>(listener));
    }

    public static void removeArtworkFileListener(ArtworkFileListener listener) {
        artworkFileListeners.removeIf(ref -> ref.get() == null || ref.get() == listener);
    }

    private static void notifyArtworkFileChanged() {
        Utils.runOnMainThread(() -> {
            List<ArtworkFileListener> targets = new ArrayList<>();
            for (WeakReference<ArtworkFileListener> ref : artworkFileListeners) {
                ArtworkFileListener listener = ref.get();
                if (listener != null) targets.add(listener);
            }
            artworkFileListeners.removeIf(ref -> ref.get() == null);
            for (ArtworkFileListener listener : targets) {
                listener.onArtworkFileChanged(artworkFile);
            }
        });
    }

    /**
     * Injection point.
     *
     * @param thumbnail Artwork of the player.
     */
    public static void setPlayerThumbnail(ImageView thumbnail) {
        if (!ENABLED) return;

        try {
            thumbnailRef = new WeakReference<>(thumbnail);
            updateArtworkView();
        } catch (Exception ex) {
            Logger.printException(() -> "setPlayerThumbnail failure", ex);
        }
    }

    @Nullable
    public static File currentArtworkFile() {
        return ENABLED ? artworkFile : null;
    }

    @Nullable
    public static View attachAnimatedArtwork(ImageView thumbnail) {
        File file = currentArtworkFile();
        if (file == null) return null;
        try {
            AnimatedArtworkView view = AnimatedArtworkView.addAbove(thumbnail, file);
            extraArtworkViews.add(new WeakReference<>(view));
            view.updatePlayback();
            return view;
        } catch (Exception ex) {
            Logger.printException(() -> "attachAnimatedArtwork failure", ex);
            return null;
        }
    }

    public static void detachAnimatedArtwork(@Nullable View view) {
        if (!(view instanceof AnimatedArtworkView animated)) return;
        extraArtworkViews.removeIf(ref -> ref.get() == animated);
        animated.remove();
    }

    /**
     * Injection point.
     */
    public static void onSetMetadata(@Nullable MediaMetadata metadata) {
        if (!ENABLED || metadata == null) return;

        try {
            String title = getMetadataString(metadata, MediaMetadata.METADATA_KEY_TITLE);
            String artist = getMetadataString(metadata, MediaMetadata.METADATA_KEY_ARTIST);
            // The app also sets the metadata without the artist while a song plays.
            if (title == null || artist == null) return;

            Song song = new Song(title, artist, getMetadataString(metadata, MediaMetadata.METADATA_KEY_ALBUM));
            Utils.runOnMainThread(() -> setCurrentSong(song));
        } catch (Exception ex) {
            Logger.printException(() -> "onSetMetadata failure", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onSetPlaybackState(@Nullable PlaybackState state) {
        if (!ENABLED || state == null) return;

        final int playbackState = state.getState();
        musicPlaying = playbackState == PlaybackState.STATE_PLAYING
                || playbackState == PlaybackState.STATE_BUFFERING;

        Utils.runOnMainThread(() -> {
            AnimatedArtworkView artworkView = artworkViewRef.get();
            if (artworkView != null) {
                artworkView.updatePlayback();
            }
            for (WeakReference<AnimatedArtworkView> ref : extraArtworkViews) {
                AnimatedArtworkView extra = ref.get();
                if (extra != null) {
                    extra.updatePlayback();
                }
            }
            extraArtworkViews.removeIf(ref -> ref.get() == null);
        });
    }

    static boolean isMusicPlaying() {
        return musicPlaying;
    }

    @Nullable
    private static String getMetadataString(MediaMetadata metadata, String key) {
        String value = metadata.getString(key);
        if (value == null) return null;
        value = value.trim();
        return value.isEmpty() ? null : value;
    }

    private static void setCurrentSong(Song song) {
        Song previousSong = currentSong;
        if (previousSong != null && previousSong.key.equals(song.key)) return;

        Logger.printDebug(() -> "Song changed: " + song);
        currentSong = song;
        artworkFile = null;
        artworkRetried = false;
        updateArtworkView();
        notifyArtworkFileChanged();

        executor.execute(() -> findArtwork(song));
    }

    /**
     * Adds, replaces or removes the artwork view to match the current artwork and player.
     */
    private static void updateArtworkView() {
        ImageView thumbnail = thumbnailRef.get();
        File file = artworkFile;

        AnimatedArtworkView artworkView = artworkViewRef.get();
        if (artworkView != null && !artworkView.shows(thumbnail, file)) {
            artworkView.remove();
            artworkView = null;
        }

        if (artworkView == null && thumbnail != null && file != null) {
            artworkView = AnimatedArtworkView.addAbove(thumbnail, file);
        }

        artworkViewRef = new WeakReference<>(artworkView);
    }

    /**
     * Called when the artwork view could not play its file.
     */
    static void onArtworkPlaybackError(AnimatedArtworkView artworkView) {
        if (artworkView != artworkViewRef.get()) return;

        artworkView.remove();
        artworkViewRef = new WeakReference<>(null);

        if (!artworkRetried) {
            Logger.printDebug(() -> "Artwork failed to play, trying again");
            artworkRetried = true;
            updateArtworkView();
            return;
        }

        Song song = currentSong;
        File file = artworkFile;
        artworkFile = null;
        notifyArtworkFileChanged();
        if (song == null || file == null) return;

        executor.execute(() -> {
            try {
                Logger.printDebug(() -> "Artwork failed to play, using the next variant");
                SharedPreferences preferences = getVariantPreferences();
                String songId = getSongId(song);
                preferences.edit().putInt(songId, preferences.getInt(songId, 0) + 1).apply();
                Utils.deleteQuietly(file);

                findArtwork(song);
            } catch (Exception ex) {
                Logger.printException(() -> "onArtworkPlaybackError failure", ex);
            }
        });
    }

    private static void findArtwork(Song song) {
        try {
            if (song != currentSong) return;

            Context context = Utils.getContext();
            String songId = getSongId(song);
            final int variant = getVariantPreferences().getInt(songId, 0);
            File folder = new File(context.getCacheDir(), CACHE_FOLDER_NAME);
            File file = new File(folder, songId + "_" + variant + ".mp4");

            if (!file.exists()) {
                String playlistUrl = AppleMusicCatalog.findMotionArtwork(song.title, song.artist, song.album);
                if (playlistUrl == null) {
                    Logger.printDebug(() -> "No animated artwork for: " + song);
                    return;
                }
                if (song != currentSong) return;

                List<String> variantUrls = HlsDownloader.getVariantUrls(playlistUrl);
                if (variant >= variantUrls.size()) {
                    Logger.printDebug(() -> "No artwork variant could be played for: " + song);
                    return;
                }

                if (!folder.isDirectory() && !folder.mkdirs()) {
                    throw new IOException("Could not create folder: " + folder);
                }
                HlsDownloader.download(variantUrls.get(variant), file);
                Logger.printDebug(() -> "Downloaded artwork variant " + variant + " of "
                        + variantUrls.size() + " (" + file.length() + " bytes) for: " + song);

                trimCache(folder);
            }

            Utils.runOnMainThread(() -> {
                if (song != currentSong) return;
                artworkFile = file;
                updateArtworkView();
                notifyArtworkFileChanged();
            });
        } catch (IOException ex) {
            Logger.printInfo(() -> "Could not get the artwork of: " + song, ex);
        } catch (Exception ex) {
            Logger.printException(() -> "findArtwork failure", ex);
        }
    }

    private static SharedPreferences getVariantPreferences() {
        return Utils.getContext().getSharedPreferences(VARIANT_PREFERENCES_NAME, Context.MODE_PRIVATE);
    }

    private static String getSongId(Song song) throws NoSuchAlgorithmException {
        byte[] digest = MessageDigest.getInstance("MD5").digest(song.key.getBytes(StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            builder.append(Character.forDigit((b >> 4) & 0xF, 16));
            builder.append(Character.forDigit(b & 0xF, 16));
        }
        return builder.toString();
    }

    /**
     * Deletes the oldest artworks.
     */
    private static void trimCache(File folder) {
        File[] files = folder.listFiles();
        if (files == null || files.length <= MAX_CACHED_ARTWORKS) return;

        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0, count = files.length - MAX_CACHED_ARTWORKS; i < count; i++) {
            Utils.deleteQuietly(files[i]);
        }
    }
}
