/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public final class DisableDRCAudioPatch {

    /**
     * Injection point.
     * Checks if DRC audio should be disabled according to user settings.
     */
    public static boolean disableDrcAudio() {
        return SharedYouTubeSettings.DISABLE_DRC_AUDIO.get();
    }

    /**
     * Injection point.
     */
    public static boolean disableDrcAudioConfig(boolean original) {
        return overrideConfig(original, false);
    }

    /**
     * Injection point.
     */
    public static boolean enableDrcAudioConfig(boolean original) {
        return overrideConfig(original, true);
    }

    private static boolean overrideConfig(boolean original, boolean override) {
        if (disableDrcAudio()) {
            return override;
        }
        return original;
    }
}
