package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class ExpandLivestreamDvrDurationPatch {

    private static final double SEVEN_DAYS_SEC = 7.0 * 24 * 60 * 60;

    /**
     * Injection point.
     */
    public static double overrideMaxDvrDurationSec(double originalDurationSec) {
        if (!Settings.EXPAND_LIVESTREAM_DVR_DURATION.get()) return originalDurationSec;
        if (originalDurationSec <= 0) return originalDurationSec;
        return SEVEN_DAYS_SEC;
    }
}
