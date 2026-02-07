package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.settings.BaseSettings;

@SuppressWarnings("unused")
public final class DrcAudioPatch {

    /**
     * Checks if DRC audio should be disabled according to user settings.
     */
    public static boolean disableDrcAudio() {
        return BaseSettings.DISABLE_DRC_AUDIO.get();
    }

    /**
     * Override volume normalization feature flag.
     */
    public static boolean disableDrcAudioFeatureFlag(boolean original) {
        return !BaseSettings.DISABLE_DRC_AUDIO.get() && original;
    }
}