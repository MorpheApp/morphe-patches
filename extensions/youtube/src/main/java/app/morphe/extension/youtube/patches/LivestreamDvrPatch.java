package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class LivestreamDvrPatch {

    /**
     * Injection point.
     */
    public static boolean enableLivestreamDvr(boolean original) {
        return original || Settings.LIVESTREAM_DVR.get();
    }
}
