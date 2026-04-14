package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.text.Html;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings({"deprecation", "unused"})
public class ExperimentalAppNoticePatch {

    public static boolean experimentalNoticeShouldBeShown() {
        String appVersionName = Utils.getAppVersionName();
        String recommendedAppVersion = Utils.getRecommendedAppVersion();

        // The current app is the same or less than the recommended.
        // YT 21.x uses nn.nn.nnn numbers but still sorts correctly compared to older releases.
        if (appVersionName.compareTo(recommendedAppVersion) <= 0) {
            return false;
        }

        // User already confirmed experimental.
        return !BaseSettings.EXPERIMENTAL_APP_CONFIRMED.get().equals(appVersionName);
    }

    /**
     * Injection point.
     * <p>
     * Checks if YouTube watch history endpoint cannot be reached.
     */
    public static void showExperimentalNoticeIfNeeded(Activity activity) {
        try {
            if (!experimentalNoticeShouldBeShown()) {
                return;
            }

            String appVersionName = Utils.getAppVersionName();
            String recommendedAppVersion = Utils.getRecommendedAppVersion();

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    str("morphe_experimental_app_version_dialog_title"), // Title.
                    Html.fromHtml(str("morphe_experimental_app_version_dialog_message", appVersionName, recommendedAppVersion)), // Message.
                    null, // No EditText.
                    str("morphe_experimental_app_version_dialog_open_homepage"), // OK button text.
                    () -> {
                        Utils.openLink("https://morphe.software"); // TODO? Send users to a unique page.
                        activity.finishAndRemoveTask(); // Shutdown the app. More proper than calling System.exit().
                    }, // OK button action.
                    null, // Cancel button action.
                    str("morphe_experimental_app_version_dialog_confirm"), // Neutral button text.
                    () -> BaseSettings.EXPERIMENTAL_APP_CONFIRMED.save(appVersionName), // Neutral button action.
                    true // Dismiss dialog on Neutral button click.
            );

            Utils.showDialog(activity, dialogPair.first, false, null);
        } catch (Exception ex) {
            Logger.printException(() -> "showExperimentalNoticeIfNeeded failure", ex);
        }
    }
}
