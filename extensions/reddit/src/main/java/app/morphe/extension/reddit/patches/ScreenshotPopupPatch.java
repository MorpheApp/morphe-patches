package app.morphe.extension.reddit.patches;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class ScreenshotPopupPatch {

    public static boolean patchEnabled;

    public static void setPatchEnabled() {
        patchEnabled = true;
    }

    public static Boolean disableScreenshotPopup(Boolean original) {
        return Settings.DISABLE_SCREENSHOT_POPUP.get() ? Boolean.FALSE : original;
    }
}
