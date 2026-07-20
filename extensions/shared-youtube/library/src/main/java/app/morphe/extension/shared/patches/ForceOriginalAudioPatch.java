package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class ForceOriginalAudioPatch {

    private static final String DEFAULT_AUDIO_TRACKS_SUFFIX = ".4";

    private static final boolean FORCE_ORIGINAL_AUDIO = SharedYouTubeSettings.FORCE_ORIGINAL_AUDIO.get();

    /**
     * Injection point.
     */
    public static boolean ignoreDefaultAudioStream(boolean original) {
        if (FORCE_ORIGINAL_AUDIO) {
            return false;
        }
        return original;
    }

    /**
     * Injection point.
     */
    public static boolean isDefaultAudioStream(boolean isDefault, String audioTrackId, String audioTrackDisplayName) {
        if (FORCE_ORIGINAL_AUDIO) {
            try {
                if (audioTrackId.isEmpty()) {
                    // Older app targets can have empty audio tracks and these might be placeholders.
                    // The real audio tracks are called after these.
                    return isDefault;
                }

                Logger.printInfo(() -> "default: " + String.format("%-5s", isDefault) + " id: "
                        + String.format("%-8s", audioTrackId) + " name:" + audioTrackDisplayName);

                final boolean isOriginal = audioTrackId.endsWith(DEFAULT_AUDIO_TRACKS_SUFFIX);
                if (isOriginal) {
                    Logger.printInfo(() -> "Using audio: " + audioTrackId);
                }

                return isOriginal;
            } catch (Exception ex) {
                Logger.printInfo(() -> "isDefaultAudioStream failure", ex);
            }
        }

        return isDefault;
    }
}
