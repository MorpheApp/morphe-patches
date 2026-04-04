package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class LiveDvrPatch {

    /**
     * Injection point.
     */
    public static boolean enableLiveDvr(boolean original) {
        return Settings.FORCE_LIVE_DVR.get() || original;
    }
}
