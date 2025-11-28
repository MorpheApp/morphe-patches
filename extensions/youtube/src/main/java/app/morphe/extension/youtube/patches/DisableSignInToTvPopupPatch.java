package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableSignInToTvPopupPatch {

    /**
     * Injection point.
     */
    public static boolean disableSignInToTvPopup() {
        return Settings.DISABLE_SIGNIN_TO_TV_POPUP.get();
    }
}
