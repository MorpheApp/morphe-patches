package app.morphe.extension.youtube.patches.spoof;

import app.morphe.extension.youtube.patches.VersionCheckPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SpoofAppVersionPatch {

    private static final boolean SPOOF_APP_VERSION_ENABLED = Settings.SPOOF_APP_VERSION.get();
    private static final String SPOOF_APP_VERSION_TARGET = Settings.SPOOF_APP_VERSION_TARGET.get();

    private static final boolean DISABLE_BOLD_ICONS = VersionCheckPatch.IS_20_31_OR_GREATER
            && isSpoofingToLessThan("20.30.00");

    /**
     * injection point.
     */
    public static String getAppVersionOverride(String version) {
        if (SPOOF_APP_VERSION_ENABLED) return SPOOF_APP_VERSION_TARGET;
        return version;
    }

    public static boolean isSpoofingToLessThan(String version) {
        return SPOOF_APP_VERSION_ENABLED && SPOOF_APP_VERSION_TARGET.compareTo(version) < 0;
    }

    /**
     * Injection point.
     */
    public static boolean disableShortsBoldIcons(boolean original) {
        return !DISABLE_BOLD_ICONS && original;
    }

}
