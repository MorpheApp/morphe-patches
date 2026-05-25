package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.settings.SharedYouTubeSettings.DISABLE_DRC_AUDIO;

@SuppressWarnings("unused")
public final class DisableDRCAudioPatch {
    /**
     * Checks if DRC audio should be disabled according to user settings.
     */
    public static boolean disableDrcAudio() {
        return DISABLE_DRC_AUDIO.get();
    }
    /**
     * Override volume normalization feature flags or optional.
     */
    public static boolean disableDrcAudioConfig(boolean original) {
        return returnNewConfigValue(original, false);
    }
    public static boolean enableDrcAudioConfig(boolean original) {
        return returnNewConfigValue(original, true);
    }
    private static boolean returnNewConfigValue(boolean original, boolean output) {
        if (!disableDrcAudio()) {
            return original;
        }
        return output;
    }
}
