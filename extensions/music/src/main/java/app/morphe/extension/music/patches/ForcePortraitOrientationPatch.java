package app.morphe.extension.music.patches;

import android.app.Activity;
import android.content.pm.ActivityInfo;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class ForcePortraitOrientationPatch {

    /**
     * Injection point.
     */
    public static void setRequestedOrientation(Activity activity) {
        try {
            if (Settings.FORCE_PORTRAIT_ORIENTATION.get()) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setRequestedOrientation failure", ex);
        }
    }
}
