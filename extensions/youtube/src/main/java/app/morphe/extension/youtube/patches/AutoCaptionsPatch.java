package app.morphe.extension.youtube.patches;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.patches.youtube.misc.playservice.is_20_26_or_greater

@SuppressWarnings("unused")
public class AutoCaptionsPatch {

    public enum AutoCaptionsStyle {
        BOTH_ENABLED,
        BOTH_DISABLED,
        WITH_VOLUME_ONLY,
        WITHOUT_VOLUME_ONLY
    }
    public enum AutoCaptionsStyleLegacy {
        ENABLED,
        DISABLED
    }

    private static volatile boolean captionsButtonStatus;

    /**
     * Injection point.
     */
    public static boolean disableAutoCaptions() {
        AutoCaptionsStyle style =
            Settings.AUTO_CAPTIONS_STYLE.get();
        boolean withVolumeAutoCaptioningEnabled;

        if (is_20_26_or_greater) {
            withVolumeAutoCaptioningEnabled =
                style == AutoCaptionsStyle.BOTH_ENABLED || style == AutoCaptionsStyle.WITH_VOLUME_ONLY;
        } else {
            withVolumeAutoCaptioningEnabled =
                style == AutoCaptionsStyleLegacy.ENABLED;
        }

        if (!withVolumeAutoCaptioningEnabled) {
            /**
             * Do this trick to disable auto-captioning only
             * when 'withVolumeAutoCaptioningEnabled'
             * field is false
             */

            return captionsButtonStatus;
        }

        return true;
    }

    /**
     * Injection point.
     *
     * Note: 'captionsButtonStatus' field check is not needed here
     * because it's only related to 'disableAutoCaptions()' method
     * in order to prevent auto-captioning with volume enabled
     */
    public static boolean disableMuteAutoCaptions() {
        AutoCaptionsStyle style =
            Settings.AUTO_CAPTIONS_STYLE.get();

        return style == AutoCaptionsStyle.BOTH_ENABLED || style == AutoCaptionsStyle.WITHOUT_VOLUME_ONLY;
    }

    /**
     * Injection point.
     */
    public static void setCaptionsButtonStatus(boolean status) {
        captionsButtonStatus = status;
    }
}
