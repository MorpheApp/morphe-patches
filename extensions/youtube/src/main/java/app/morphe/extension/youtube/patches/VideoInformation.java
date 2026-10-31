/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.icu.text.NumberFormat;

import androidx.annotation.GuardedBy;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Collection;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.ExoPlayerInterface;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.youtube.patches.originaltitles.RestoreOriginalTitlesPatch;
import app.morphe.extension.youtube.patches.playback.speed.RememberPlaybackSpeedPatch;
import app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.Event;
import app.morphe.extension.youtube.shared.PlayerType;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.shared.VideoState;

/**
 * Hooking class for the current playing video.
 * @noinspection unused
 */
public final class VideoInformation {

    public interface PlaybackController {
        // Methods are added during patching.
        boolean patch_seekTo(long videoTime);
        void patch_seekToRelative(long videoTimeOffset);
        long patch_getVideoTime();
    }

    /**
     * Interface to use obfuscated methods.
     */
    public interface PlaybackSpeedMenuInterface {
        // Method is added during patching.
        void patch_setSpeed(float speed);
    }

    /**
     * Interface to use obfuscated methods.
     */
    public interface VideoQualityMenuInterface {
        // Method is added during patching.
        void patch_setQuality(VideoQualityInterface quality);
    }

    /**
     * Interface to use obfuscated methods.
     */
    public interface VideoQualityInterface {
        // Methods are added during patching.
        String patch_getQualityName();
        int patch_getResolution();
        /**
         * @return The IDs (itags) of the formats of the quality, empty if the quality has no formats.
         */
        Collection<?> patch_getFormatIds();
    }

    /**
     * Video resolution of the automatic quality option.
     */
    public static final int AUTOMATIC_VIDEO_QUALITY_VALUE = -2;

    /**
     * Video quality names are the same text for all languages.
     * Premium can be "1080p Premium" or "1080p60 Premium"
     */
    private static final String VIDEO_QUALITY_PREMIUM_NAME = "Premium";

    private static final float DEFAULT_PLAYBACK_SPEED = 1.0f;
    /**
     * Pattern of the native playback speed panel.
     */
    private static final Pattern PLAYBACK_SPEED_PATTERN = Pattern.compile("\\d+\\.\\d{2}x");
    /**
     * Maximum playback speed, inclusive.  Custom speeds must be this or less.
     * <p>
     * Going over 8x does not increase the actual playback speed any higher,
     * and the UI selector starts flickering and acting weird.
     * Over 10x and the speeds show up out of order in the UI selector.
     */
    public static final float PLAYBACK_SPEED_MAXIMUM = 8;

    private static final float DEFAULT_PLAYBACK_AUDIO_PITCH = 1.0f;
    
    /**
     * Maximum playback audio pitch, inclusive.
     */
    public static final float PLAYBACK_AUDIO_PITCH_MAXIMUM = 8;

    /**
     * Prefix present in all Short player parameters signature.
     */
    private static final String SHORTS_PLAYER_PARAMETERS = "8AEB";

    private static WeakReference<PlaybackController> mdxPlayerDirectorRef = new WeakReference<>(null);
    private static WeakReference<ExoPlayerInterface> exoPlayerImplRef = new WeakReference<>(null);

    /**
     * Information of the video loaded by one of the two players.
     */
    private static final class PlayerVideo {
        WeakReference<PlaybackController> playerControllerRef = new WeakReference<>(null);
        String channelId = "";
        String channelName = "";
        String videoTitle = "";
        String videoId = "";
        String playlistId = "";
        long videoLength = 0;
        /**
         * If the video ID of the video started by the player controller has been set.
         */
        boolean videoIdSet;
    }

    private static final PlayerVideo regularPlayerVideo = new PlayerVideo();
    private static final PlayerVideo shortsPlayerVideo = new PlayerVideo();

    /**
     * Controller of a video started while the Shorts player is open, until the player
     * it belongs to is known.
     */
    @Nullable
    private static WeakReference<PlaybackController> pendingPlayerControllerRef;

    private static volatile String playerResponsePlaylistId = "";
    private static volatile String playerResponseVideoId = "";
    private static volatile boolean playerResponseVideoIdIsShort;
    private static volatile boolean videoIdIsShort;
    private static volatile boolean liveStream;
    private static volatile String liveStreamVideoId = "";

    /**
     * The current playback speed.
     */
    private static float playbackSpeed = DEFAULT_PLAYBACK_SPEED;
    /**
     * The current playback speed in native panel.
     */
    private static String playbackSpeedFormattedString = "";
    /**
     * Fires whenever the playback speed changes.
     */
    public static final Event<Float> onPlaybackSpeedChange = new Event<>();

    /**
     * The current playback audio pitch
     */
    private static float playbackAudioPitch = DEFAULT_PLAYBACK_AUDIO_PITCH;
    private static boolean playbackAudioPitchNeedsApplying;
    /**
     * The current playback speed in native panel.
     */
    private static String playbackAudioPitchFormattedString = "";
    /**
     * Fires whenever the playback audio pitch changes.
     */
    public static final Event<Float> onPlaybackAudioPitchChange = new Event<>();

    /**
     * The regular player and the Shorts player each keep their own video, so a Short opened while
     * a regular video is minimized does not replace it once the Shorts player is closed.
     * A Short opened in the regular player does not open the Shorts player, and a regular video
     * opened from a Short is maximized above the Shorts player that is still open.
     *
     * @return The video of the Shorts player while it is open and in front, otherwise the video
     *         of the regular player.
     */
    private static PlayerVideo getPlayerVideo() {
        return ShortsPlayerState.isOpen() && !PlayerType.getCurrent().isMaximizedOrFullscreen()
                ? shortsPlayerVideo
                : regularPlayerVideo;
    }

