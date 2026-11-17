package app.morphe.extension.reddit.patches;

import android.app.Activity;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.Logger;

public final class NsfwContentPatch {
    private NsfwContentPatch() {}

    public static void showProfileWarning(Activity activity) {
        Utils.runOnMainThread(() -> {
            try {
                if (!activity.isFinishing() && !activity.isDestroyed()) openProfileWarning(activity);
            } catch (Exception ex) {
                Logger.printException(() -> "showProfileWarning failure", ex);
            }
        });
    }

    private static void openProfileWarning(Activity activity) {
        // Replaced with Reddit's native warning screen and navigation call.
    }
}
