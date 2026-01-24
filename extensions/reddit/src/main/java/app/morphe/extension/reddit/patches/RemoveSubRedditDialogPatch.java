package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.annotation.NonNull;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class RemoveSubRedditDialogPatch {

    public static void confirmDialog(@NonNull TextView textView) {
        if (!Settings.REMOVE_NSFW_DIALOG.get())
            return;

        if (!textView.getText().toString().equals(str("nsfw_continue_non_anonymously")))
            return;

        clickViewDelayed(textView);
    }

    public static void dismissDialog(View cancelButtonView) {
        if (!Settings.REMOVE_NOTIFICATION_DIALOG.get())
            return;

        clickViewDelayed(cancelButtonView);
    }

    public static boolean spoofHasBeenVisitedStatus(boolean hasBeenVisited) {
        return Settings.REMOVE_NSFW_DIALOG.get() || hasBeenVisited;
    }

    public static void dismissNSFWDialog(Object customDialog) {
        if (Settings.REMOVE_NSFW_DIALOG.get() &&
                customDialog instanceof Dialog dialog) {
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

    public static boolean removeNSFWDialog() {
        return Settings.REMOVE_NSFW_DIALOG.get();
    }

    public static boolean spoofLoggedInStatus(boolean isLoggedIn) {
        return !Settings.REMOVE_NOTIFICATION_DIALOG.get() && isLoggedIn;
    }

    private static void clickViewDelayed(View view) {
        Utils.runOnMainThreadDelayed(() -> {
            if (view != null) {
                view.setSoundEffectsEnabled(false);
                view.performClick();
            }
        }, 0);
    }
}
