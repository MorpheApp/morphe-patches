package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableAutoCaptionsPatch {
    public enum DisableAutoCaptionsStyle {
        KEEP_BOTH(0),
        DISABLE_BOTH(1),
        WITH_VOLUME_ONLY(2),
        WITHOUT_VOLUME_ONLY(3);

        @Nullable
        static DisableAutoCaptionsPatch.DisableAutoCaptionsStyle blockTypeFromOrdinal(int blockType) {
            for (DisableAutoCaptionsPatch.DisableAutoCaptionsStyle value : values()) {
                if (value.blockType == blockType) {
                    return value;
                }
            }

            return null;
        }

        final int blockType;

        DisableAutoCaptionsStyle(int blockType) {
            this.blockType = blockType;
        }
    }

    private static volatile boolean captionsButtonStatus;

    /**
     * Injection point.
     */
    public static boolean disableAutoCaptions() {
        DisableAutoCaptionsStyle blockType = Settings.DISABLE_AUTO_CAPTIONS_STYLE.get();
        return (blockType == DisableAutoCaptionsStyle.KEEP_BOTH || blockType == DisableAutoCaptionsStyle.WITH_VOLUME_ONLY)
                    &&
                !captionsButtonStatus;
    }

    /**
     * Injection point.
     */
    public static boolean disableMuteAutoCaptions() {
        DisableAutoCaptionsStyle blockType = Settings.DISABLE_AUTO_CAPTIONS_STYLE.get();
        return !((blockType == DisableAutoCaptionsStyle.KEEP_BOTH || blockType == DisableAutoCaptionsStyle.WITHOUT_VOLUME_ONLY)
                    &&
                !captionsButtonStatus);
    }

    /**
     * Injection point.
     */
    public static void setCaptionsButtonStatus(boolean status) {
        captionsButtonStatus = status;
    }
}
