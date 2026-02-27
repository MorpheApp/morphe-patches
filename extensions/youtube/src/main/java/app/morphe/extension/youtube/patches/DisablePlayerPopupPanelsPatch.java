package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisablePlayerPopupPanelsPatch {
    /**
     * Injection point.
     */
    public static boolean disablePlayerPopupPanels() {
        return Settings.DISABLE_PLAYER_POPUP_PANELS.get();
    }
}
