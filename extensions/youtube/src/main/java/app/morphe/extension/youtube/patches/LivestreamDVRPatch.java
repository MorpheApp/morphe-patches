package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class LivestreamDVRPatch {

    private static final int SEVEN_DAYS_IN_SECONDS = 7 * 24 * 60 * 60;

    /**
     * Injection point.
     */
    public static double overrideMaxDVRDurationSeconds(double originalDurationSeconds) {
        if (!Settings.EXPAND_LIVESTREAM_DVR_DURATION.get()) return originalDurationSeconds;
        if (originalDurationSeconds <= 0) return originalDurationSeconds;
        return SEVEN_DAYS_IN_SECONDS;
    }

    /**
     * Injection point.
     */
    public static boolean enableLivestreamDVR(boolean original) {
        return original || Settings.LIVESTREAM_DVR.get();
    }

}
