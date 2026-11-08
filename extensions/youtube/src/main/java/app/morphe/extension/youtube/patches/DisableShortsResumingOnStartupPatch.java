package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableShortsResumingOnStartupPatch {

    /**
     * Injection point.
     */
    public static boolean disableShortsResumingOnStartup(boolean original) {
        return Logger.traceOverride("DisableShortsResumingOnStartupPatch.disableShortsResumingOnStartup", original,
                original && !Settings.DISABLE_SHORTS_RESUMING_ON_STARTUP.get());
    }
}
