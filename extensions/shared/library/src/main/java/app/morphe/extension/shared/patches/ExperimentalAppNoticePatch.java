package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.StringKeyLookup;
import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.text.Html;
import android.util.Pair;
import android.widget.LinearLayout;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("unused")
public class ExperimentalAppNoticePatch {

    // Backup strings for Reddit. Remove this when Reddit gets resource patching or localized strings.
    private static final StringKeyLookup strings = new StringKeyLookup(
            Map.of("morphe_experimental_app_version_dialog_message",
                    """
                    <b>🚨 Experimental Alert! 🚨</b>️
                    <br/><br/>
                    You are using an experimental app version of ⚠️ <b>%s</b>
                    <br/><br/>
                    🔧 Expect quirky app behavior or unidentified bugs as we fine tune the patches for this app version.
                    <br/><br/>
                    If you want the most trouble free experience, then <b>uninstall</b> this and patch the recommended app version of ✅ <b>%s</b>""",

                    "morphe_experimental_app_version_dialog_ignore",
                    "⚠️ I want experimental",

                    "morphe_experimental_app_version_dialog_open_homepage",
                    "✅ I want stable"
            )
    );

    private static String getString(String key, Object... args) {
        if (ResourceUtils.getIdentifier(ResourceType.STRING, key) == 0) {
            return strings.getString(key, args);
        }
        return str(key, args);
    }

    /**
     * Injection point.
     *
     * Checks if YouTube watch history endpoint cannot be reached.
     */
    public static void showExperimentalNoticeIfNeeded(Activity activity) {
        try {
            String appVersionName = Utils.getAppVersionName();
            String recommendedAppVersion = Utils.getRecommendedAppVersion();

            // The current app is the same or less than the recommended.
            // YT 21.x uses nn.nn.nnn numbers but still sorts correctly compared to older releases.
            if (appVersionName.compareTo(recommendedAppVersion) <= 0) {
                return;
            }

            if (BaseSettings.EXPERIMENTAL_APP_CONFIRMED.get().equals(appVersionName)) {
                return; // User already confirmed experimental.
            }

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    null, // Title.
                    Html.fromHtml(getString("morphe_experimental_app_version_dialog_message", appVersionName, recommendedAppVersion)), // Message.
                    null, // No EditText.
                    getString("morphe_experimental_app_version_dialog_open_homepage"), // OK button text.
                    () -> {
                        Utils.openLink("https://morphe.software"); // TODO? Send users to a unique page.
                        activity.finishAndRemoveTask(); // Shutdown the app. More proper than calling System.exit().
                    }, // OK button action.
                    null, // Cancel button action.
                    getString("morphe_experimental_app_version_dialog_ignore"), // Neutral button text.
                    () -> BaseSettings.EXPERIMENTAL_APP_CONFIRMED.save(appVersionName), // Neutral button action.
                    true // Dismiss dialog on Neutral button click.
            );

            Utils.showDialog(activity, dialogPair.first, false, null);
        } catch (Exception ex) {
            Logger.printException(() -> "showExperimentalNoticeIfNeeded failure", ex);
        }
    }
}
