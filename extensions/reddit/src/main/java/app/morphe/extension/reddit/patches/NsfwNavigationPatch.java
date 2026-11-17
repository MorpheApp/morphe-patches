package app.morphe.extension.reddit.patches;

import android.app.Activity;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;

public final class NsfwNavigationPatch {
    private NsfwNavigationPatch() {}

    public static int goBackLabelResource() {
        return ResourceUtils.getStringIdentifier("action_go_back");
    }

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
