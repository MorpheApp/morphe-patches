package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.settings.BaseSettings;

@SuppressWarnings("unused")
public final class DisableDRCAudioPatch {
    private static final boolean DISABLE_DRC_AUDIO = BaseSettings.DISABLE_DRC_AUDIO.get();

    /**
     * Checks if DRC audio should be disabled according to user settings.
     */
    public static boolean disableDrcAudio() {
        return DISABLE_DRC_AUDIO;
    }

    /**
     * Override volume normalization feature flag.
     */
    public static boolean disableDrcAudioFeatureFlag(boolean original) {
        return !DISABLE_DRC_AUDIO && original;
    }
}