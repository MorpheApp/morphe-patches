package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class TranscriptPatch {
    private static final boolean OVERRIDE_TRANSCRIPT_APP_VERSION = Settings.FIX_TRANSCRIPT.get()
            && !SpoofAppVersionPatch.isSpoofingToLessThan("20.05.00");

    /**
     * Injection point.
     */
    public static String getTranscriptAppVersionOverride(String version) {
        return Logger.traceOverride("TranscriptPatch.getTranscriptAppVersionOverride", version,
                OVERRIDE_TRANSCRIPT_APP_VERSION
                        ? "20.05.46"
                        : version);
    }
}
