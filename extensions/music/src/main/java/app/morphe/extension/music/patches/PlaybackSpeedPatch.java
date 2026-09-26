/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.music.patches;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.SeekBarPreference;

@SuppressWarnings("unused")
public class PlaybackSpeedPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface ExoPlayerImpl {
        // Method is added during patching.
        void patch_setPlaybackParameters(float speed, float pitch);
    }

    private static final float DEFAULT_PLAYBACK_SPEED = 1.0f;

    /**
     * Speed and pitch last applied to each player.
     * More than one player can be alive at the same time, such as when crossfading.
     */
    private static final Map<ExoPlayerImpl, float[]> players =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static float getPlaybackSpeed() {
        return SeekBarPreference.clampToRange(Settings.PLAYBACK_SPEED) / 100f;
    }

    /**
     * Injection point.
     */
    public static void initializeExoPlayerImpl(@NonNull ExoPlayerImpl player) {
        try {
            // A new player starts with the default playback parameters.
            players.put(player, new float[]{DEFAULT_PLAYBACK_SPEED, DEFAULT_PLAYBACK_SPEED});
        } catch (Exception ex) {
            Logger.printException(() -> "initializeExoPlayerImpl failure", ex);
        }
    }

    /**
     * Injection point.
     *
     * @param speed Playback speed the app is setting.
     */
    public static float overridePlaybackSpeed(float speed) {
        final float playbackSpeed = getPlaybackSpeed();
        // At the default speed keep what the app sets, such as the podcast playback speed.
        return playbackSpeed == DEFAULT_PLAYBACK_SPEED
                ? speed
                : playbackSpeed;
    }

    /**
     * Injection point.
     *
     * @param speed Playback speed the app is setting.
     * @param pitch Playback pitch the app is setting.
     */
    public static float overridePlaybackPitch(float speed, float pitch) {
        return Settings.PLAYBACK_SPEED_CHANGE_PITCH.get()
                ? overridePlaybackSpeed(speed)
                : pitch;
    }

    /**
     * Injection point.
     * Called on the main thread about every second while a track is playing.
     */
    public static void setVideoTime(long time) {
        try {
            final float speed = getPlaybackSpeed();
            final float pitch = Settings.PLAYBACK_SPEED_CHANGE_PITCH.get()
                    ? speed
                    : DEFAULT_PLAYBACK_SPEED;

            List<Map.Entry<ExoPlayerImpl, float[]>> entries;
            synchronized (players) {
                entries = new ArrayList<>(players.entrySet());
            }

            for (Map.Entry<ExoPlayerImpl, float[]> entry : entries) {
                ExoPlayerImpl player = entry.getKey();
                float[] applied = entry.getValue();
                if (player == null || (applied[0] == speed && applied[1] == pitch)) {
                    continue;
                }

                // Set before applying, so a failure is not retried every second.
                applied[0] = speed;
                applied[1] = pitch;

                Logger.printDebug(() -> "Changing playback speed to: " + speed + " pitch: " + pitch);
                player.patch_setPlaybackParameters(speed, pitch);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoTime failure", ex);
        }
    }
}
