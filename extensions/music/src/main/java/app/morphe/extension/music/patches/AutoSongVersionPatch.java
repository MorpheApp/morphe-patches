/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4595
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.AUDIO_TYPE;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.INNERTUBE_KEY_QUERY;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.INNERTUBE_URL;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.SEARCH_URL;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.SONG_VERSIONS_TO_REMEMBER;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.VIDEO_TYPE;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.await;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.capturedQueue;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.isVideoId;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.musicVideoType;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.normalize;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.notifyQueueChanged;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.rememberSwitch;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.runOnQueueExecutor;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.switchInQueue;
import static app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.videoAt;
import static app.morphe.extension.music.patches.album.PlayAlbumSongsPatch.DONT_PLAY_VIDEO_SETTING_KEY;
import static app.morphe.extension.music.patches.album.PlayAlbumSongsPatch.MAX_MILLISECONDS_TO_WAIT_FOR_ALBUM;
import static app.morphe.extension.music.patches.album.PlayAlbumSongsPatch.YOUTUBE_MUSIC_ALBUM_PREFIX;

import android.content.SharedPreferences;
import android.os.SystemClock;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.music.patches.EnableAudioVideoSwitchPatch.QueueAccess;
import app.morphe.extension.music.patches.album.PlaylistRequest;
import app.morphe.extension.music.patches.downloads.DownloadedMark;
import app.morphe.extension.music.patches.jam.QueueCommand;
import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.music.shared.VideoInformation;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.requests.Requester;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;

/**
 * Replaces music videos with their song versions, as real queue entries.
 */
@SuppressWarnings("unused")
public final class AutoSongVersionPatch {

    /** The patch makes this implement the app's queue listener, every callback calls onQueueChanged. */
    public static final class QueueListener {
    }

    /** Inserted too late to move. Gets removed if it still shows up during the same track. */
    private record LateInsert(String songId, String playingVideoId) {
    }

    private static final String NEXT_URL = INNERTUBE_URL + "next" + INNERTUBE_KEY_QUERY;
    private static final String PLAYER_URL = INNERTUBE_URL + "player" + INNERTUBE_KEY_QUERY;
    private static final String WEB_CLIENT_VERSION = "1.20260914.01.00";
    private static final long DAY_MILLISECONDS = 86_400_000L;
    private static final int CONNECT_TIMEOUT_MILLISECONDS = 5_000;
    private static final int READ_TIMEOUT_MILLISECONDS = 8_000;

    /** Limits a search to the Songs tab. */
    private static final String SONGS_FILTER = "EgWKAQIIAWoKEAkQBRAKEAMQBA==";
    private static final int MAX_LENGTH_DIFFERENCE_SECONDS = 45;
    private static final String NO_SONG_VERSION = "";

    /** Different recording than the plain song, so these have to match too. */
    private static final Pattern VARIANT_WORDS = Pattern.compile(
            "\\b(remix|live|acoustic|instrumental|sped up|slowed|edit|extended|karaoke|cover)\\b");

    private static final int RECENT_ALBUMS_TO_SEARCH = 5;

    private static final long MAX_HOLD_MILLISECONDS = 1_500;
    private static final long WAIT_FOR_CURRENT_TRACK_MILLISECONDS = 1_000;
    private static final long WAIT_FOR_INSERT_MILLISECONDS = 4_000;
    private static final long WAIT_FOR_MOVE_MILLISECONDS = 1_500;
    private static final int TRACKS_TO_LOOK_AHEAD = 15;
    private static final long RETRY_FAILED_MILLISECONDS = 60_000;

    /** Albums played recently, oldest first. */
    private static final Set<String> recentAlbums = new LinkedHashSet<>();

