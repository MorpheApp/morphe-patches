package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.StringKeyLookup;
import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.Utils.getAppVersionName;
import static app.morphe.extension.shared.Utils.getRecommendedAppVersion;

import android.app.Activity;
import android.app.Dialog;
import android.text.Html;
import android.util.Pair;
import android.view.Gravity;
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
                        <b>🔬️ This app version is experimental</b>️
                        <br/><br/>
                        You are using an experimental app version of ⚠️ <b>%1$s</b>.
                        <br/><br/>
                        You may experience unusual app behavior or unidentified bugs.
                        <br/><br/>
                        If you want the most trouble free experience, then uninstall this and patch the recommended app version of ✅ <b>%2$s</b>""",

                    "morphe_experimental_app_version_dialog_ignore",
                    "⚠️ I am brave",

                    "morphe_experimental_app_version_dialog_open_homepage",
                    "✅ Exit"
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
    public static void showExperimentalNoticeIfNeeded(Activity context) {
        try {
            String appVersionName = Utils.getAppVersionName();
            String recommendedAppVersion = Utils.getRecommendedAppVersion();

            // The current app is the same or less than the recommended.
            // YT 21.x releases now use nn.nn.nnn numbers, but this still sorts correctly compared to older releases.
            if (appVersionName.compareTo(recommendedAppVersion) <= 0) {
                return;
            }

            if (BaseSettings.EXPERIMENTAL_APP_CONFIRMED.get().equals(appVersionName)) {
                // User already confirmed they are aware this is experimental.
                return;
            }

            Logger.printDebug(() -> getString("morphe_experimental_app_version_dialog_message", getAppVersionName(), getRecommendedAppVersion()));

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    null, // Title.
                    Html.fromHtml(getString("morphe_experimental_app_version_dialog_message", getAppVersionName(), getRecommendedAppVersion())), // Message.
                    null, // No EditText.
                    getString("morphe_experimental_app_version_dialog_open_homepage"), // OK button text.
                    () -> {
                        Utils.openLink("https://morphe.software"); // TODO: Send users to a unique page.
                        System.exit(0);
                    }, // OK button action.
                    null, // Cancel button action.
                    getString("morphe_experimental_app_version_dialog_ignore"), // Neutral button text.
                    () -> BaseSettings.EXPERIMENTAL_APP_CONFIRMED.save(appVersionName), // Neutral button action.
                    true // Dismiss dialog on Neutral button click.
            );

            LinearLayout layout = dialogPair.second;

//            // Must set layout parameters otherwise HTML centering doesn't work.
//            layout.setLayoutParams(new LinearLayout.LayoutParams(
//                    ViewGroup.LayoutParams.MATCH_PARENT,
//                    ViewGroup.LayoutParams.WRAP_CONTENT
//            ));
            layout.setGravity(Gravity.CENTER_HORIZONTAL);

            Utils.showDialog(context, dialogPair.first, false, null);
        } catch (Exception ex) {
            Logger.printException(() -> "showExperimentalNoticeIfNeeded failure", ex);
        }
    }
}
