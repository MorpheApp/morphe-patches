package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableSignInToTVPopupPatch {

    /**
     * Injection point.
     */
    public static boolean disableSignInToTVPopup() {
        return Settings.DISABLE_SIGN_IN_TO_TV_POPUP.get();
    }

    /**
     * Injection point.
     */
    public static boolean disableConnectYourDevicesPopup() {
        return Settings.DISABLE_CONNECT_YOUR_DEVICES_POPUP.get();
    }
}
