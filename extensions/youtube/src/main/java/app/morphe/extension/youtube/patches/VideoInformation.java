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

import java.util.Locale;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.patches.components.ContextInterface;
import app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch;
import app.morphe.extension.youtube.shared.Event;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import app.morphe.extension.youtube.shared.VideoState;
import app.morphe.extension.youtube.patches.playback.speed.RememberPlaybackSpeedPatch;
import app.morphe.extension.youtube.settings.Settings;

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
    public interface ExoPlayerImpl {
        // Method is added during patching.
        void patch_setPlaybackParameters(float speed, float pitch);
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

    private static WeakReference<PlaybackController> playerControllerRef = new WeakReference<>(null);
    private static WeakReference<PlaybackController> mdxPlayerDirectorRef = new WeakReference<>(null);
    private static WeakReference<ExoPlayerImpl> exoPlayerImplRef = new WeakReference<>(null);
    private static String channelId = "";
    private static String channelName = "";
    private static String videoId = "";
    private static long videoLength = 0;

    private static volatile String playerResponsePlaylistId = "";
    private static volatile String playerResponseVideoId = "";
    private static volatile boolean playerResponseVideoIdIsShort;
    private static volatile boolean videoIdIsShort;

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
    /**
     * The current playback speed in native panel.
     */
    private static String playbackAudioPitchFormattedString = "";
    /**
     * Fires whenever the playback audio pitch changes.
     */
    public static final Event<Float> onPlaybackAudioPitchChange = new Event<>();

    private static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    private static boolean isPlaybackAudioPitchEnabled() {
        return isPatchIncluded() && Settings.ENABLE_PLAYBACK_AUDIO_PITCH.get();
    }

    private static boolean isPlaybackAudioPitchLinked() {
        return isPlaybackAudioPitchEnabled() && !Settings.PLAYBACK_AUDIO_TIME_STRETCHING.get();
    }

    private static int desiredVideoResolution = AUTOMATIC_VIDEO_QUALITY_VALUE;

    private static boolean qualityNeedsUpdating;

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
    public static void initialize(@NonNull PlaybackController playerController) {
        try {
            Logger.printDebug(() -> "newVideoStarted");

            playerControllerRef = new WeakReference<>(Objects.requireNonNull(playerController));
            videoLength = 0;
            channelId = "";
            channelName = "";
            String videoTitle = "";
            // playbackSpeed = DEFAULT_PLAYBACK_SPEED; // Captured at video start, interferes otherwise.
            playbackSpeedFormattedString = "";
            final float audioPitchOverride = RememberPlaybackSpeedPatch.getPlaybackAudioPitchOverride();
            if (audioPitchOverride > 0.0f && Settings.ENABLE_PLAYBACK_AUDIO_PITCH.get()) {
                playbackAudioPitch = audioPitchOverride;
            }
            playbackAudioPitchFormattedString = "";
            desiredVideoResolution = AUTOMATIC_VIDEO_QUALITY_VALUE;
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
     * @param exoPlayerImpl instance that can set the playback parameters directly.
     */
    public static void initializeExoPlayerImpl(@NonNull ExoPlayerImpl exoPlayerImpl) {
        try {
            exoPlayerImplRef = new WeakReference<>(Objects.requireNonNull(exoPlayerImpl));
        } catch (Exception ex) {
            Logger.printException(() -> "Failed to initialize ExoPlayer", ex);
        }
    }

    /**
     * Injection point.
     */
    public static void setChannelId(String cId) {
        channelId = cId != null ? cId : "";
        Logger.printDebug(() -> "Extracted Channel ID: " + channelId);
        if (!channelId.isEmpty()) {
            onChannelIdChange.invoke(channelId);
        }
    }

    /**
     * Injection point.
     */
    public static void setChannelName(String cName) {
        channelName = cName != null ? cName : "";
        Logger.printDebug(() -> "Extracted Channel Name: " + channelName);
    }

    @NonNull
    public static String getChannelName() {
        return channelName;
    }

    /**
     * Injection point.
     *
     * @param newlyLoadedVideoId ID of the current video
     */
    public static void setVideoId(@NonNull String newlyLoadedVideoId) {
        if (!videoId.equals(newlyLoadedVideoId)) {
            Logger.printDebug(() -> "New video ID: " + newlyLoadedVideoId);
            videoId = newlyLoadedVideoId;
        }
    }

    /**
     * @return If the player parameters are for a Short.
     */
    public static boolean playerParametersAreShort(@NonNull String parameters) {
        return parameters.startsWith(SHORTS_PLAYER_PARAMETERS);
    }

    /**
     * Injection point.
     */
    public static String newPlayerResponseSignature(@NonNull String signature, String videoId, boolean isShortAndOpeningOrPlaying) {
        final boolean isShort = playerParametersAreShort(signature);
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
    public static void setPlayerResponseVideoId(@NonNull String videoId, boolean isShortAndOpeningOrPlaying) {
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
    public static void videoSpeedChanged(float currentVideoSpeed) {
        Logger.printDebug(() -> "Video speed changed: " + currentVideoSpeed);
        // An exception occurs when the playback speed dialog is opened by an overlay button while 'Restore old playback speed menu' is off.
        // Update the formatted string value to avoid the exception.
        updatePlaybackSpeedValue(currentVideoSpeed);
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
            changePlaybackSpeed(playbackSpeed);
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
        if (videoLength != length) {
            Logger.printDebug(() -> "Current video length: " + length);
            videoLength = length;
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
            PlaybackController controller = playerControllerRef.get();
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
            PlaybackController controller = playerControllerRef.get();
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
     * @return The channel ID of the current video.
     */
    @NonNull
    public static String getChannelId() {
        return channelId;
    }

    /**
     * ID of the last video opened. Includes Shorts.
     *
     * @return The ID of the video, or an empty string if no videos have been opened yet.
     *         With 21.15+ this returns an empty string if no video is currently opened.
     */
    @NonNull
    public static String getVideoId() {
        return videoId;
    }

    /**
     * This is the playlistId of the player response, but since Shorts does not support playlists,
     * it is the same as the current playlistId.
     *
     * @return The playlist id of the video.
     */
    @NonNull
    public static String getPlaylistId() {
        return playerResponsePlaylistId;
    }

    /**
     * Differs from {@link #videoId} as this is the video ID for the
     * last player response received, which may not be the last video opened.
     * <p>
     * If Shorts are loading the background, this commonly will be
     * different from the Short that is currently on screen.
     * <p>
     * For most use cases, you should instead use {@link #getVideoId()}.
     *
     * @return The ID of the last video loaded, or an empty string if no videos have been loaded yet.
     */
    @NonNull
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
     * Length of the current video playing.  Includes Shorts.
     *
     * @return The length of the video in milliseconds.
     *         If the video is not yet loaded, or if the video is playing in the background with no video visible,
     *         then this returns zero.
     */
    public static long getVideoLength() {
        return videoLength;
    }

    /**
     * @return The current non casting player time. Value is zero if casting.
     */
    private static long getPlayerVideoTime() {
        PlaybackController controller = playerControllerRef.get();
        return controller != null
                ? controller.patch_getVideoTime()
                : -1;
    }

    /**
     * @return The current casting player time. Value is zero if not casting.
     */
    private static long getMdxVideoTime() {
        PlaybackController controller = mdxPlayerDirectorRef.get();
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

        final long mdxTime = getMdxVideoTime();
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
        return getVideoTime() >= videoLength && videoLength > 0;
    }

    /**
     * Forcefully changes the playback speed of the currently playing video.
     */
    public static void changePlaybackSpeed(float playbackSpeed) {
        Logger.printDebug(() -> "Video speed force changed: " + playbackSpeed);
        Utils.verifyOnMainThread();

        if (currentPlaybackSpeedMenuInterface == null) {
            Logger.printException(() -> "Cannot change speed, menu interface is null");
            return;
        }
        if (playbackSpeed <= 0 || playbackSpeed > PLAYBACK_SPEED_MAXIMUM) {
            Logger.printException(() -> "Invalid playback speed: " + playbackSpeed);
            return;
        }

        currentPlaybackSpeedMenuInterface.patch_setSpeed(playbackSpeed);
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

        ExoPlayerImpl exoPlayerImpl = exoPlayerImplRef.get();
        if (exoPlayerImpl != null) {
            exoPlayerImpl.patch_setPlaybackParameters(speed, pitch);
            Logger.printDebug(() -> "Video playbackParameters changed, speed: " + speed + " pitch: " + pitch);
        } else {
            Logger.printException(() -> "Cannot change speed, menu interface is null");
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
    public static void onNativePlaybackSpeedPanelLoaded(Object context, CharSequence original) {
        if (context instanceof ContextInterface contextInterface) {
            try {
                String identifier = contextInterface.patch_getIdentifier();
                if (identifier == null || !identifier.startsWith("playback_rate_selector_menu_sheet.e")) {
                    return;
                }
                String path = contextInterface.patch_getPathBuilder().toString();
                if (!path.endsWith("|ContainerType|ContainerType|ContainerType|TextType|")) {
                    return;
                }
                String text = Objects.toString(original);
                if (text.length() == 5) {
                    Matcher matcher = PLAYBACK_SPEED_PATTERN.matcher(text);
                    if (matcher.matches()) {
                        setPlaybackSpeedFormattedString(text, Float.parseFloat(text.substring(0, 4)));
                    }
                }
            } catch (Exception ex) {
                Logger.printException(() -> "onNativePlaybackSpeedPanelLoaded failed", ex);
            }
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
            return;
        }

        RememberPlaybackSpeedPatch.userSelectedPlaybackSpeed(playbackSpeed);
        if (isPlaybackAudioPitchLinked()) {
            RememberPlaybackSpeedPatch.userSelectedPlaybackAudioPitch(playbackAudioPitch);
        }
        changePlaybackSpeed(playbackSpeed);
    }

    /**
     * @param resolution The desired video quality resolution to use.
     */
    public static void setDesiredVideoResolution(int resolution) {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "Setting desired video resolution: " + resolution);
        desiredVideoResolution = resolution;
        qualityNeedsUpdating = true;
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
     */
    public static int fixVideoQualityResolution(String name, int quality) {
        try {
            if (!name.startsWith(Integer.toString(quality))) {
                final int suffixIndex = name.indexOf('p');
                if (suffixIndex > 0) {
                    final int fixedQuality = Integer.parseInt(name.substring(0, suffixIndex));
                    Logger.printDebug(() -> "Fixing wrong quality resolution from: " +
                            name + "(" + quality + ") to: " + name + "(" + fixedQuality + ")");
                    return fixedQuality;
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "fixVideoQualityResolution failed", ex);
        }

        return quality;
    }

    /**
     * Injection point.
     *
     * @param qualities Video qualities available, ordered from largest to smallest, with index 0 being the 'automatic' value of -2
     * @param originalQualityIndex quality index to use, as chosen by YouTube
     */
    public static int setVideoQuality(VideoQualityInterface[] qualities, VideoQualityMenuInterface menu, int originalQualityIndex) {
        try {
            Utils.verifyOnMainThread();
            currentQualityMenuInterface = menu;

            final boolean availableQualitiesChanged = (currentQualities == null)
                    || !Arrays.equals(currentQualities, qualities);
            if (availableQualitiesChanged) {
                currentQualities = qualities;
                Logger.printDebug(() -> "VideoQualities: " + Arrays.toString(currentQualities));
            }

            // On extremely slow internet connections the index can initially be -1
            originalQualityIndex = Math.max(0, originalQualityIndex);

            VideoQualityInterface updatedCurrentQuality = qualities[originalQualityIndex];
            if (updatedCurrentQuality.patch_getResolution() != AUTOMATIC_VIDEO_QUALITY_VALUE
                    && (currentQuality == null || currentQuality != updatedCurrentQuality)) {
                setCurrentQuality(updatedCurrentQuality);
            }

            final int preferredQuality = desiredVideoResolution;
            if (preferredQuality == AUTOMATIC_VIDEO_QUALITY_VALUE) {
                return originalQualityIndex; // Nothing to do.
            }

            // After changing videos the qualities can initially be for the prior video.
            // If the qualities have changed and the default is not auto then an update is needed.
            if (qualityNeedsUpdating) {
                qualityNeedsUpdating = false;
            } else if (!availableQualitiesChanged) {
                return originalQualityIndex;
            }

            // Find the highest quality that is equal to or less than the preferred.
            int i = 0;
            final int lastQualityIndex = qualities.length - 1;
            for (VideoQualityInterface quality : qualities) {
                final int qualityResolution = quality.patch_getResolution();
                if ((qualityResolution != AUTOMATIC_VIDEO_QUALITY_VALUE && qualityResolution <= preferredQuality)
                        // Use the lowest video quality if the default is lower than all available.
                        || i == lastQualityIndex) {
                    final boolean qualityNeedsChange = (i != originalQualityIndex);
                    Logger.printDebug(() -> qualityNeedsChange
                            ? "Changing video quality from: " + updatedCurrentQuality + " to: " + quality
                            : "Video is already the preferred quality: " + quality
                    );

                    // On first load of a new regular video, if the video is already the
                    // desired quality then the quality flyout will show 'Auto' (ie: Auto (720p)).
                    //
                    // To prevent user confusion, set the video index even if the
                    // quality is already correct so the UI picker will not display "Auto".
                    //
                    // Only change Shorts quality if the quality actually needs to change,
                    // because the "auto" option is not shown in the flyout
                    // and setting the same quality again can cause the Short to restart.
                    if (qualityNeedsChange || !ShortsPlayerState.isOpen()) {
                        changeQuality(quality);
                        return i;
                    }

                    return originalQualityIndex;
                }
                i++;
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoQuality failure", ex);
        }
        return originalQualityIndex;
    }

    public static boolean isPremiumVideoQuality(@NonNull VideoQualityInterface quality) {
        String qualityName = quality.patch_getQualityName();
        return qualityName != null && qualityName.contains(VIDEO_QUALITY_PREMIUM_NAME);
    }
}
