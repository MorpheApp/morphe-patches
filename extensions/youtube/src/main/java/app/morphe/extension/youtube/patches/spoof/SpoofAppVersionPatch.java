package app.morphe.extension.youtube.patches.spoof;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SpoofAppVersionPatch {

    private static final boolean SPOOF_APP_VERSION_ENABLED = Settings.SPOOF_APP_VERSION.get();
    private static final String SPOOF_APP_VERSION_TARGET = Settings.SPOOF_APP_VERSION_TARGET.get();

    private static final boolean DISABLE_BOLD_ICONS = isSpoofingToLessThan("20.30.00");

    /**
     * Injection point.
     * <p>
     * Called before {@link #getShortsAppVersionOverride(String)}.
     * Called from all endpoints.
     */
    public static String getUniversalAppVersionOverride(String version) {
        return SPOOF_APP_VERSION_ENABLED
                ? SPOOF_APP_VERSION_TARGET
                : version;
    }

    public static boolean isSpoofingToLessThan(String version) {
        return SPOOF_APP_VERSION_ENABLED && SPOOF_APP_VERSION_TARGET.compareTo(version) < 0;
    }

    /**
     * Injection point.
     * Used on YouTube 20.31 ~ 21.04.
     */
    public static boolean disableShortsBoldIcons(boolean original) {
        return !DISABLE_BOLD_ICONS && original;
    }

    /**
     * Injection point.
     * Used on YouTube 21.05+.
     * <p>
     * Called after {@link #getUniversalAppVersionOverride(String)}.
     * Called from the '/reel/create_reel_items', '/reel/reel_item_watch', and '/reel/reel_watch_sequence' endpoints.
     */
    public static String getShortsAppVersionOverride(String version) {
        return DISABLE_BOLD_ICONS
                ? "20.30.40" // Oldest version that supports new Shorts overlay endpoint response.
                : version;
    }

}