    /**
     * A regular video opened from a Short starts before the regular player is maximized,
     * so a video started while the Shorts player is open is assigned to its player
     * once the information of the video is set.
     *
     * @return The player video the information being set belongs to.
     */
    private static PlayerVideo getLoadingPlayerVideo() {
        PlayerVideo playerVideo = getPlayerVideo();
        if (pendingPlayerControllerRef != null) {
            startPlayerVideo(playerVideo, pendingPlayerControllerRef);
            pendingPlayerControllerRef = null;
        }
        return playerVideo;
    }

    private static void startPlayerVideo(
            PlayerVideo playerVideo,
            WeakReference<PlaybackController> playerControllerRef
    ) {
        playerVideo.playerControllerRef = playerControllerRef;
        playerVideo.videoLength = 0;
        playerVideo.channelId = "";
        playerVideo.channelName = "";
        playerVideo.videoTitle = "";
        playerVideo.playlistId = "";
        playerVideo.videoIdSet = false;
    }

    private static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    private static boolean isPlaybackAudioPitchEnabled() {
        return isPatchIncluded() && Settings.ENABLE_PLAYBACK_AUDIO_PITCH.get();
    }

    private static boolean isPlaybackAudioPitchLinked() {
        return isPlaybackAudioPitchEnabled() && !Settings.PLAYBACK_AUDIO_TIME_STRETCHING.get();
    }

    @GuardedBy("itself")
    private static final NumberFormat speedFormatter = NumberFormat.getNumberInstance();

    static {
        // Use same 2 digit format as built in speed picker,
        speedFormatter.setMinimumFractionDigits(2);
        speedFormatter.setMaximumFractionDigits(2);
    }

    /**
     * The current VideoQualityMenuInterface, set during setPlaybackSpeedMenu.
     */
    @Nullable
    private static PlaybackSpeedMenuInterface currentPlaybackSpeedMenuInterface;

    /**
     * The available qualities of the current video.
     */
    @Nullable
    private static VideoQualityInterface[] currentQualities;

    /**
     * The current quality of the video playing.
     * This is always the actual quality even if Automatic quality is active.
     */
    @Nullable
    private static VideoQualityInterface currentQuality;

    /**
     * The current VideoQualityMenuInterface, set during setVideoQuality.
     */
    @Nullable
    private static VideoQualityMenuInterface currentQualityMenuInterface;

    /**
     * Callback for when the current quality changes.
     */
    public static final Event<VideoQualityInterface> onQualityChange = new Event<>();

    /**
     * Fires whenever a new channel ID is extracted for the current video.
     */
    public static final Event<String> onChannelIdChange = new Event<>();

    /**
     * Fires whenever the video of a player changes, such as when the next Short starts.
     * Not when the same video is loaded again, such as after the app returns from the background.
     */
    public static final Event<String> onVideoIdChange = new Event<>();

    @Nullable
    public static VideoQualityInterface[] getCurrentQualities() {
        return currentQualities;
    }

    @Nullable
    public static VideoQualityInterface getCurrentQuality() {
        return currentQuality;
    }

