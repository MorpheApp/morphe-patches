package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.settings.SharedYouTubeSettings.DISABLE_DRC_AUDIO;

@SuppressWarnings("unused")
public final class DisableDRCAudioPatch {

    /**
     * Injection point.
     * Checks if DRC audio should be disabled according to user settings.
     */
    public static boolean disableDrcAudio() {
        return DISABLE_DRC_AUDIO.get();
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
