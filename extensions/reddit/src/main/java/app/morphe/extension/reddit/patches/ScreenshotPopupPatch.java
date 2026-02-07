package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public class ScreenshotPopupPatch {

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    public static Boolean disableScreenshotPopup(Boolean original) {
        return Settings.DISABLE_SCREENSHOT_POPUP.get() ? Boolean.FALSE : original;
    }
}
