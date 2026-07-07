package app.morphe.extension.shared.spoof;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class SpoofAppVersionPatch {

    public static String getDefaultTarget() {
        return "";
    }

    public static final boolean SPOOF_APP_VERSION_ENABLED = SharedYouTubeSettings.SPOOF_APP_VERSION.get();
    public static final String SPOOF_APP_VERSION_TARGET = SharedYouTubeSettings.SPOOF_APP_VERSION_TARGET.get();

    public static String getUniversalAppVersionOverride(String version) {
        return SPOOF_APP_VERSION_ENABLED
                ? SPOOF_APP_VERSION_TARGET
                : version;
    }

    public static boolean isSpoofingToLessThan(String version) {
        return SPOOF_APP_VERSION_ENABLED && SPOOF_APP_VERSION_TARGET.compareTo(version) < 0;
    }
}
