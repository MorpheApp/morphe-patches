/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.media.AudioTrack;

import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

/**
 * Multiplies the YouTube ExoPlayer audio sink volume.
 * <p>
 * Ducking and muting are kept as separate channels so the features using them
 * never overwrite each other.
 */
@SuppressWarnings("unused")
public final class PlayerVolumePatch {
    // Ducking toggles constantly while TTS speaks, while mute can stay on for an
    // entire video and is not worth polling for as often.
    private static final long DUCK_ENFORCE_INTERVAL_MS = 50;
    private static final long MUTE_ENFORCE_INTERVAL_MS = 500;

    private static volatile float duckMultiplier = 1.0f;
    private static volatile boolean muted;
    private static volatile float lastBaseVolume = 1.0f;
    private static volatile boolean enforceScheduled;
    private static final AtomicReference<AudioTrack> lastAudioTrackRef = new AtomicReference<>(null);

    private static float clamp01(float value) {
        if (Float.isNaN(value) || value < 0f) return 0f;
        return Math.min(value, 1f);
    }

    private static float effectiveMultiplier() {
        return muted ? 0f : duckMultiplier;
    }

    /**
     * Injection point.
     * <p>
     * Invoked on entry of the AudioSink {@code setVolume(F)V} interface method that ExoPlayer
     * calls before writing volume to AudioTrack. Runs on the ExoPlayer audio thread.
     */
    public static float getAudioMultiplier(float volume) {
        float clamped = clamp01(volume);
        lastBaseVolume = clamped;
        return clamp01(clamped * effectiveMultiplier());
    }

    /**
     * Injection point.
     * <p>
     * Invoked on construction of the AudioTrack wrapper so the active AudioTrack can be
     * volume-adjusted directly when the multiplier changes without waiting for ExoPlayer
     * to call {@code setVolume} again.
     */
    public static void setAudioTrack(AudioTrack track) {
        if (track == null) return;
        lastAudioTrackRef.set(track);
        applyMultiplier();
    }

    /**
     * Sets the ducking multiplier (0..1). Called from the main thread.
     */
    public static void setDuckMultiplier(float multiplier) {
        final float clamped = clamp01(multiplier);
        if (clamped == duckMultiplier) return;
        duckMultiplier = clamped;
        applyMultiplier();
    }

    /**
     * Resets the ducking multiplier to 1.0 (original volume). Does not affect muting.
     */
    public static void clearDuckMultiplier() {
        setDuckMultiplier(1.0f);
    }

    /**
     * Mutes the video audio, independent of any active ducking.
     * Called from the main thread.
     */
    public static void setMuted(boolean mute) {
        if (mute == muted) return;
        muted = mute;
        applyMultiplier();
    }

    public static boolean isMuted() {
        return muted;
    }

    private static void applyMultiplier() {
        applyToActiveTrack();
        if (effectiveMultiplier() != 1.0f) scheduleEnforce();
    }

    private static void scheduleEnforce() {
        if (enforceScheduled) return;
        enforceScheduled = true;
        Utils.runOnMainThreadDelayed(PlayerVolumePatch::enforceTick, enforceIntervalMs());
    }

    private static long enforceIntervalMs() {
        return duckMultiplier == 1.0f
                ? MUTE_ENFORCE_INTERVAL_MS
                : DUCK_ENFORCE_INTERVAL_MS;
    }

    private static void enforceTick() {
        enforceScheduled = false;
        // Any later change restarts the loop.
        if (effectiveMultiplier() == 1.0f) return;
        applyToActiveTrack();
        scheduleEnforce();
    }

    private static void applyToActiveTrack() {
        AudioTrack track = lastAudioTrackRef.get();
        if (track == null) return;
        try {
            track.setVolume(clamp01(lastBaseVolume * effectiveMultiplier()));
        } catch (Exception ex) {
            Logger.printDebug(() -> "AudioTrack setVolume failed", ex);
        }
    }
}
