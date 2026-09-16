/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.swipecontrols.controller

import android.content.Context
import android.media.AudioManager
import android.os.Build
import app.morphe.extension.shared.Logger.printException
import app.morphe.extension.youtube.swipecontrols.SwipeControlsConfigurationProvider
import app.morphe.extension.youtube.swipecontrols.SwipeControlsHostActivity
import app.morphe.extension.youtube.swipecontrols.misc.clamp
import kotlin.properties.Delegates

/**
 * Controller to adjust the device volume level.
 *
 * @param host The host activity of which the volume is adjusted, the main controller instance.
 * @param targetStream The stream that is being controlled. Must be one of the STREAM_* constants in [AudioManager].
 */
class AudioVolumeController(
    private val host: SwipeControlsHostActivity,
    private val targetStream: Int = AudioManager.STREAM_MUSIC,
) {

    /**
     * Audio service connection.
     */
    private lateinit var audioManager: AudioManager
    private var minimumVolumeIndex by Delegates.notNull<Int>()
    private var maximumVolumeIndex by Delegates.notNull<Int>()

    init {
        // bind audio service
        val mgr = host.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (mgr == null) {
            printException { "failed to acquire AUDIO_SERVICE" }
        } else {
            audioManager = mgr
            maximumVolumeIndex = audioManager.getStreamMaxVolume(targetStream)
            minimumVolumeIndex =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    audioManager.getStreamMinVolume(
                        targetStream,
                    )
                } else {
                    0
                }
        }
    }

    /**
     * Whether the audio service was bound and the volume can be adjusted.
     * A controller that is not available must not be used, as the stream bounds are unknown.
     */
    val isAvailable: Boolean
        get() = this::audioManager.isInitialized

    /**
     * The current volume, ranging from 0.0 to [maxVolume].
     */
    var volume: Int
        get() {
            // check if initialized correctly
            if (!this::audioManager.isInitialized) return 0

            // get current volume
            return currentVolumeIndex - minimumVolumeIndex
        }
        set(value) {
            // check if initialized correctly
            if (!this::audioManager.isInitialized) return

            // set new volume
            currentVolumeIndex =
                (value + minimumVolumeIndex).clamp(minimumVolumeIndex, maximumVolumeIndex)
        }

    /**
     * The maximum possible volume.
     */
    val maxVolume: Int
        get() = maximumVolumeIndex - minimumVolumeIndex

    /**
     * The volume change of a single step, in stream index units.
     * Some devices expose a fine-grained volume stream (150 indices instead of 15),
     * where a single index is a tenth of what the device volume UI adjusts by.
     */
    val volumeStepSize: Int
        get() {
            if (!this::audioManager.isInitialized) return 1

            return SwipeControlsConfigurationProvider.volumeStepSize(maxVolume, host.config.volumeStepCount)
        }

    /**
     * The current volume, counted in steps of [volumeStepSize].
     */
    val steppedVolume: Int
        get() = volume / volumeStepSize

    /**
     * The maximum volume, counted in steps of [volumeStepSize].
     */
    val steppedMaxVolume: Int
        get() = maxVolume / volumeStepSize

    /**
     * Adjusts the volume by whole steps, snapping to a multiple of [volumeStepSize]
     * so the levels stay the same ones the device volume UI uses.
     *
     * @param steps The number of steps to adjust by, negative to lower the volume.
     */
    fun adjustVolumeBySteps(steps: Int) {
        val stepSize = volumeStepSize
        if (stepSize == 1) {
            volume += steps
            return
        }

        // Round away from the direction of travel, so a volume set outside the app
        // first snaps onto the step grid instead of jumping a full step.
        val currentStep = if (steps > 0) volume / stepSize else (volume + stepSize - 1) / stepSize
        volume = (currentStep + steps) * stepSize
    }

    /**
     * The current volume index of the target stream.
     */
    private var currentVolumeIndex: Int
        get() = audioManager.getStreamVolume(targetStream)
        set(value) = audioManager.setStreamVolume(targetStream, value, 0)
}
