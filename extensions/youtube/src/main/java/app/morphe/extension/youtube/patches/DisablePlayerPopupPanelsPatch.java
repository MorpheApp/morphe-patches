package app.morphe.extension.youtube.patches;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisablePlayerPopupPanelsPatch {

    /**
     * Injection point.
     */
    public static boolean disablePlayerPopupPanels() {
        if (Settings.DISABLE_PLAYER_POPUP_PANELS.get()) {
            Logger.printDebug(() -> "disablePlayerPopupPanels: Popup panels blocked!");
            return true;
        }
        return false;
    }
}