    /** Song version per video, or NO_SONG_VERSION if there is none. */
    private static final Map<String, String> songVersions = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(SONG_VERSIONS_TO_REMEMBER));

    /** So a failed track isn't retried on every queue change. */
    private static final Map<String, Long> failedTracks = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(SONG_VERSIONS_TO_REMEMBER));

    private static final AtomicBoolean lookingAhead = new AtomicBoolean();
    private static final AtomicBoolean lookAheadRequested = new AtomicBoolean();

    /** One queue edit at a time. Don't hold this during network requests. */
    private static final Object queueEditLock = new Object();

    private static volatile WeakReference<Object> listenedLane = new WeakReference<>(null);

    @Nullable
    private static volatile LateInsert lateInsert;

    /** The app's per account preferences. */
    @Nullable
    private static volatile SharedPreferences accountPreferences;

    static {
        VideoInformation.addVideoIdListener(AutoSongVersionPatch::autoSwitchToSongVersion);
        StreamingDataRequest.setFetchGate(AutoSongVersionPatch::holdMusicVideo);
    }

    private AutoSongVersionPatch() {
    }

    /**
     * Injection point.
     */
    public static void setAccountPreferences(SharedPreferences preferences) {
        accountPreferences = preferences;
    }

    /** Keep what was picked with the toggle. Leaving it allows replacing it again. */
    static void keepChosenVersion(String chosenVideoId, String previousVideoId) {
        songVersions.put(chosenVideoId, NO_SONG_VERSION);
        songVersions.remove(previousVideoId);
    }

    /**
     * Injection point. Remembers the album being played so we can prefer its tracks.
     */
    public static void trackPlaylist(String videoId, String playlistId, int playlistIndex) {
        try {
            if (!shouldPlaySongVersions()) return;
            prepareQueue();

            if (playlistId == null || !playlistId.startsWith(YOUTUBE_MUSIC_ALBUM_PREFIX)) {
                // Don't forget the album here, a song we queued starts with its own playlist
                // while the album is still playing.
                return;
            }
            synchronized (recentAlbums) {
                recentAlbums.remove(playlistId);
                recentAlbums.add(playlistId);
                if (recentAlbums.size() > RECENT_ALBUMS_TO_SEARCH) {
                    recentAlbums.remove(recentAlbums.iterator().next());
                }
            }
            PlaylistRequest.fetchRequestIfNeeded(videoId, playlistId);
        } catch (Exception ex) {
            Logger.printException(() -> "trackPlaylist failure", ex);
        }
    }

    /** Replace a music video that just started with the song. */
    private static void autoSwitchToSongVersion(String videoId) {
        if (!shouldPlaySongVersions() || !isVideoId(videoId)) return;

        Utils.runOnBackgroundThread(() -> {
            try {
                if (!videoId.equals(VideoInformation.getVideoId())) return;

                QueueAccess access = capturedQueue();
                if (access == null) {
                    Logger.printDebug(() -> "song version: no queue captured");
                    return;
                }
                if (!isCurrentTrack(access, videoId, WAIT_FOR_CURRENT_TRACK_MILLISECONDS)) {
                    Logger.printDebug(() -> "song version: queue did not reach " + videoId);
                    return;
                }

                listenToQueue(access);

                // Also runs when a song starts, so what comes after it gets prepared here.
                if (!switchCurrentToSong(access, videoId)) {
                    lookAheadAndReplace(access);
                }
            } catch (Exception ex) {
                // Not remembered, next time this track plays it's tried again.
                Logger.printInfo(() -> "song version lookup failed: " + videoId, ex);
            }
        });
    }

    /**
     * Holds a track that's about to start until we know its song version.
     * Not on the main thread, runs right before the streams are fetched.
     */
    private static void holdMusicVideo(String videoId) {
        if (!shouldPlaySongVersions() || !isVideoId(videoId)) return;
        if (songVersions.containsKey(videoId)) return;

        QueueAccess access = capturedQueue();
        if (access == null) return;
        // The app fetches the next track's streams early, those should never wait.
        if (videoId.equals(videoAt(access, access.patch_atvCurrentIndex() + 1))) return;

        // A tapped track only shows as current after its streams loaded so it can't be replaced
        // here yet. With the answer ready it happens right away.
        CountDownLatch finished = new CountDownLatch(1);
        Utils.runOnBackgroundThread(() -> {
            try {
                songVersionOf(videoId);
            } catch (Exception ex) {
                Logger.printInfo(() -> "song version lookup failed: " + videoId, ex);
            } finally {
                finished.countDown();
            }
        });

        try {
            // Don't hold longer than this, a slow lookup can finish while it plays.
            if (!finished.await(MAX_HOLD_MILLISECONDS, TimeUnit.MILLISECONDS)) {
                Logger.printDebug(() -> "song version: stopped holding " + videoId);
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * @return If the track that is current in the queue was replaced by its song version.
     */
    private static boolean switchCurrentToSong(QueueAccess access, String videoId) throws Exception {
        if (!isCurrentTrack(access, videoId, 0)) return false;

        String songId = songVersionOf(videoId);
        if (songId.isEmpty()) return false;

        synchronized (queueEditLock) {
            if (!isCurrentTrack(access, videoId, 0)) return false;

            // The song plays next and doesn't need its own lookup.
            songVersions.put(songId, NO_SONG_VERSION);
            rememberSwitch(videoId, songId);
            final boolean switched = switchInQueue(Utils.getContext(), access, videoId, songId);
            Logger.printDebug(() -> "song version: " + videoId + " -> " + songId + " switched: " + switched);
            return switched;
        }
    }

    private static boolean isCurrentTrack(QueueAccess access, String videoId, long waitMilliseconds) {
        return await(() -> videoId.equals(videoAt(access, access.patch_atvCurrentIndex())), waitMilliseconds);
    }

    /**
     * A new queue starting with the track played last isn't a new video,
     * so also prepare the queue when a track is requested.
     */
    private static void prepareQueue() {
        Utils.runOnBackgroundThread(() -> {
            QueueAccess access = capturedQueue();
            if (access == null) return;
            listenToQueue(access);
            lookAheadAndReplace(access);
        });
    }

    /** Get told when the queue changes. Works without it too, just from track changes only. */
    private static void listenToQueue(QueueAccess access) {
        try {
            Executor executor = access.patch_atvExecutor();
            if (executor == null) return;
            runOnQueueExecutor(executor, () -> {
                Object lane = access.patch_atvPrimaryLane();
                if (lane == null || lane == listenedLane.get()) return;

                access.patch_atvAddListener(lane, new QueueListener());
                listenedLane = new WeakReference<>(lane);
                Logger.printDebug(() -> "song version: listening to the queue");
            });
        } catch (Exception ex) {
            Logger.printInfo(() -> "song version could not listen to the queue", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void onQueueChanged() {
        try {
            notifyQueueChanged();
            if (!shouldPlaySongVersions()) return;

            QueueAccess access = capturedQueue();
            if (access != null) lookAheadAndReplace(access);
        } catch (Exception ex) {
            Logger.printException(() -> "onQueueChanged failure", ex);
        }
    }

    /**
     * Looks up the upcoming tracks and replaces the music videos, runs again if something
     * changed meanwhile. Replacing a track changes the queue too but then there's nothing
     * left to replace, so it stops there.
     */
    private static void lookAheadAndReplace(QueueAccess access) {
        lookAheadRequested.set(true);
        if (!lookingAhead.compareAndSet(false, true)) return;

        Utils.runOnBackgroundThread(() -> {
            try {
                while (lookAheadRequested.getAndSet(false)) {
                    if (!shouldPlaySongVersions()) continue;
                    String currentId = videoAt(access, access.patch_atvCurrentIndex());
                    if (currentId != null) replaceUpcomingMusicVideos(access, currentId);
                }
            } catch (Exception ex) {
                Logger.printInfo(() -> "song version look ahead failed", ex);
            } finally {
                lookingAhead.set(false);
                // Requested after the last check, would be lost otherwise.
                if (lookAheadRequested.get()) lookAheadAndReplace(access);
            }
        });
    }

    private static boolean failedRecently(String videoId) {
        Long failed = failedTracks.get(videoId);
        return failed != null && SystemClock.uptimeMillis() - failed < RETRY_FAILED_MILLISECONDS;
    }

    /** Only while the stock 'Don't play music videos' is on, that's when Premium does it. */
    private static boolean shouldPlaySongVersions() {
        if (!Settings.AUTO_SONG_VERSION.get()) return false;

        SharedPreferences preferences = accountPreferences;
        return preferences != null && preferences.getBoolean(DONT_PLAY_VIDEO_SETTING_KEY, false);
    }

    /** Replaces upcoming music videos so the queue shows songs and they never start as the video. */
    private static void replaceUpcomingMusicVideos(QueueAccess access, String currentId) throws Exception {
        final int index = access.patch_atvCurrentIndex();

        for (int offset = 1; offset <= TRACKS_TO_LOOK_AHEAD; offset++) {
            String videoId = videoAt(access, index + offset);
            if (videoId == null) break;
            if (!isVideoId(videoId)) continue;
            try {
                songVersionOf(videoId);
            } catch (Exception ex) {
                // One failed lookup shouldn't stop the others.
                Logger.printInfo(() -> "song version lookup failed: " + videoId, ex);
            }
        }
        if (!access.patch_atvLocal()) return;

        Executor executor = access.patch_atvExecutor();
        if (executor == null) return;

        LateInsert late = lateInsert;
        if (late != null) {
            if (!late.playingVideoId().equals(currentId)) {
                // Another track started, whatever is at this position isn't that insert anymore.
                lateInsert = null;
            } else if (late.songId().equals(videoAt(access, index + 1))) {
                lateInsert = null;
                runOnQueueExecutor(executor, () -> removeTrackAt(access, index + 1, late.songId()));
                return; // Removing it changes the queue, which runs this again.
            }
        }

        for (int offset = 1; offset <= TRACKS_TO_LOOK_AHEAD; offset++) {
            String videoId = videoAt(access, index + offset);
            if (videoId == null) break;

            String songId = songVersions.get(videoId);
            if (songId == null || songId.isEmpty() || failedRecently(videoId)) continue;

            synchronized (queueEditLock) {
                if (!replaceTrack(access, executor, currentId, index, offset, videoId, songId)) return;
            }
        }
    }

    /**
     * Inserts the song after the current track (only place the queue can insert), moves it
     * to where the music video is and removes the video.
     *
     * @return If the queue still looks right and the next one can be replaced.
     */
    private static boolean replaceTrack(QueueAccess access, Executor executor, String currentId,
                                        int index, int offset, String videoId, String songId) throws Exception {
        final int songInserted = index + 1;
        final int songTarget = index + offset;
        final int videoAfterInsert = index + offset + 1;
        final byte[] command = QueueCommand.encode(songId, true);

        boolean[] sent = {false};
        runOnQueueExecutor(executor, () -> {
            if (access.patch_atvCurrentIndex() != index
                    || !currentId.equals(videoAt(access, index))
                    || !videoId.equals(videoAt(access, songTarget))) {
                return;
            }
            access.patch_atvEnqueue(command);
            sent[0] = true;
        });
        if (!sent[0]) return false;

        songVersions.put(songId, NO_SONG_VERSION);
        rememberSwitch(videoId, songId);

        if (!await(() -> songId.equals(videoAt(access, songInserted)), WAIT_FOR_INSERT_MILLISECONDS)) {
            Logger.printDebug(() -> "song version: was not inserted: " + songId);
            lateInsert = new LateInsert(songId, currentId);
            failedTracks.put(videoId, SystemClock.uptimeMillis());
            return false;
        }

        if (songInserted != songTarget) {
            boolean[] moved = {false};
            runOnQueueExecutor(executor, () -> {
                if (access.patch_atvCurrentIndex() != index
                        || !songId.equals(videoAt(access, songInserted))
                        || !videoId.equals(videoAt(access, videoAfterInsert))) {
                    return;
                }
                access.patch_atvMove(songInserted, songTarget);
                moved[0] = true;
            });

            if (!moved[0] || !await(() -> songId.equals(videoAt(access, songTarget)), WAIT_FOR_MOVE_MILLISECONDS)) {
                failedTracks.put(videoId, SystemClock.uptimeMillis());
                // Put the queue back how it was.
                runOnQueueExecutor(executor, () -> removeTrackAt(access, songInserted, songId));
                Logger.printDebug(() -> "song version: could not move " + songId + " to " + songTarget);
                return false;
            }
        }

        runOnQueueExecutor(executor, () -> removeTrackAt(access, videoAfterInsert, videoId));
        Logger.printDebug(() -> "song version: upcoming " + videoId + " -> " + songId);
        return true;
    }

    private static void removeTrackAt(QueueAccess access, int position, String videoId) {
        Object[] items = access.patch_atvItems();
        if (items != null && position < items.length
                && videoId.equals(access.patch_atvVideoId(items[position]))) {
            access.patch_atvRemove(items[position]);
        }
    }

    /**
     * @return Track with this title and artist from a recent album, newest first, or NO_SONG_VERSION.
     */
    private static String findInRecentAlbums(String videoId, String title, String wantedTitle, String wantedArtist) {
        List<String> albums;
        synchronized (recentAlbums) {
            albums = new ArrayList<>(recentAlbums);
        }
        Collections.reverse(albums);

        for (String playlistId : albums) {
            PlaylistRequest request = PlaylistRequest.getRequestForPlaylistId(playlistId);
            if (request == null) continue;

            for (PlaylistRequest.Song song : request.awaitSongs(MAX_MILLISECONDS_TO_WAIT_FOR_ALBUM)) {
                if (song == null || !isVideoId(song.videoId()) || song.videoId().equals(videoId)) continue;
                if (wantedTitle.equals(comparableTitle(song.title(), wantedArtist))
                        && normalize(song.artist()).contains(wantedArtist)
                        && variantWords(title).equals(variantWords(song.title()))) {
                    return song.videoId();
                }
            }
        }
        return NO_SONG_VERSION;
    }

    /**
     * @return If the search result is on a real album and not a single named after the song.
     */
    private static boolean isFromAlbum(JSONArray detailRuns, String wantedTitle) {
        // Artists, separator, album, separator, length.
        final int albumIndex = detailRuns.length() - 3;
        if (albumIndex < 2) return false;
        String album = normalize(detailRuns.optJSONObject(albumIndex).optString("text", ""));
        return !album.isEmpty() && !album.equals(wantedTitle);
    }

    private static boolean isDownloaded(String videoId) {
        return Settings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP && DownloadedMark.isDownloaded(videoId);
    }

    private static String songVersionOf(String videoId) throws Exception {
        // Downloaded videos play offline, the song wouldn't.
        if (isDownloaded(videoId)) return NO_SONG_VERSION;

        String known = songVersions.get(videoId);
        if (known != null) return known;

        // So a bad connection doesn't repeat every lookup on every queue change.
        if (failedRecently(videoId)) return NO_SONG_VERSION;

        try {
            String songId = findSongVersion(videoId);
            songVersions.put(videoId, songId);
            failedTracks.remove(videoId);
            return songId;
        } catch (Exception ex) {
            failedTracks.put(videoId, SystemClock.uptimeMillis());
            throw ex;
        }
    }

    /**
     * @return Song with the same title, artist and about the same length, or NO_SONG_VERSION.
     */
    private static String findSongVersion(String videoId) throws Exception {
        JSONObject track = findQueueTrack(videoId);
        if (track == null) return NO_SONG_VERSION;

        JSONObject navigation = track.optJSONObject("navigationEndpoint");
        JSONObject endpoint = navigation == null ? null : navigation.optJSONObject("watchEndpoint");
        if (endpoint == null) return NO_SONG_VERSION;

        final String videoType = musicVideoType(endpoint);
        if (AUDIO_TYPE.equals(videoType)) return NO_SONG_VERSION;

        String title = runsText(track.optJSONObject("title"));
        JSONArray byline = runs(track.optJSONObject("longBylineText"));
        String artist = byline.length() == 0 ? "" : byline.optJSONObject(0).optString("text", "");
        String wantedArtist = normalize(artist);
        String wantedTitle = comparableTitle(title, wantedArtist);
        if (wantedTitle.isEmpty() || wantedArtist.isEmpty()) return NO_SONG_VERSION;

        // Labels flip between OMV and UGC. If an album lists a different track with this
        // title and artist it is the right one either way.
        String albumSongId = findInRecentAlbums(videoId, title, wantedTitle, wantedArtist);
        if (!albumSongId.isEmpty()) return albumSongId;

        if (!isOfficialMusicVideo(videoId, videoType)) return NO_SONG_VERSION;

        final int videoLength = parseLength(runsText(track.optJSONObject("lengthText")));

        JSONObject results = requestWebRemix(SEARCH_URL, new JSONObject()
                .put("query", title + " " + artist)
                .put("params", SONGS_FILTER));
        List<JSONObject> rows = new ArrayList<>();
        collectRenderers(results, "musicResponsiveListItemRenderer", rows);

        // Singles only if no album has the song.
        String firstMatch = NO_SONG_VERSION;
        for (JSONObject row : rows) {
            JSONArray columns = row.optJSONArray("flexColumns");
            if (columns == null || columns.length() < 2) continue;

            JSONArray titleRuns = columnRuns(columns, 0);
            JSONArray detailRuns = columnRuns(columns, 1);
            if (titleRuns.length() == 0 || detailRuns.length() == 0) continue;

            JSONObject titleRun = titleRuns.optJSONObject(0);
            JSONObject songNavigation = titleRun.optJSONObject("navigationEndpoint");
            JSONObject songEndpoint = songNavigation == null
                    ? null
                    : songNavigation.optJSONObject("watchEndpoint");
            if (songEndpoint == null || !AUDIO_TYPE.equals(musicVideoType(songEndpoint))) continue;

            String songId = songEndpoint.optString("videoId", "");
            String songTitle = titleRun.optString("text", "");
            if (!isVideoId(songId) || songId.equals(videoId)) continue;
            if (!wantedTitle.equals(comparableTitle(songTitle, wantedArtist))) continue;
            if (!variantWords(title).equals(variantWords(songTitle))) continue;
            if (!hasArtist(detailRuns, wantedArtist)) continue;

            final int songLength = parseLength(
                    detailRuns.optJSONObject(detailRuns.length() - 1).optString("text", ""));
            if (videoLength > 0 && songLength > 0
                    && Math.abs(videoLength - songLength) > MAX_LENGTH_DIFFERENCE_SECONDS) {
                continue;
            }
            if (isFromAlbum(detailRuns, wantedTitle)) return songId;
            if (firstMatch.isEmpty()) firstMatch = songId;
        }

        if (firstMatch.isEmpty()) Logger.printDebug(() -> "song version: no exact match for " + videoId);
        return firstMatch;
    }

    /** /next flips between OMV and UGC for the same video, /player has been consistent so far. */
    private static boolean isOfficialMusicVideo(String videoId, String videoType) throws Exception {
        if (VIDEO_TYPE.equals(videoType)) return true;

        JSONObject details = requestWebRemix(PLAYER_URL, new JSONObject().put("videoId", videoId))
                .optJSONObject("videoDetails");
        return details != null && VIDEO_TYPE.equals(details.optString("musicVideoType"));
    }

    @Nullable
    private static JSONObject findQueueTrack(String videoId) throws Exception {
        JSONObject response = requestWebRemix(NEXT_URL, new JSONObject()
                .put("videoId", videoId)
                .put("isAudioOnly", true));
        List<JSONObject> tracks = new ArrayList<>();
        collectRenderers(response, "playlistPanelVideoRenderer", tracks);
        for (JSONObject track : tracks) {
            if (videoId.equals(track.optString("videoId", ""))) return track;
        }
        return null;
    }

    /**
     * @return Client version from yesterday, old ones can get refused at any time.
     */
    private static String currentWebClientVersion() {
        SimpleDateFormat format = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return "1." + format.format(new Date(System.currentTimeMillis() - DAY_MILLISECONDS)) + ".01.00";
    }

    private static JSONObject requestWebRemix(String url, JSONObject body) throws Exception {
        String country = Requester.getAppLocale().getCountry();
        int status = 0;
        // Fixed version is for devices with a wrong date, those get rejected.
        for (String version : new String[]{currentWebClientVersion(), WEB_CLIENT_VERSION}) {
            byte[] payload = body
                    .put("context", new JSONObject()
                            .put("client", new JSONObject()
                                    .put("clientName", "WEB_REMIX")
                                    .put("clientVersion", version)
                                    .put("hl", "en")
                                    .put("gl", country.isEmpty() ? "US" : country)))
                    .toString()
                    .getBytes(StandardCharsets.UTF_8);

            HttpURLConnection connection = Requester.openConnection(url);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLISECONDS);
            connection.setReadTimeout(READ_TIMEOUT_MILLISECONDS);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("User-Agent", "Mozilla/5.0");
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            try (OutputStream output = connection.getOutputStream()) {
                output.write(payload);
            }

            status = connection.getResponseCode();
            if (status == Requester.HTTP_STATUS_CODE_SUCCESS) {
                return Requester.parseJSONObjectAndDisconnect(connection);
            }
            connection.disconnect();
            if (status != Requester.HTTP_STATUS_CODE_BAD_REQUEST) break;
        }
        throw new IOException("Request failed with status: " + status);
    }

    private static void collectRenderers(Object node, String name, List<JSONObject> out) {
        if (node instanceof JSONObject object) {
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object value = object.opt(key);
                if (name.equals(key) && value instanceof JSONObject renderer) out.add(renderer);
                collectRenderers(value, name, out);
            }
        } else if (node instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                collectRenderers(array.opt(i), name, out);
            }
        }
    }

    private static JSONArray runs(@Nullable JSONObject text) {
        JSONArray runs = text == null ? null : text.optJSONArray("runs");
        return runs == null ? new JSONArray() : runs;
    }

    private static String runsText(@Nullable JSONObject text) {
        JSONArray runs = runs(text);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < runs.length(); i++) {
            builder.append(runs.optJSONObject(i).optString("text", ""));
        }
        return builder.toString();
    }

    private static JSONArray columnRuns(JSONArray columns, int index) {
        JSONObject column = columns.optJSONObject(index);
        JSONObject renderer = column == null
                ? null
                : column.optJSONObject("musicResponsiveListItemFlexColumnRenderer");
        return runs(renderer == null ? null : renderer.optJSONObject("text"));
    }

    private static boolean hasArtist(JSONArray detailRuns, String wantedArtist) {
        for (int i = 0; i < detailRuns.length(); i++) {
            if (wantedArtist.equals(normalize(detailRuns.optJSONObject(i).optString("text", "")))) {
                return true;
            }
        }
        return false;
    }

    /** Videos are often called "Artist - Title", the song just "Title". */
    private static String comparableTitle(String title, String comparableArtist) {
        String text = normalize(title);
        String prefix = comparableArtist + " ";
        return text.startsWith(prefix) ? text.substring(prefix.length()) : text;
    }

    private static Set<String> variantWords(String title) {
        Set<String> words = new HashSet<>();
        Matcher matcher = VARIANT_WORDS.matcher(title.toLowerCase(Locale.ROOT));
        while (matcher.find()) {
            words.add(matcher.group());
        }
        return words;
    }

    /**
     * @return Seconds from "m:ss" or "h:mm:ss", 0 if it can't be read.
     */
    private static int parseLength(String length) {
        int seconds = 0;
        for (String part : length.split(":")) {
            try {
                seconds = seconds * 60 + Integer.parseInt(part.trim());
            } catch (NumberFormatException ex) {
                return 0;
            }
        }
        return seconds;
    }
}
