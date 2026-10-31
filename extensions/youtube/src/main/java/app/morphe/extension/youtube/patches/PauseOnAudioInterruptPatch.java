/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/1320
 *
 * Adapted from ReVanced's Pause on audio interrupt patch (PR #6464).
 * https://github.com/PatrickMitchellUI/YT-Revanced-patches/blob/946f1c2498ce118f9a092c2a7c6af377d165f698/extensions/youtube/src/main/java/app/revanced/extension/youtube/patches/PauseOnAudioInterruptPatch.java
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.media.AudioManager;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class PauseOnAudioInterruptPatch {

    private PauseOnAudioInterruptPatch() {
    }

    /** Request callbacks instead of Android's automatic volume ducking. */
    public static boolean overridePauseWhenDucked(boolean original) {
        return original || Settings.PAUSE_ON_AUDIO_INTERRUPT.get();
    }

    /** Use YouTube's existing transient pause/resume handling for ducking events. */
    public static int overrideAudioFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK
                && Settings.PAUSE_ON_AUDIO_INTERRUPT.get()) {
            return AudioManager.AUDIOFOCUS_LOSS_TRANSIENT;
        }
        return focusChange;
    }
}
