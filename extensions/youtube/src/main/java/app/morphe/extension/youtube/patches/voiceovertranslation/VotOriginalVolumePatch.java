/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import android.media.AudioTrack;

import java.util.concurrent.atomic.AtomicReference;

import app.morphe.extension.shared.Logger;

/**
 * Multiplies the YouTube ExoPlayer audio sink volume by a specific multiplier.
 */
@SuppressWarnings("unused")
public final class VotOriginalVolumePatch {
    private static volatile float currentMultiplier = 1.0f;
    private static volatile float lastBaseVolume = 1.0f;
    private static final AtomicReference<AudioTrack> lastAudioTrackRef = new AtomicReference<>(null);

    private static float clamp01(float value) {
        if (Float.isNaN(value) || value < 0f) return 0f;
        return Math.min(value, 1f);
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
        return clamp01(clamped * currentMultiplier);
    }

    /**
     * Injection point.
     * <p>
     * Invoked on construction of the AudioTrack wrapper so the active AudioTrack can be
     * volume-adjusted directly when the multiplier changes without waiting for ExoPlayer
     * to call {@code setVolume} again.
     */
    public static void setAudioTrack(AudioTrack track) {
        if (track != null) lastAudioTrackRef.set(track);
    }

    /**
     * Sets the ducking multiplier (0..1) and immediately applies it to the active AudioTrack.
     * Called from the main thread.
     */
    public static void setAudioMultiplier(float multiplier) {
        final float clamped = clamp01(multiplier);
        if (clamped == currentMultiplier) return;
        currentMultiplier = clamped;
        applyToActiveTrack();
    }

    /**
     * Resets the multiplier to 1.0 (original volume) and applies immediately.
     */
    public static void clearAudioMultiplier() {
        setAudioMultiplier(1.0f);
    }

    /**
     * Bypasses the AudioSink.setVolume skip-if-equal optimization so a multiplier change is
     * audible without waiting for ExoPlayer to push a new volume value through D(F)V.
     */
    private static void applyToActiveTrack() {
        AudioTrack track = lastAudioTrackRef.get();
        if (track == null) return;
        try {
            track.setVolume(clamp01(lastBaseVolume * currentMultiplier));
        } catch (Exception ex) {
            Logger.printDebug(() -> "AudioTrack setVolume failed", ex);
        }
    }
}
