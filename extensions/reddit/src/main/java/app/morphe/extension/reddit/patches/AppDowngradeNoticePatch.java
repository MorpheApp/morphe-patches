/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.reddit.patches;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.text.Html;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.reddit.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Disable Play Store updates allows installing an older app version over a newer one,
 * but the app data of newer versions crashes older versions.
 */
@SuppressWarnings({"deprecation", "unused"})
public class AppDowngradeNoticePatch {

    /**
     * Injection point.
     */
    public static void showDowngradeNoticeIfNeeded(Activity activity) {
        try {
            String appVersionName = Utils.getAppVersionName();
            String highestAppVersion = Settings.HIGHEST_APP_VERSION.get();

            final int comparison = appVersionName.compareTo(highestAppVersion);
            if (comparison > 0) {
                Settings.HIGHEST_APP_VERSION.save(appVersionName);
            }
            if (comparison >= 0) {
                return;
            }

            Logger.printInfo(() -> "App was downgraded from: " + highestAppVersion + " to: " + appVersionName);

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    str("morphe_app_downgrade_dialog_title"), // Title.
                    Html.fromHtml(str("morphe_app_downgrade_dialog_message", highestAppVersion, appVersionName)), // Message.
                    null, // No EditText.
                    str("morphe_app_downgrade_dialog_open_app_info"), // OK button text.
                    () -> {
                        Intent intent = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", activity.getPackageName(), null));
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        activity.startActivity(intent);
                        activity.finishAndRemoveTask();
                    }, // OK button action.
                    null, // No cancel button.
                    null, // No neutral button.
                    null, // No neutral button action.
                    true // Dismiss dialog on Neutral button click.
            );

            Utils.showDialog(activity, dialogPair.first, false, null);
        } catch (Exception ex) {
            Logger.printException(() -> "showDowngradeNoticeIfNeeded failure", ex);
        }
    }
}
