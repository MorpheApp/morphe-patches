package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class LivestreamDvrPatch {

    private static final int SEVEN_DAYS_IN_SECONDS = 7 * 24 * 60 * 60;

    /**
     * Injection point.
     */
    public static double overrideMaxDvrDurationSec(double originalDurationSec) {
        if (!Settings.EXPAND_LIVESTREAM_DVR_DURATION.get()) return originalDurationSec;
        if (originalDurationSec <= 0) return originalDurationSec;
        return SEVEN_DAYS_IN_SECONDS;
    }

    /**
     * Injection point.
     */
    public static boolean enableLivestreamDvr(boolean original) {
        return original || Settings.LIVESTREAM_DVR.get();
    }

}
