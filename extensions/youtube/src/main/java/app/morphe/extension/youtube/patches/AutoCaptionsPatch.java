package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class AutoCaptionsPatch {

    public enum AutoCaptionsStyle {
        KEEP_BOTH,
        DISABLE_BOTH,
        WITH_VOLUME_ONLY,
        WITHOUT_VOLUME_ONLY
    }

    private static volatile boolean captionsButtonStatus;

    /**
     * Injection point.
     */
    public static boolean disableAutoCaptions() {
        if (!captionsButtonStatus) return false;

        AutoCaptionsStyle style = Settings.AUTO_CAPTIONS_STYLE.get();
        return style == AutoCaptionsStyle.DISABLE_BOTH || style == AutoCaptionsStyle.WITHOUT_VOLUME_ONLY;
    }

    /**
     * Injection point.
     */
    public static boolean disableMuteAutoCaptions() {
        if (!captionsButtonStatus) return false;

        AutoCaptionsStyle style = Settings.AUTO_CAPTIONS_STYLE.get();
        return style == AutoCaptionsStyle.DISABLE_BOTH || style == AutoCaptionsStyle.WITH_VOLUME_ONLY;
    }

    /**
     * Injection point.
     */
    public static void setCaptionsButtonStatus(boolean status) {
        captionsButtonStatus = status;
    }
}