    /**
     * Injection point.
     *
     * @param playerController player controller object.
     */
    public static void initialize(PlaybackController playerController) {
        try {
            Logger.printDebug(() -> "newVideoStarted");

            WeakReference<PlaybackController> playerControllerRef =
                    new WeakReference<>(Objects.requireNonNull(playerController));
            if (ShortsPlayerState.isOpen()) {
                pendingPlayerControllerRef = playerControllerRef;
            } else {
                pendingPlayerControllerRef = null;
                startPlayerVideo(regularPlayerVideo, playerControllerRef);
            }
            // playbackSpeed = DEFAULT_PLAYBACK_SPEED; // Captured at video start, interferes otherwise.
            playbackSpeedFormattedString = "";
            final float audioPitchOverride = RememberPlaybackSpeedPatch.getPlaybackAudioPitchOverride();
            final PlayerType playerType = PlayerType.getCurrent();
            final boolean noWatchWhilePlayerActive = playerType.isNoneOrHidden() ||
                    playerType == PlayerType.INLINE_MINIMAL;
            final float newPlaybackAudioPitch = audioPitchOverride > 0.0f &&
                    isPlaybackAudioPitchEnabled() && Settings.PLAYBACK_AUDIO_TIME_STRETCHING.get()
                    ? audioPitchOverride
                    : !isPlaybackAudioPitchEnabled() || noWatchWhilePlayerActive
                            ? DEFAULT_PLAYBACK_AUDIO_PITCH
                            : playbackAudioPitch;
            if (playbackAudioPitch != newPlaybackAudioPitch ||
                    (noWatchWhilePlayerActive && newPlaybackAudioPitch != DEFAULT_PLAYBACK_AUDIO_PITCH)) {
                playbackAudioPitch = newPlaybackAudioPitch;
                playbackAudioPitchNeedsApplying = true;
            }
            playbackAudioPitchFormattedString = "";
            currentQualities = null;
            currentQualityMenuInterface = null;
            setCurrentQuality(null);
        } catch (Exception ex) {
            Logger.printException(() -> "initialize failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param mdxPlayerDirector MDX player director object (casting mode).
     */
    public static void initializeMDX(PlaybackController mdxPlayerDirector) {
        try {
            mdxPlayerDirectorRef = new WeakReference<>(Objects.requireNonNull(mdxPlayerDirector));
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to initialize MDX", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param exoPlayerInterface instance that can set the playback parameters directly.
     */
    public static void initializeExoPlayer(ExoPlayerInterface exoPlayerInterface) {
        try {
            exoPlayerImplRef = new WeakReference<>(Objects.requireNonNull(exoPlayerInterface));
        } catch (Exception ex) {
            Logger.printException(() -> "initializeExoPlayer failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param speed Playback speed the app is setting.
     * @return Playback speed to use.
     */
    public static float overridePlaybackSpeed(float speed) {
        // Shorts ignore changePlaybackSpeed(), so their speed is forced here instead.
        return RememberPlaybackSpeedPatch.getPlaybackSpeedInShorts(speed);
    }

    /**
     * Injection point.
     *
     * @param speed Playback speed the app is setting.
     * @param pitch Playback pitch the app is setting.
     * @return Playback pitch to use.
     */
    public static float overridePlaybackPitch(float speed, float pitch) {
        // The app sets its own pitch every time the speed changes, which resets the selected pitch.
        return isPlaybackAudioPitchEnabled()
                ? playbackAudioPitch
                : pitch;
    }

    /**
     * Injection point.
     */
    public static void setChannelId(String cId) {
        String channelId = cId != null ? cId : "";
        getLoadingPlayerVideo().channelId = channelId;
        Logger.printDebug(() -> "Extracted Channel ID: " + channelId);
        if (!channelId.isEmpty()) {
            onChannelIdChange.invoke(channelId);
        }
    }

    /**
     * Injection point.
     */
    public static void setChannelName(String cName) {
        String channelName = cName != null ? cName : "";
        getLoadingPlayerVideo().channelName = channelName;
        Logger.printDebug(() -> "Extracted Channel Name: " + channelName);
    }

    /**
     * @return The channel name of the video of the player in front.
     */
    public static String getChannelName() {
        return getPlayerVideo().channelName;
    }

    /**
     * Injection point.
     */
    public static void setVideoTitle(String title) {
        String videoTitle = title != null ? title : "";
        getLoadingPlayerVideo().videoTitle = videoTitle;
        Logger.printDebug(() -> "Extracted Video Title: " + videoTitle);
    }

    /**
     * @return The title of the video of the player in front,
     *         or the title restored by {@link RestoreOriginalTitlesPatch} if enabled.
     */
    public static String getVideoTitle() {
        PlayerVideo playerVideo = getPlayerVideo();
        return RestoreOriginalTitlesPatch.getTitle(playerVideo.videoId, playerVideo.videoTitle);
    }

    /**
     * The miniplayer only shows the video of the regular player, so the player in front
     * is not used, as the Shorts player can be in front while the miniplayer is shown,
     * such as when going back from the Shorts player.
     *
     * @return The title of the video of the regular player,
     *         or the title restored by {@link RestoreOriginalTitlesPatch} if enabled.
     */
    public static String getMiniplayerVideoName() {
        return RestoreOriginalTitlesPatch.getTitle(regularPlayerVideo.videoId, regularPlayerVideo.videoTitle);
    }

    /**
     * @return The channel name of the video of the regular player.
     * @see #getMiniplayerVideoName()
     */
    public static String getMiniplayerChannelName() {
        return regularPlayerVideo.channelName;
    }

    /**
     * Injection point.
     *
     * @param newlyLoadedVideoId ID of the current video
     */
    public static void setVideoId(String newlyLoadedVideoId) {
        PlayerVideo playerVideo = getLoadingPlayerVideo();
        playerVideo.videoIdSet = true;
        // The playlist ID hook is called after the video ID hook of the same player response,
        // and is skipped for Shorts.
        if (!playerResponseVideoIdIsShort && newlyLoadedVideoId.equals(playerResponseVideoId)) {
            playerVideo.playlistId = playerResponsePlaylistId;
        }
        if (!playerVideo.videoId.equals(newlyLoadedVideoId)) {
            Logger.printDebug(() -> "New video ID: " + newlyLoadedVideoId);
            playerVideo.videoId = newlyLoadedVideoId;
            LocalQueuePatch.onVideoChanged();
            onVideoIdChange.invoke(newlyLoadedVideoId);
        }
    }

    /**
     * Injection point.
     */
    public static String newPlayerResponseSignature(String signature, String videoId, boolean isShortAndOpeningOrPlaying) {
        final boolean isShort = signature.startsWith(SHORTS_PLAYER_PARAMETERS);
        playerResponseVideoIdIsShort = isShort;
        if (!isShort || isShortAndOpeningOrPlaying) {
            if (videoIdIsShort != isShort) {
                videoIdIsShort = isShort;
                Logger.printDebug(() -> "videoIdIsShort: " + isShort);
            }
        }
        return signature; // Return the original value since we are observing and not modifying.
    }

    /**
     * Injection point.  Called off the main thread.
     *
     * @param playlistId The ID of the last playlist loaded.
     */
    public static void setPlayerResponsePlaylistId(@Nullable String playlistId, boolean isShortAndOpeningOrPlaying) {
        if (!playerResponseVideoIdIsShort) {
            if (playlistId == null) {
                playlistId = "";
            }
            if (!playerResponsePlaylistId.equals(playlistId)) {
                String finalPlaylistId = playlistId;
                Logger.printDebug(() -> "New player response playlist ID: " + finalPlaylistId);
                playerResponsePlaylistId = playlistId;
            }
        }
    }

    /**
     * Injection point.  Called off the main thread.
     *
     * @param videoId The ID of the last video loaded.
     */
    public static void setPlayerResponseVideoId(String videoId, boolean isShortAndOpeningOrPlaying) {
        if (!playerResponseVideoId.equals(videoId)) {
            Logger.printDebug(() -> "New player response video ID: " + videoId);
            playerResponseVideoId = videoId;
        }
    }

    /**
     * Records a new playback speed, updates the formatted string, and fires {@link #onPlaybackSpeedChange}.
     *
     * @return true if the speed actually changed.
     */
    private static boolean updatePlaybackSpeedValue(float speed) {
        if (playbackSpeed == speed) {
            return false;
        }

        playbackSpeed = speed;
        Logger.printDebug(() -> "Video speed updated: " + playbackSpeed);
        playbackSpeedFormattedString = formatSpeedStringX(speed);
        Utils.runOnMainThreadNowOrLater(() -> onPlaybackSpeedChange.invoke(speed));
        if (isPlaybackAudioPitchLinked() || (isPatchIncluded() && !isPlaybackAudioPitchEnabled())) {
            updatePlaybackAudioPitchValue(speed);
        }
        return true;
    }

    /**
     * Records a new playback audio pitch, updates the formatted string, and fires {@link #onPlaybackAudioPitchChange}.
     *
     * @return true if the pitch actually changed.
     */
    private static boolean updatePlaybackAudioPitchValue(float pitch) {
        if (!isPlaybackAudioPitchEnabled()) {
            pitch = 1.0f;
        }
        if (playbackAudioPitch == pitch) {
            return false;
        }

        playbackAudioPitch = pitch;
        Logger.printDebug(() -> "Audio pitch updated: " + playbackAudioPitch);
        playbackAudioPitchFormattedString = formatSpeedStringX(pitch);
        final float updatedPitch = pitch;
        Utils.runOnMainThreadNowOrLater(() -> onPlaybackAudioPitchChange.invoke(updatedPitch));
        if (isPlaybackAudioPitchLinked()) {
            updatePlaybackSpeedValue(pitch);
        }
        return true;
    }

    /**
     * Injection point.
     */
    public static void videoSpeedChanged(float videoSpeed) {
        // The Shorts player reports its own speed when a Short loads,
        // but plays at the overridden Shorts speed.
        final float currentVideoSpeed = RememberPlaybackSpeedPatch.getPlaybackSpeedInShorts(videoSpeed);
        Logger.printDebug(() -> "Video speed changed: " + currentVideoSpeed);
        // An exception occurs when the playback speed dialog is opened by an overlay button while 'Restore old playback speed menu' is off.
        // Update the formatted string value to avoid the exception.
        updatePlaybackSpeedValue(currentVideoSpeed);
        if (playbackAudioPitchNeedsApplying && Settings.PLAYBACK_SPEED_DEFAULT.get() <= 0.0f) {
            playbackAudioPitchNeedsApplying = false;
            final float pitch = playbackAudioPitch;
            Utils.runOnMainThreadNowOrLater(() -> setPlaybackParameters(currentVideoSpeed, pitch));
        }
    }

    /**
     * Not injected.
     * Only CustomPlaybackInterface sets audio pitch.
     */
    public static void setAudioPitch(float currentAudioPitch) {
        Logger.printDebug(() -> "Audio pitch set to: " + currentAudioPitch);
        final float previousPlaybackSpeed = playbackSpeed;
        if (!updatePlaybackAudioPitchValue(currentAudioPitch)) {
            return;
        }

        RememberPlaybackSpeedPatch.userSelectedPlaybackAudioPitch(playbackAudioPitch);
        if (!Settings.PLAYBACK_AUDIO_TIME_STRETCHING.get() && previousPlaybackSpeed != playbackSpeed) {
            RememberPlaybackSpeedPatch.userSelectedPlaybackSpeed(playbackSpeed);
            applyUserSelectedPlaybackSpeed(playbackSpeed);
        } else {
            setPlaybackParameters(playbackSpeed, playbackAudioPitch);
        }
    }

    /**
     * Injection point.
     * Called when user selects a playback speed.
     *
     * @param userSelectedPlaybackSpeed The playback speed the user selected
     */
    public static void userSelectedPlaybackSpeed(float userSelectedPlaybackSpeed) {
        Logger.printDebug(() -> "User selected playback speed: " + userSelectedPlaybackSpeed);
        updatePlaybackSpeedValue(userSelectedPlaybackSpeed);
        if (isPlaybackAudioPitchLinked()) {
            RememberPlaybackSpeedPatch.userSelectedPlaybackAudioPitch(userSelectedPlaybackSpeed);
        }

        // An exception occurs when the playback speed dialog is opened by an overlay button while 'Restore old playback speed menu' is off.
        // Update the formatted string value to avoid the exception.
        playbackSpeedFormattedString = formatSpeedStringX(userSelectedPlaybackSpeed);
    }

    /**
     * @param speed The playback speed value to format using minimum of 2 fractional digits.
     * @return A string representation of the speed with 'x' (e.g. "1.25x" or "1.00x").
     */
    public static String formatSpeedStringX(float speed) {
        return formatSpeedStringX(speed, 2);
    }

    /**
     * @param speed The playback speed value to format
     * @param minFractionalDigits The minimum number of fractional digits to use.
     * @return A string representation of the speed with 'x' (e.g. "1.25x" or "1.00x").
     */
    public static String formatSpeedStringX(float speed, int minFractionalDigits) {
        return formatSpeedStringX(speed, minFractionalDigits, true);
    }

    /**
     * @param speed The playback speed value to format.
     * @param minFractionalDigits The minimum number of fractional digits to use.
     * @param includeX If 'x' character is appended to the speed.
     */
    public static String formatSpeedStringX(float speed, int minFractionalDigits, boolean includeX) {
        synchronized (speedFormatter) {
            speedFormatter.setMinimumFractionDigits(minFractionalDigits);

            String speedFormatted = speedFormatter.format(speed);
            return includeX
                    ? speedFormatted + 'x'
                    : speedFormatted;
        }
    }

    private static final double LOG_2 = Math.log(2.0);
    private static final double SEMITONES_PER_OCTAVE = 12.0;

    /**
     * @param pitch The playback audio pitch value to format.
     * @return pitch formatted as "X.XXx (Nst)" with signed one-decimal semitone offset.
     */
    public static String formatAudioPitchStringX(float pitch) {
        if (!(pitch > 0f)) {
            throw new IllegalArgumentException("pitch must be a positive non infinite value: " + pitch);
        }

        final double semitones = SEMITONES_PER_OCTAVE * (Math.log(pitch) / LOG_2);
        String formattedSemitones = String.format(Locale.US, "%.1f", semitones);
        if (formattedSemitones.equals("-0.0")) {
            formattedSemitones = "0.0";
        }
        // Decide the sign from the *formatted* string, not the raw value,
        // so values that round to zero never get a stray "+".
        String signedSemitones = formattedSemitones.startsWith("-") || formattedSemitones.equals("0.0")
                ? formattedSemitones
                : "+" + formattedSemitones;

        String speed = formatSpeedStringX(pitch);
        return Utils.isRightToLeftLocale()
                ? String.format(Locale.US, "(%sst) %s", signedSemitones, speed)
                : String.format(Locale.US, "%s (%sst)", speed, signedSemitones);
    }

    /**
     * Injection point.
     *
     * @param length The length of the video in milliseconds.
     */
    public static void setVideoLength(final long length) {
        // The length is reset when a video starts, and the seekbar reports zero
        // before the Shorts player opens.
        if (length == 0) {
            return;
        }

        // The seekbar also reports the length of the Short shown while the regular player
        // is hidden, even right after the Shorts player is closed.
        PlayerVideo playerVideo = PlayerType.getCurrent().isNoneOrHidden()
                ? shortsPlayerVideo
                : getPlayerVideo();
        if (playerVideo.videoLength != length) {
            Logger.printDebug(() -> "Current video length: " + length);
            playerVideo.videoLength = length;
        }
    }

    /**
     * Seek on the current video.
     * Does not function for playback of Shorts.
     * <p>
     * Caution: If called from a videoTimeHook() callback,
     * this will cause a recursive call into the same videoTimeHook() callback.
     *
     * @param seekTime The seekTime to seek the video to.
     * @return true if the seek was successful.
     */
    public static boolean seekTo(final long seekTime) {
        Utils.verifyOnMainThread();
        try {
            final long videoTime = getVideoTime();
            final long videoLength = getVideoLength();

            // Prevent issues such as play/ pause button or autoplay not working.
            final long adjustedSeekTime = Math.min(seekTime, videoLength - 250);
            if (videoTime <= seekTime && videoTime >= adjustedSeekTime) {
                // Both the current video time and the seekTo are in the last 250ms of the video.
                // Ignore this seek call, otherwise if a video ends with multiple closely timed segments
                // then seeking here can create an infinite loop of skip attempts.
                Logger.printDebug(() -> "Ignoring seekTo call as video playback is almost finished. "
                        + " videoTime: " + videoTime + " videoLength: " + videoLength + " seekTo: " + seekTime);
                return false;
            }

            Logger.printDebug(() -> "Seeking to: " + adjustedSeekTime);

            // Try regular playback controller first, and it will not succeed if casting.
            PlaybackController controller = getPlayerVideo().playerControllerRef.get();
            if (controller == null) {
                Logger.printDebug(() -> "Cannot seekTo because player controller is null");
            } else {
                if (controller.patch_seekTo(adjustedSeekTime)) {
                    VoiceOverTranslationPatch.onVideoSeeked();
                    return true;
                }
                Logger.printDebug(() -> "seekTo did not succeeded. Trying MXD.");
                // Else the video is loading or changing videos, or video is casting to a different device.
            }

            // Try calling the seekTo method of the MDX player director (called when casting).
            // The difference has to be a different second mark in order to avoid infinite skip loops
            // as the Lounge API only supports whole seconds.
            if (adjustedSeekTime / 1000 == videoTime / 1000) {
                Logger.printDebug(() -> "Skipping seekTo for MDX because seek time is too small"
                        + "(" + (adjustedSeekTime - videoTime) + "ms)");
                return false;
            }

            controller = mdxPlayerDirectorRef.get();
            if (controller == null) {
                Logger.printDebug(() -> "Cannot seekTo MXD because player controller is null");
                return false;
            }

            final boolean mdxSeekSuccessful = controller.patch_seekTo(adjustedSeekTime);
            if (mdxSeekSuccessful) VoiceOverTranslationPatch.onVideoSeeked();
            return mdxSeekSuccessful;
        } catch (Exception ex) {
            Logger.printException(() -> "seekTo failure", ex);
            return false;
        }
    }

    /**
     * Seeks a relative amount.  Should always be used over {@link #seekTo(long)}
     * when the desired seek time is an offset of the current time.
     */
    public static void seekToRelative(long seekTime) {
        Utils.verifyOnMainThread();
        try {
            Logger.printDebug(() -> "Seeking relative to: " + seekTime);

            // 19.39+ does not have a boolean return type for relative seek.
            // But can call both methods, and it works correctly for both situations.
            PlaybackController controller = getPlayerVideo().playerControllerRef.get();
            if (controller == null) {
                Logger.printDebug(() -> "Cannot seek relative as player controller is null");
            } else {
                controller.patch_seekToRelative(seekTime);
            }

            // Adjust the fine adjustment function so it's at least 1 second before/after.
            // Otherwise, the fine adjustment will do nothing when casting.
            final long adjustedSeekTime;
            if (seekTime < 0) {
                adjustedSeekTime = Math.min(seekTime, -1000);
            } else {
                adjustedSeekTime = Math.max(seekTime, 1000);
            }

            controller = mdxPlayerDirectorRef.get();
            if (controller == null) {
                Logger.printDebug(() -> "Cannot seek relative as MXD player controller is null");
            } else {
                controller.patch_seekToRelative(adjustedSeekTime);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "seekToRelative failure", ex);
        }
    }

    /**
     * @return The channel ID of the video of the player in front.
     */
    public static String getChannelId() {
        return getPlayerVideo().channelId;
    }

    /**
     * ID of the video of the player in front. Includes Shorts.
     *
     * @return The ID of the video, or an empty string if no videos have been opened yet.
     *         With 21.15+ this returns an empty string if no video is currently opened.
     */
    public static String getVideoId() {
        return getPlayerVideo().videoId;
    }

    /**
     * The player response video ID changes with any video loaded in the background,
     * such as the feed videos loaded while scrolling with the miniplayer open,
     * so it is used only while the video of the player in front has not started yet.
     *
     * @return The ID of the video of the player in front, or the ID of the last
     *         player response if that video is still starting.
     */
    public static String getStartingOrPlayingVideoId() {
        PlayerVideo playerVideo = getPlayerVideo();
        return playerVideo.videoIdSet || playerResponseVideoIdIsShort
                ? playerVideo.videoId // Player response may be a Short in the feed.
                : playerResponseVideoId;
    }

    /**
     * Like {@link #getStartingOrPlayingVideoId()}, the playlist ID of the player response is used
     * only while the video of the player in front has not started yet.
     *
     * @return The playlist ID of the video of the player in front, or the playlist ID of the last
     *         player response if that video is still starting.
     */
    public static String getPlaylistId() {
        PlayerVideo playerVideo = getPlayerVideo();
        return playerVideo.videoIdSet || playerResponseVideoIdIsShort
                ? playerVideo.playlistId
                : playerResponsePlaylistId;
    }

    /**
     * Differs from {@link #getVideoId()} as this is the video ID for the
     * last player response received, which may not be the last video opened.
     * <p>
     * If Shorts are loading the background, this commonly will be
     * different from the Short that is currently on screen.
     * <p>
     * For most use cases, you should instead use {@link #getVideoId()}.
     *
     * @return The ID of the last video loaded, or an empty string if no videos have been loaded yet.
     */
    public static String getPlayerResponseVideoId() {
        return playerResponseVideoId;
    }

    /**
     * @return If the last player response video ID was a Short.
     * Include Shorts shelf items appearing in the feed that are not opened.
     * @see #lastVideoIdIsShort()
     */
    public static boolean lastPlayerResponseIsShort() {
        return playerResponseVideoIdIsShort;
    }

    /**
     * @return If the last player response video ID _that was opened_ was a Short.
     */
    public static boolean lastVideoIdIsShort() {
        return videoIdIsShort;
    }

    /**
     * Injection point.
     * Called when the player response of the current video is read.
     *
     * @param isLive If the current video is an ongoing live stream.
     */
    public static void setLiveStream(boolean isLive) {
        // Remember which video the value is for, so a live video does not mark the following
        // videos as live. The player response video ID is used because the video ID is
        // not yet updated for the new video at this point.
        final boolean wasLive = isLiveStream();
        liveStreamVideoId = playerResponseVideoId;
        liveStream = isLive;

        final boolean nowLive = isLiveStream();
        if (wasLive == nowLive) {
            return;
        }

        Logger.printDebug(() -> "isLiveStream: " + nowLive);
        RememberPlaybackSpeedPatch.onLiveStreamChanged(nowLive);
        SkipLiveShortsPatch.onLiveStreamChanged(nowLive);
    }

    /**
     * @return If the video that is currently loading is an ongoing live stream.
     */
    public static boolean isLiveStream() {
        return liveStream && liveStreamVideoId.equals(playerResponseVideoId);
    }

    /**
     * @return The current playback speed.
     */
    public static float getPlaybackSpeed() {
        return playbackSpeed;
    }

    /**
     * @return The current playback audio pitch.
     */
    public static float getPlaybackAudioPitch() {
        return playbackAudioPitch;
    }

    /**
     * Length of the video of the player in front.  Includes Shorts.
     *
     * @return The length of the video in milliseconds.
     *         If the video is not yet loaded, or if the video is playing in the background with no video visible,
     *         then this returns zero.
     */
    public static long getVideoLength() {
        return getPlayerVideo().videoLength;
    }

    /**
     * @return The current non casting player time. Value is zero if casting.
     */
    private static long getPlayerVideoTime() {
        PlaybackController controller = getPlayerVideo().playerControllerRef.get();
        return controller != null
                ? controller.patch_getVideoTime()
                : -1;
    }

    /**
     * Playback time of the current video playing. Includes Shorts.
     * If casting then the time is always rounded down to the nearest whole second.
     *
     * @return The time of the video in milliseconds, or -1 if not the player is not available.
     */
    public static long getVideoTime() {
        final long playerTime = getPlayerVideoTime();
        // If time is zero, then playback may be casting.
        if (playerTime > 0) {
            return playerTime;
        }

        // The casting player time.
        PlaybackController controller = mdxPlayerDirectorRef.get();
        final long mdxTime = controller != null
                ? controller.patch_getVideoTime()
                : -1;
        return mdxTime >= 0
                ? mdxTime
                : playerTime;
    }

    /**
     * If video is playing in the background with no video visible,
     * this always returns false (even if the video is actually at the end).
     * <p>
     * This is equivalent to checking for {@link VideoState#ENDED},
     * but can give a more up-to-date result for code calling from some hooks.
     *
     * @return If the playback is at the end of the video.
     * @see VideoState
     */
    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public static boolean isAtEndOfVideo() {
        final long videoLength = getVideoLength();
        return getVideoTime() >= videoLength && videoLength > 0;
    }

    /**
     * Forcefully changes the playback speed of the currently playing video.
     */
    public static void changePlaybackSpeed(float playbackSpeed) {
        Logger.printDebug(() -> "Video speed force changed: " + playbackSpeed);
        Utils.verifyOnMainThread();

        if (currentPlaybackSpeedMenuInterface == null) {
            Logger.LogMessage logMessage = () -> "Debug: Cannot change playback speed, menu interface is null";
            if (Settings.DEBUG.get()) {
                Logger.printException(logMessage);
            } else {
                Logger.printDebug(logMessage);
            }
            return;
        }
        if (playbackSpeed <= 0 || playbackSpeed > PLAYBACK_SPEED_MAXIMUM) {
            Logger.printException(() -> "Invalid playback speed: " + playbackSpeed);
            return;
        }

        final boolean playbackSpeedChanged = updatePlaybackSpeedValue(playbackSpeed);
        if (isPlaybackAudioPitchLinked()) {
            updatePlaybackAudioPitchValue(playbackSpeed);
        }
        final float playbackAudioPitchToApply = playbackAudioPitch;
        currentPlaybackSpeedMenuInterface.patch_setSpeed(playbackSpeed);
        if (playbackAudioPitchNeedsApplying && !playbackSpeedChanged) {
            setPlaybackParameters(playbackSpeed, playbackAudioPitchToApply);
        }
        playbackAudioPitchNeedsApplying = false;
    }

    /**
     * Forcefully changes the playback parameters (speed and pitch) of the current ExoPlayerImpl instance.
     * Avoid using this for just video speed changes, YT won't update in other places.
     */
    public static void setPlaybackParameters(float speed, float pitch) {
        Utils.verifyOnMainThread();
        
        if (speed <= 0 || speed > PLAYBACK_SPEED_MAXIMUM) {
            Logger.printException(() -> "Invalid playback speed: " + speed);
            return;
        }
        if (pitch <= 0 || pitch > PLAYBACK_AUDIO_PITCH_MAXIMUM) {
            Logger.printException(() -> "Invalid playback pitch: " + pitch);
            return;
        }

        ExoPlayerInterface exoPlayerInterface = exoPlayerImplRef.get();
        if (exoPlayerInterface != null) {
            exoPlayerInterface.patch_setPlaybackParameters(speed, pitch);
            Logger.printDebug(() -> "Video playbackParameters changed, speed: " + speed + " pitch: " + pitch);
        } else {
            Logger.LogMessage logMessage = () -> "Debug: Cannot change speed parameters, menu interface is null";
            if (Settings.DEBUG.get()) {
                Logger.printException(logMessage);
            } else {
                Logger.printDebug(logMessage);
            }
        }
    }

    /**
     * Injection point.
     */
    public static void setPlaybackSpeedMenu(PlaybackSpeedMenuInterface menu) {
        currentPlaybackSpeedMenuInterface = menu;
    }

    /**
     * Injection point.
     */
    public static void onNativePlaybackSpeedPanelLoaded(ContextInterface context, CharSequence original) {
        try {
            String identifier = context.patch_getIdentifier();
            if (identifier == null || !identifier.startsWith("playback_rate_selector_menu_sheet.e")) {
                return;
            }
            StringBuilder path = context.patch_getPathBuilder();
            if (!Utils.endsWith(path, "|ContainerType|ContainerType|ContainerType|TextType|")) {
                return;
            }
            if (original.length() == 5) {
                Matcher matcher = PLAYBACK_SPEED_PATTERN.matcher(original);
                if (matcher.matches()) {
                    String text = original.toString();
                    setPlaybackSpeedFormattedString(text, Float.parseFloat(text.substring(0, 4)));
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onNativePlaybackSpeedPanelLoaded failed", ex);
        }
    }

    /**
     * Called when the native playback speed panel is opened.
     * @param newlyLoadedPlaybackSpeedFormattedString The current formatted playback speed string.
     */
    private static void setPlaybackSpeedFormattedString(
            String newlyLoadedPlaybackSpeedFormattedString,
            float newlyLoadedPlaybackSpeed
    ) {
        if (playbackSpeedFormattedString.isEmpty()) {
            // The user has not yet changed the playback speed in the native playback speed panel.
            // Save the string to the field (playbackSpeedFormattedString) and do nothing.
            playbackSpeedFormattedString = newlyLoadedPlaybackSpeedFormattedString;
            return;
        }

        // Playback speed changed in native playback speed panel.
        if (!playbackSpeedFormattedString.equals(newlyLoadedPlaybackSpeedFormattedString)) {
            playbackSpeedFormattedString = newlyLoadedPlaybackSpeedFormattedString;

            final float previousPlaybackAudioPitch = playbackAudioPitch;
            VideoInformation.userSelectedPlaybackSpeed(newlyLoadedPlaybackSpeed);
            if (previousPlaybackAudioPitch != playbackAudioPitch) {
                final float pitch = playbackAudioPitch;
                Utils.runOnMainThreadNowOrLater(() ->
                        setPlaybackParameters(newlyLoadedPlaybackSpeed, pitch));
            }

            // Rest of the implementation added by patch.
            // RememberPlaybackSpeedPatch.userSelectedPlaybackSpeed(newlyLoadedPlaybackSpeed);
            // PlaybackSpeedDialogButton.videoSpeedChanged(newlyLoadedPlaybackSpeed);
        }
    }

    /**
     * Injection point.
     * CustomPlaybackInterface sets video speed through this.
     * @param newlyLoadedPlaybackSpeed The current playback speed.
     */
    public static void setPlaybackSpeed(float newlyLoadedPlaybackSpeed) {
        if (!updatePlaybackSpeedValue(newlyLoadedPlaybackSpeed)) {
            // The Shorts player can play at another speed than the one last recorded,
            // such as when it reopens, so the selected speed is always applied.
            if (ShortsPlayerState.isOpen()) {
                applyUserSelectedPlaybackSpeed(newlyLoadedPlaybackSpeed);
            }
            return;
        }

        RememberPlaybackSpeedPatch.userSelectedPlaybackSpeed(playbackSpeed);
        if (isPlaybackAudioPitchLinked()) {
            RememberPlaybackSpeedPatch.userSelectedPlaybackAudioPitch(playbackAudioPitch);
        }
        applyUserSelectedPlaybackSpeed(playbackSpeed);
    }

    /**
     * Sets the speed of the Shorts player, which has no playback speed menu interface.
     */
    public static void setShortsPlaybackSpeed(float speed) {
        updatePlaybackSpeedValue(speed);
        setPlaybackParameters(speed, playbackAudioPitch);
    }

    /**
     * Applies a speed the user selected in a Morphe speed menu.
     * The Shorts player ignores {@link #changePlaybackSpeed(float)}, so the speed
     * is set on the player directly and kept for the following Shorts.
     */
    private static void applyUserSelectedPlaybackSpeed(float speed) {
        if (ShortsPlayerState.isOpen()) {
            RememberPlaybackSpeedPatch.setShortsSelectedPlaybackSpeed(speed);
            setPlaybackParameters(speed, playbackAudioPitch);
            return;
        }
        changePlaybackSpeed(speed);
    }

    private static void setCurrentQuality(@Nullable VideoQualityInterface quality) {
        Utils.verifyOnMainThread();
        if (currentQuality != quality) {
            Logger.printDebug(() -> "Current quality changed to: " + quality);
            currentQuality = quality;
            onQualityChange.invoke(quality);
        }
    }

    /**
     * Forcefully changes the video quality of the currently playing video.
     */
    public static void changeQuality(VideoQualityInterface quality) {
        Utils.verifyOnMainThread();

        if (currentQualityMenuInterface == null) {
            Logger.printException(() -> "Cannot change quality, menu interface is null");
            return;
        }
        currentQualityMenuInterface.patch_setQuality(quality);
    }

    /**
     * Injection point. Fixes bad data used by YouTube.
     * Issue can be reproduced by selecting 480p quality on any Short,
     * and occasionally with random regular videos.
     * <p>
     * Only the resolution returned by {@link VideoQualityInterface#patch_getResolution()} is fixed.
     * The quality object keeps the YouTube resolution, because the player and the SABR requests
     * use it to match the stream formats. With a fixed resolution in the quality object,
     * some VP9 streams restart in a loop when the quality is set.
     */
    public static int fixVideoQualityResolution(@Nullable String name, int quality) {
        try {
            if (name != null && quality != AUTOMATIC_VIDEO_QUALITY_VALUE
                    && !name.startsWith(Integer.toString(quality))) {
                final int suffixIndex = name.indexOf('p');
                if (suffixIndex > 0) {
                    return Integer.parseInt(name.substring(0, suffixIndex));
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "fixVideoQualityResolution failed", ex);
        }

        return quality;
    }

    /**
     * Injection point.
     * <p>
     * The default quality is used as the initial quality of the video, so the quality
     * chosen by YouTube is always used and this only keeps the current qualities.
     *
     * @param qualities Video qualities available, ordered from largest to smallest, with index 0 being the 'automatic' value of -2
     * @param originalQualityIndex quality index to use, as chosen by YouTube
     */
    public static int setVideoQuality(VideoQualityInterface[] qualities, VideoQualityMenuInterface menu, int originalQualityIndex) {
        try {
            Utils.verifyOnMainThread();
            currentQualityMenuInterface = menu;

            if (currentQualities == null || !Arrays.equals(currentQualities, qualities)) {
                currentQualities = qualities;
                Logger.printDebug(() -> "VideoQualities: " + Arrays.toString(currentQualities));
            }

            // On extremely slow internet connections the index can initially be -1
            VideoQualityInterface updatedCurrentQuality = qualities[Math.max(0, originalQualityIndex)];
            if (updatedCurrentQuality.patch_getResolution() != AUTOMATIC_VIDEO_QUALITY_VALUE) {
                setCurrentQuality(updatedCurrentQuality);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoQuality failure", ex);
        }
        return originalQualityIndex;
    }

    public static boolean isPremiumVideoQuality(VideoQualityInterface quality) {
        String qualityName = quality.patch_getQualityName();
        return qualityName != null && qualityName.contains(VIDEO_QUALITY_PREMIUM_NAME);
    }
}
