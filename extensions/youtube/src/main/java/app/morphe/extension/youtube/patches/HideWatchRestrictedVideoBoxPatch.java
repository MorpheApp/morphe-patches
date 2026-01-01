package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class HideWatchRestrictedVideoBoxPatch {
    public static boolean hideConfirmationBox() {
        return !Settings.RESTRICTED_VIDEO_BOX.get();
    }
}
