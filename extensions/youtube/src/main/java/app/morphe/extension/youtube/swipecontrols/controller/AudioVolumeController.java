/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.youtube.patches.SoundBoostPatch;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider;
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity;

/**
 * Controller to adjust the device volume level.
 */
public final class AudioVolumeController {

    /**
     * The stream that is being controlled.
     */
    private static final int TARGET_STREAM = AudioManager.STREAM_MUSIC;

    /**
     * The host activity of which the volume is adjusted, the main controller instance.
     */
    private final SwipeControlsHostActivity host;

    /**
     * Audio service connection.
     */
    @Nullable
    private final AudioManager audioManager;
    private final int minimumVolumeIndex;
    private final int maximumVolumeIndex;

    /**
     * @param host The host activity of which the volume is adjusted, the main controller instance.
     */
    public AudioVolumeController(SwipeControlsHostActivity host) {
        this.host = host;

        // bind audio service
        audioManager = (AudioManager) host.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            Logger.printException(() -> "failed to acquire AUDIO_SERVICE");
            minimumVolumeIndex = 0;
            maximumVolumeIndex = 0;
        } else {
            maximumVolumeIndex = audioManager.getStreamMaxVolume(TARGET_STREAM);
            minimumVolumeIndex = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    ? audioManager.getStreamMinVolume(TARGET_STREAM)
                    : 0;
        }
    }

    /**
     * Whether the audio service was bound and the volume can be adjusted.
     * A controller that is not available must not be used, as the stream bounds are unknown.
     */
    public boolean isAvailable() {
        return audioManager != null;
    }

    /**
     * The current volume, ranging from 0.0 to {@link #getMaxVolume()}.
     */
    public int getVolume() {
        // check if initialized correctly
        if (audioManager == null) return 0;

        // get current volume
        return audioManager.getStreamVolume(TARGET_STREAM) - minimumVolumeIndex;
    }

    public void setVolume(int value) {
        // check if initialized correctly
        if (audioManager == null) return;

        // set new volume
        audioManager.setStreamVolume(TARGET_STREAM,
                Utils.clamp(value + minimumVolumeIndex, minimumVolumeIndex, maximumVolumeIndex), 0);
    }

    /**
     * The maximum possible volume.
     */
    public int getMaxVolume() {
        return maximumVolumeIndex - minimumVolumeIndex;
    }

    /**
     * The volume change of a single step, in stream index units.
     * Some devices expose a fine-grained volume stream (150 indices instead of 15),
     * where a single index is a tenth of what the device volume UI adjusts by.
     */
    public int getVolumeStepSize() {
        if (audioManager == null) return 1;

        return SwipeControlsConfigurationProvider.volumeStepSize(getMaxVolume(), host.getConfig().getVolumeStepCount());
    }

    /**
     * The current volume, counted in steps of {@link #getVolumeStepSize()}.
     */
    public int getSteppedVolume() {
        return getVolume() / getVolumeStepSize();
    }

    /**
     * The maximum volume, counted in steps of {@link #getVolumeStepSize()}.
     */
    public int getSteppedMaxVolume() {
        return getMaxVolume() / getVolumeStepSize();
    }

    /**
     * The current boost above the maximum volume, in steps. Always zero if volume boost is off.
     */
    public int getBoostStep() {
        return SoundBoostPatch.getBoostStep();
    }

    /**
     * Adjusts the volume by whole steps, snapping to a multiple of {@link #getVolumeStepSize()}
     * so the levels stay the same ones the device volume UI uses.
     * If volume boost is allowed, steps up at the maximum volume raise the boost,
     * and steps down first remove the boost before lowering the volume.
     *
     * @param steps The number of steps to adjust by, negative to lower the volume.
     */
    public void adjustVolumeBySteps(int steps) {
        if (!isAvailable()) return;

        int remaining = steps;
        while (remaining != 0) {
            final int direction = remaining > 0 ? 1 : -1;
            remaining -= direction;

            if (direction > 0 && getVolume() >= getMaxVolume()) {
                SoundBoostPatch.adjustBoostStep(1);
                continue;
            }
            if (direction < 0 && SoundBoostPatch.adjustBoostStep(-1)) {
                continue;
            }
            adjustSystemVolumeBySteps(direction);
        }
    }

    private void adjustSystemVolumeBySteps(int steps) {
        final int stepSize = getVolumeStepSize();
        if (stepSize == 1) {
            setVolume(getVolume() + steps);
            return;
        }

        // Round away from the direction of travel, so a volume set outside the app
        // first snaps onto the step grid instead of jumping a full step.
        final int volume = getVolume();
        final int currentStep = steps > 0 ? volume / stepSize : (volume + stepSize - 1) / stepSize;
        setVolume((currentStep + steps) * stepSize);
    }
}
