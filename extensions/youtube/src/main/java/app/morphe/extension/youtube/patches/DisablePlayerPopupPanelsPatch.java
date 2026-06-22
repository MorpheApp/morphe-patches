package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisablePlayerPopupPanelsPatch {
    /**
     * Injection point.
     */
    public static boolean disablePlayerPopupPanels(boolean value1, boolean value2) {
        if (!Settings.DISABLE_PLAYER_POPUP_PANELS.get()) {
            return false;
        }
        return value1 && value2;
    }
}
