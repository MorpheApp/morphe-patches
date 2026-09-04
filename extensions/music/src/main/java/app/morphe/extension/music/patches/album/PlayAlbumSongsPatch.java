/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2556
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.album;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * Plays the song version of an album track that the app queued as a music video.
 *
 * <p>The track is not reopened. Instead the streams of the song are served under the video id of
 * the music video, so the app keeps the album queue it built and never restarts the player.
 */
@SuppressWarnings("unused")
public class PlayAlbumSongsPatch {

    /**
     * An album track, identified the same way the player response identifies it.
     */
    private record AlbumTrack(String playlistId, int playlistIndex) {
    }

    // YouTube Music album playlist ids share a stable "OLAK" prefix.
    private static final String YOUTUBE_MUSIC_ALBUM_PREFIX = "OLAK";

    /**
     * Key of the built-in 'Don't play music videos' setting.
     */
    private static final String DONT_PLAY_VIDEO_SETTING_KEY = "pref_key_dont_play_video";

    private static final int NUMBER_OF_LAST_VIDEO_IDS_TO_TRACK = 10;

    /**
     * The streams cannot be fetched until the album is known, so playback of the first track of an
     * album waits for it. Giving up leaves the music video playing.
     */
    private static final long MAX_MILLISECONDS_TO_WAIT_FOR_ALBUM = 5000;

    /**
     * Album track the song streams of each music video came from.
     */
    @GuardedBy("itself")
    private static final Map<String, PlaylistRequest.Song> songs = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, PlaylistRequest.Song> eldest) {
            return size() > NUMBER_OF_LAST_VIDEO_IDS_TO_TRACK;
        }
    };

    /**
     * Album position of the videos of the most recent player responses.
     */
    @GuardedBy("itself")
    private static final Map<String, AlbumTrack> albumTracks = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, AlbumTrack> eldest) {
            return size() > NUMBER_OF_LAST_VIDEO_IDS_TO_TRACK;
        }
    };

    /**
     * Notified when the streams to serve under a video are known, which can be after the app
     * has already set up playback of the music video.
     */
    public interface SubstitutionListener {
        void videoIdResolved(@NonNull String videoId, @NonNull String resolvedVideoId);
    }

    private static final Collection<SubstitutionListener> substitutionListeners =
            new CopyOnWriteArrayList<>();

    static {
        StreamingDataRequest.setVideoIdResolver(PlayAlbumSongsPatch::resolveVideoIdToFetch);
        SpoofVideoStreamsPatch.setVideoLengthResolver(PlayAlbumSongsPatch::songLengthSeconds);
    }

    /**
     * @param listener Notified for every video the streams are fetched for, including those
     *                 left playing as the music video.
     */
    public static void addSubstitutionListener(@NonNull SubstitutionListener listener) {
        substitutionListeners.add(listener);
    }

    private static boolean isEnabled() {
        return Settings.PLAY_ALBUMS_SONGS.get();
    }

    /**
     * Injection point.
     */
    public static void newPlayerResponse(@NonNull String videoId,
                                         @NonNull String playlistId,
                                         int playlistIndex) {
        try {
            if (!isEnabled()) return;
            if (playlistIndex < 0 || !playlistId.startsWith(YOUTUBE_MUSIC_ALBUM_PREFIX)) {
                forgetSubstitution(videoId, playlistId);
                return;
            }

            synchronized (albumTracks) {
                AlbumTrack existing = albumTracks.get(videoId);
                if (existing != null
                        && existing.playlistIndex() == playlistIndex
                        && existing.playlistId().equals(playlistId)) {
                    return;
                }
                albumTracks.put(videoId, new AlbumTrack(playlistId, playlistIndex));
            }

            // Runs before the app requests the streams of this video, which is what gives the
            // album fetch a head start and lets every later track of the album resolve instantly.
            PlaylistRequest.fetchRequestIfNeeded(videoId, playlistId);
        } catch (Exception ex) {
            Logger.printException(() -> "newPlayerResponse failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * <p>The built-in 'Don't play music videos' setting reloads the player to drop the video track
     * of the same recording, which fights this patch and leaves playback restarting every few
     * seconds. While this patch is on it plays the song version anyway, so the built-in setting is
     * reported as off to everything that reads it, including the settings screen showing it.
     *
     * @return Whether to ignore the setting the app is reading.
     */
    public static boolean ignoreDontPlayMusicVideoSetting(String key) {
        try {
            return DONT_PLAY_VIDEO_SETTING_KEY.equals(key) && isEnabled();
        } catch (Exception ex) {
            Logger.printException(() -> "ignoreDontPlayMusicVideoSetting failure", ex);
            return false;
        }
    }

    /**
     * Stops serving the song of an album to a video that is now played outside of that album,
     * which otherwise keeps the music video replaced until the app is restarted.
     */
    private static void forgetSubstitution(@NonNull String videoId, @NonNull String playlistId) {
        synchronized (albumTracks) {
            AlbumTrack track = albumTracks.get(videoId);
            // The same album can build the player parameter again without a position,
            // and that is still the album playing rather than the music video itself.
            if (track == null || track.playlistId().equals(playlistId)) return;
            albumTracks.remove(videoId);
        }
        synchronized (songs) {
            songs.remove(videoId);
        }
        Logger.printDebug(() -> "No longer playing the song version of: " + videoId);
    }

    /**
     * @return The album track playing under the given video, or null if it is not substituted.
     */
    @Nullable
    public static PlaylistRequest.Song getSong(@Nullable String videoId) {
        if (videoId == null || !isEnabled()) return null;
        synchronized (songs) {
            return songs.get(videoId);
        }
    }

    /**
     * @return Length of the song playing under the given video, or zero if it is not substituted.
     */
    private static long songLengthSeconds(@NonNull String videoId) {
        PlaylistRequest.Song song = getSong(videoId);
        return song == null ? 0 : song.durationSeconds();
    }

    /**
     * Called off the main thread, just before the streams of the video are fetched.
     */
    private static String resolveVideoIdToFetch(@NonNull String videoId) {
        try {
            if (!isEnabled()) return videoId;

            String resolvedVideoId = resolveAlbumSong(videoId);

            for (SubstitutionListener listener : substitutionListeners) {
                listener.videoIdResolved(videoId, resolvedVideoId);
            }
            return resolvedVideoId;
        } catch (Exception ex) {
            Logger.printException(() -> "resolveVideoIdToFetch failure", ex);
            return videoId;
        }
    }

    /**
     * @return The video whose streams to serve for the given video, which is the video itself
     *         when it is not an album track playing as a music video.
     */
    private static String resolveAlbumSong(@NonNull String videoId) {
        AlbumTrack track;
        synchronized (albumTracks) {
            track = albumTracks.get(videoId);
        }
        if (track == null) return videoId;

        PlaylistRequest request =
                PlaylistRequest.getRequestForPlaylistId(track.playlistId());
        if (request == null) return videoId;

        PlaylistRequest.Song song = request.awaitSong(
                track.playlistIndex(), MAX_MILLISECONDS_TO_WAIT_FOR_ALBUM);
        if (song == null) {
            Logger.printDebug(() -> "Official song not found, videoId: " + videoId);
            return videoId;
        }
        if (song.videoId().equals(videoId)) {
            // The album track has no music video, or is already the song version.
            return videoId;
        }

        synchronized (songs) {
            songs.put(videoId, song);
        }
        return song.videoId();
    }
}
