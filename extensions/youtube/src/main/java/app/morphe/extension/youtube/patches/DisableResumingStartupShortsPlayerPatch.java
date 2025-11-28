package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableResumingStartupShortsPlayerPatch {

    /**
     * Injection point.
     */
    public static boolean disableResumingStartupShortsPlayer() {
        return Settings.DISABLE_RESUMING_SHORTS_PLAYER.get();
    }

    /**
     * Injection point.
     */
    public static boolean disableResumingStartupShortsPlayer(boolean original) {
        return original && !Settings.DISABLE_RESUMING_SHORTS_PLAYER.get();
    }
}
