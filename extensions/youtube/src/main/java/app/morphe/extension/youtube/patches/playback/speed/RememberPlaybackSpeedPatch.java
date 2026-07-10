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

    private static long lastTimeSpeedChanged;

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        Logger.printDebug(() -> "newVideoStarted");
        newVideoStarted = true;
    }

    /**
     * Injection point.
     * Called when user selects a playback speed.
     *
     * @param playbackSpeed The playback speed the user selected
     */
    public static void userSelectedPlaybackSpeed(float playbackSpeed) {
        try {
            if (Settings.REMEMBER_PLAYBACK_SPEED_LAST_SELECTED.get()) {
                // With the 0.05x menu, if the speed is set by a patch to higher than 2.0x
                // then the menu will allow increasing without bounds but the max speed is
                // still capped to 8.0x.
                playbackSpeed = Math.min(playbackSpeed, CustomPlaybackSpeedPatch.PLAYBACK_SPEED_MAXIMUM);

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
                        Utils.showToastShort(str("morphe_remember_playback_speed_toast", (finalPlaybackSpeed + "x")));
                }, TOAST_DELAY_MILLISECONDS);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "userSelectedPlaybackSpeed failure", ex);
        }
    }

    /**
     * Injection point.
     * Overrides the video speed.  Called after video loads,
     * and immediately after the user selects a different playback speed.
     */
    public static float getPlaybackSpeedOverride() {
        if (newVideoStarted) {
            newVideoStarted = false;

            final float defaultSpeed = Settings.PLAYBACK_SPEED_DEFAULT.get();
            if (DISABLE_PLAYBACK_SPEED_MUSIC) {
                if (defaultSpeed == 1.0f) {
                    return 1.0f;
                }

                String videoId = VideoInformation.getVideoId();
                GetMixPlaylistRequest request = GetMixPlaylistRequest.getRequestForVideoId(videoId);
                final boolean isMusic = request != null && Boolean.TRUE.equals(request.getResult());
                if (isMusic) {
                    Logger.printDebug(() -> "Overriding music video speed to 1.0x: " + videoId);
                    return 1.0f;
                }
            }

            if (defaultSpeed > 0) {
                return defaultSpeed;
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