package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class LiveStreamDVRPatch {

    private static final int SEVEN_DAYS_IN_SECONDS = 7 * 24 * 60 * 60;

    /**
     * Injection point.
     */
    public static double overrideMaxDVRDurationSeconds(double originalDurationSeconds) {
        if (!Settings.EXPAND_LIVE_STREAM_DVR_DURATION.get()) return originalDurationSeconds;
        if (originalDurationSeconds <= 0) return originalDurationSeconds;
        return SEVEN_DAYS_IN_SECONDS;
    }

    /**
     * Injection point.
     */
    public static boolean enableLiveStreamDVR(boolean original) {
        return original || Settings.LIVE_STREAM_DVR.get();
    }

}
