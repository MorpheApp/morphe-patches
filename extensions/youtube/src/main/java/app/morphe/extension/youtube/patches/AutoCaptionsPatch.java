package app.morphe.extension.youtube.patches;

import androidx.annotation.Nullable;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class AutoCaptionsPatch {
    public enum AutoCaptionsStyle {
        KEEP_BOTH(0),
        DISABLE_BOTH(1),
        WITH_VOLUME_ONLY(2),
        WITHOUT_VOLUME_ONLY(3);

        @Nullable
        static AutoCaptionsPatch.AutoCaptionsStyle blockTypeFromOrdinal(int blockType) {
            for (AutoCaptionsPatch.AutoCaptionsStyle value : values()) {
                if (value.blockType == blockType) {
                    return value;
                }
            }

            return null;
        }

        final int blockType;

        AutoCaptionsStyle(int blockType) {
            this.blockType = blockType;
        }
    }

    private static volatile boolean captionsButtonStatus;

    /**
     * Injection point.
     */
    public static boolean disableAutoCaptions() {
        AutoCaptionsStyle blockType = Settings.AUTO_CAPTIONS_STYLE.get();
        return (blockType == AutoCaptionsStyle.KEEP_BOTH || blockType == AutoCaptionsStyle.WITH_VOLUME_ONLY)
                    &&
                !captionsButtonStatus;
    }

    /**
     * Injection point.
     */
    public static boolean disableMuteAutoCaptions() {
        AutoCaptionsStyle blockType = Settings.AUTO_CAPTIONS_STYLE.get();
        return !((blockType == AutoCaptionsStyle.KEEP_BOTH || blockType == AutoCaptionsStyle.WITHOUT_VOLUME_ONLY)
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
