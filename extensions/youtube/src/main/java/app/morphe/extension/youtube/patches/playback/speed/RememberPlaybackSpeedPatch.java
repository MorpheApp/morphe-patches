/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.speed;

import static app.morphe.extension.shared.StringRef.str;

import java.util.Collections;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.utils.requests.GetMixPlaylistRequest;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class RememberPlaybackSpeedPatch {

    private static final boolean DISABLE_PLAYBACK_SPEED_MUSIC = Settings.DISABLE_PLAYBACK_SPEED_MUSIC.get();

    private static final long TOAST_DELAY_MILLISECONDS = 750;

    private static volatile String lastFetchedVideoId = "";

    private static volatile boolean newVideoStarted;

    private static volatile boolean newAudioStarted = true; // Actually video, just a flag for audio pitch

    private static float reloadPlaybackSpeed = -2.0f;

    private static float reloadPlaybackAudioPitch = -2.0f;

    private static long lastTimeSpeedChanged;

    private static long lastTimePitchChanged;

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        Logger.printDebug(() -> "newVideoStarted");
        newVideoStarted = true;
        newAudioStarted = true;
    }

    public static void preservePlaybackParametersForReload() {
        reloadPlaybackSpeed = VideoInformation.getPlaybackSpeed();
        reloadPlaybackAudioPitch = VideoInformation.getPlaybackAudioPitch();
    }

    /**
     * Injection point.
     * Called when user selects a playback speed.
     *
     * @param playbackSpeed The playback speed the user selected
     */
    public static void userSelectedPlaybackSpeed(float playbackSpeed) {
        try {
            if (!Settings.REMEMBER_PLAYBACK_SPEED_LAST_SELECTED.get()) {
                return;
            }
            // With the 0.05x menu, if the speed is set by a patch to higher than 2.0x
            // then the menu will allow increasing without bounds but the max speed is
            // still capped to 8.0x.
            playbackSpeed = Math.min(playbackSpeed, VideoInformation.PLAYBACK_SPEED_MAXIMUM);

            // Prevent toast spamming if using the 0.05x adjustments.
            // Show exactly one toast after the user stops interacting with the speed menu.
            final long now = System.currentTimeMillis();
            lastTimeSpeedChanged = now;

            final float finalPlaybackSpeed = playbackSpeed;
            Utils.runOnMainThreadDelayed(() -> {
                if (lastTimeSpeedChanged != now) {
                    // The user made additional speed adjustments and this call is outdated.
                    return;
                }

                if (Settings.PLAYBACK_SPEED_DEFAULT.get() == finalPlaybackSpeed) {
                    // User changed to a different speed and immediately changed back.
                    // Or the user is going past 8.0x in the glitched out 0.05x menu.
                    return;
                }
                Settings.PLAYBACK_SPEED_DEFAULT.save(finalPlaybackSpeed);

                if (Settings.REMEMBER_PLAYBACK_SPEED_LAST_SELECTED_TOAST.get())
                    Utils.showToastShort(str("morphe_remember_playback_speed_toast",
                            (String.format(java.util.Locale.US, "%.2fx", finalPlaybackSpeed))));
            }, TOAST_DELAY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "userSelectedPlaybackSpeed failure", ex);
        }
    }

    /**
     * only VideoInformation calls this, when user sets audio pitch.
     *
     * @param playbackAudioPitch The playback speed the user selected
     */
    public static void userSelectedPlaybackAudioPitch(float playbackAudioPitch) {
        try {
            if (!Settings.REMEMBER_PLAYBACK_SPEED_LAST_SELECTED.get()) {
                return;
            }
            // Will already be in range, below line is just a fail-safe.
            playbackAudioPitch = Math.min(playbackAudioPitch, VideoInformation.PLAYBACK_AUDIO_PITCH_MAXIMUM);

            // Prevent toast spamming if using the 0.05x adjustments.
            // Show exactly one toast after the user stops interacting with the pitch menu.
            final long now = System.currentTimeMillis();
            lastTimePitchChanged = now;

            final float finalPlaybackAudioPitch = playbackAudioPitch;
            Utils.runOnMainThreadDelayed(() -> {
                if (lastTimePitchChanged != now) {
                    // The user made additional pitch adjustments and this call is outdated.
                    return;
                }

                if (Settings.PLAYBACK_AUDIO_PITCH_DEFAULT.get() == finalPlaybackAudioPitch) {
                    // User changed to a different pitch and immediately changed back.
                    // Or the user is going past 8.0x in the glitched out 0.05x menu.
                    return;
                }
                Settings.PLAYBACK_AUDIO_PITCH_DEFAULT.save(finalPlaybackAudioPitch);

                // Sharing toast with video speed
                if (Settings.REMEMBER_PLAYBACK_SPEED_LAST_SELECTED_TOAST.get()) {
                    Utils.showToastShort(str("morphe_remember_playback_audio_pitch_toast",
                            (String.format(java.util.Locale.US, "%.2fx", finalPlaybackAudioPitch))));
                }
            }, TOAST_DELAY_MILLISECONDS);
        } catch (Exception ex) {
            Logger.printException(() -> "userSelectedPlaybackAudioPitch failure", ex);
        }
    }

    /**
     * Injection point.
     * Overrides the video speed.  Called after video loads,
     * and immediately after the user selects a different playback speed.
     */
    public static void setDefaultPlaybackSpeed(VideoInformation.PlaybackSpeedMenuInterface menu) {
        if (newVideoStarted) {
            newVideoStarted = false;

            VideoInformation.setPlaybackSpeedMenu(menu);

            final boolean useReloadPlaybackSpeed = reloadPlaybackSpeed > 0;
            float defaultSpeed = useReloadPlaybackSpeed
                    ? reloadPlaybackSpeed
                    : Settings.PLAYBACK_SPEED_DEFAULT.get();
            reloadPlaybackSpeed = -2.0f;
            if (!useReloadPlaybackSpeed && DISABLE_PLAYBACK_SPEED_MUSIC && defaultSpeed != 1.0f) {
                String videoId = VideoInformation.getVideoId();
                GetMixPlaylistRequest request = GetMixPlaylistRequest.getRequestForVideoId(videoId);
                final boolean isMusic = request != null && Boolean.TRUE.equals(request.getResult());
                if (isMusic) {
                    Logger.printDebug(() -> "Overriding music video speed to 1.0x: " + videoId);
                    defaultSpeed = 1.0f;
                }
            }

            if (defaultSpeed > 0) {
                VideoInformation.changePlaybackSpeed(defaultSpeed);
            }
        }
    }

    /**
     * audio pitch state is managed only by VideoInformation
     */
    public static float getPlaybackAudioPitchOverride() {
        if (newAudioStarted) {
            newAudioStarted = false;
            
            final boolean useReloadAudioPitch = reloadPlaybackAudioPitch > 0;
            final float defaultAudioPitch = useReloadAudioPitch
                    ? reloadPlaybackAudioPitch
                    : Settings.PLAYBACK_AUDIO_PITCH_DEFAULT.get();
            reloadPlaybackAudioPitch = -2.0f;
            if (!useReloadAudioPitch && DISABLE_PLAYBACK_SPEED_MUSIC && defaultAudioPitch != 1.0f) {
                String videoId = VideoInformation.getVideoId();

                // duplicate request, needs refactor along with getPlaybackSpeedOverride
                GetMixPlaylistRequest request = GetMixPlaylistRequest.getRequestForVideoId(videoId);
                final boolean isMusic = request != null && Boolean.TRUE.equals(request.getResult());
                if (isMusic) {
                    Logger.printDebug(() -> "Overriding music audio pitch to 1.0x: " + videoId);
                    return 1.0f;
                }
            }

            if (defaultAudioPitch > 0) {
                return defaultAudioPitch;
            }
        }

        return -2.0f;
    }

    public static void preloadMusicVideoFetch(String videoId, boolean isShortAndOpeningOrPlaying) {
        if (DISABLE_PLAYBACK_SPEED_MUSIC && !VideoInformation.lastPlayerResponseIsShort() &&
                !lastFetchedVideoId.equals(videoId) && Settings.PLAYBACK_SPEED_DEFAULT.get() != 1.0f) {
            Logger.printDebug(() -> "Prefetching music video status: " + videoId);
            lastFetchedVideoId = videoId;
            GetMixPlaylistRequest request = GetMixPlaylistRequest.fetchRequestIfNeeded(
                    videoId, Collections.emptyMap());
            // Must block here off the main thread until fetch is finished,
            // because the speed override happens on main thread after playback has started.
            request.getResult();
        }
    }
}
