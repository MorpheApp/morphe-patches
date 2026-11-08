package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class DisableDoubleTapActionsPatch {

    /**
     * Injection point.
     *
     * @return If "should skip to chapter start" flag is set.
     */
    public static boolean disableDoubleTapChapters(boolean original) {
        return Logger.traceOverride("DisableDoubleTapActionsPatch.disableDoubleTapChapters", original,
                original && !Settings.DISABLE_CHAPTER_SKIP_DOUBLE_TAP.get());
    }

    /**
     * Injection point.
     * Server config value that turns on the two-finger double tap chapter skip.
     * Some accounts get it turned off, so the gesture seeks by the double tap length
     * like a one-finger double tap, even when the chapter skip is not disabled.
     *
     * @return If the chapter skip is turned on.
     */
    public static boolean enableDoubleTapChaptersConfig(boolean original) {
        return Logger.traceOverride("DisableDoubleTapActionsPatch.enableDoubleTapChaptersConfig", original,
                original || !Settings.DISABLE_CHAPTER_SKIP_DOUBLE_TAP.get());
    }

    /**
     * Injection point.
     * A/B flag that turns off the two-finger double tap chapter skip,
     * so the gesture seeks by the double tap length like a one-finger double tap.
     *
     * @return If the chapter skip is turned off.
     */
    public static boolean disableDoubleTapChaptersFeatureFlag(boolean original) {
        return Logger.traceOverride("DisableDoubleTapActionsPatch.disableDoubleTapChaptersFeatureFlag", original,
                Settings.DISABLE_CHAPTER_SKIP_DOUBLE_TAP.get());
    }
}
