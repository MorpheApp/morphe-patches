package app.morphe.extension.reddit.patches;

import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import app.morphe.extension.reddit.settings.Settings;

@SuppressWarnings("unused")
public class RemoveSubRedditDialogPatch {
    private static final boolean REMOVE_NSFW_DIALOG = Settings.REMOVE_NSFW_DIALOG.get();
    private static final boolean REMOVE_NOTIFICATION_DIALOG = Settings.REMOVE_NOTIFICATION_DIALOG.get();

    /**
     * @return If this patch was included during patching.
     */
    public static boolean isPatchIncluded() {
        return false;  // Modified during patching.
    }

    /**
     * Injection point.
     */
    public static boolean spoofHasBeenVisitedStatus(boolean hasBeenVisited) {
        return REMOVE_NSFW_DIALOG || hasBeenVisited;
    }

    /**
     * Injection point.
     */
    public static void dismissNSFWDialog(Object customDialog) {
        if (REMOVE_NSFW_DIALOG && customDialog instanceof Dialog dialog) {
            Window window = dialog.getWindow();
            if (window != null) {
                WindowManager.LayoutParams params = window.getAttributes();
                params.height = 0;
                params.width = 0;

                // Change the size of dialog to 0.
                window.setAttributes(params);

                // Disable dialog's background dim.
                window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                // Hide DecorView.
                View decorView = window.getDecorView();
                decorView.setVisibility(View.GONE);

                // Dismiss dialog.
                dialog.dismiss();
            }
        }
    }

    /**
     * Injection point.
     */
    public static boolean spoofLoggedInStatus(boolean isLoggedIn) {
        return !REMOVE_NOTIFICATION_DIALOG && isLoggedIn;
    }
}
