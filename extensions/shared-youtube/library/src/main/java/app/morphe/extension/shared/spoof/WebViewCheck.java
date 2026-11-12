/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4733
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.spoof;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Pair;
import android.webkit.WebView;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.ui.CustomDialog;

/**
 * Checks if the system WebView, which runs the JavaScript of the JavaScript clients, is turned on and up to date.
 */
@SuppressWarnings("unused")
public final class WebViewCheck {

    public enum WebViewStatus {
        OK,
        /**
         * Turned off, or not installed.
         */
        MISSING,
        OUTDATED
    }

    /**
     * Versions before this are shown as outdated.
     * JavaScript sometimes fails with WebView versions that were not updated for a long time.
     */
    private static final int MIN_WEBVIEW_MAJOR_VERSION = 130;

    /**
     * Package of Android System WebView, used when there is no WebView provider.
     */
    private static final String WEBVIEW_PACKAGE = "com.google.android.webview";

    private static final String PLAY_STORE_PACKAGE = "com.android.vending";

    /**
     * If a dialog button was tapped since the app started. Only used on the main thread.
     */
    private static boolean dialogAnswered;

    private WebViewCheck() {
    }

    public static WebViewStatus getStatus() {
        return getStatus(WebView.getCurrentWebViewPackage());
    }

    private static WebViewStatus getStatus(@Nullable PackageInfo webView) {
        if (webView == null) {
            return WebViewStatus.MISSING;
        }

        Integer majorVersion = getMajorVersion(webView.versionName);
        if (majorVersion != null && majorVersion < MIN_WEBVIEW_MAJOR_VERSION) {
            return WebViewStatus.OUTDATED;
        }
        return WebViewStatus.OK;
    }

    @Nullable
    private static Integer getMajorVersion(@Nullable String versionName) {
        if (versionName == null) {
            Logger.printException(() -> "WebView has no version name");
            return null;
        }
        try {
            int end = versionName.indexOf('.');
            return Integer.parseInt(end < 0 ? versionName : versionName.substring(0, end));
        } catch (NumberFormatException ex) {
            Logger.printException(() -> "Unknown WebView version: " + versionName, ex);
            return null;
        }
    }

    /**
     * Injection point.
     */
    public static void showDialogIfNeeded(Activity activity) {
        try {
            if (dialogAnswered || !SharedYouTubeSettings.SPOOF_VIDEO_STREAMS_WEBVIEW_DIALOG.get()
                    || !SpoofVideoStreamsPatch.isSpoofingEnabled()
                    || !SpoofVideoStreamsPatch.getPreferredClient().requireJS) {
                return;
            }

            PackageInfo webView = WebView.getCurrentWebViewPackage();
            WebViewStatus status = getStatus(webView);
            if (status == WebViewStatus.OK) {
                return;
            }

            String packageName = webView == null ? WEBVIEW_PACKAGE : webView.packageName;
            Logger.printInfo(() -> "WebView status: " + status + " package: " + packageName
                    + (webView == null ? "" : " version: " + webView.versionName));

            String message = status == WebViewStatus.MISSING
                    ? str("morphe_spoof_video_streams_webview_dialog_message_missing")
                    : str("morphe_spoof_video_streams_webview_dialog_message_outdated", webView.versionName);

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    str("morphe_spoof_video_streams_webview_dialog_title"), // Title.
                    message, // Message.
                    null, // No EditText.
                    null, // OK button text.
                    () -> {
                        dialogAnswered = true;
                        openWebViewPage(activity, packageName);
                    }, // OK button action.
                    null, // No cancel button.
                    str("morphe_spoof_video_streams_webview_dialog_ignore"), // Neutral button text.
                    () -> {
                        dialogAnswered = true;
                        SharedYouTubeSettings.SPOOF_VIDEO_STREAMS_WEBVIEW_DIALOG.save(false);
                    }, // Neutral button action.
                    true // Dismiss dialog on Neutral button click.
            );

            // Not shown with Utils.showDialog(), because its dialog fragment crashes
            // when the activity is recreated during startup. The recreated activity shows this again.
            Dialog dialog = dialogPair.first;
            dialog.setCancelable(false);
            dialog.show();
        } catch (Exception ex) {
            Logger.printException(() -> "showDialogIfNeeded failure", ex);
        }
    }

    /**
     * Opens the Play Store page of the WebView, which can turn it on, install it and update it.
     * Without the Play Store, opens the app info, where it can be turned on.
     */
    private static void openWebViewPage(Context context, String packageName) {
        try {
            PackageManager packageManager = context.getPackageManager();
            Intent playStore = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=" + packageName))
                    .setPackage(PLAY_STORE_PACKAGE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            // Check first, because YouTube shows its own error toast when an activity is not found.
            if (playStore.resolveActivity(packageManager) != null) {
                context.startActivity(playStore);
                return;
            }

            try {
                packageManager.getApplicationInfo(packageName, 0);
            } catch (PackageManager.NameNotFoundException ex) {
                // The app info of a package that is not installed closes right away.
                Logger.printInfo(() -> "No Play Store and WebView is not installed: " + packageName, ex);
                Utils.showToastLong(str("morphe_spoof_video_streams_webview_toast_not_installed"));
                return;
            }

            Logger.printInfo(() -> "No Play Store, opening app info of: " + packageName);
            Intent appInfo = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(appInfo);
        } catch (Exception ex) {
            Logger.printException(() -> "openWebViewPage failure", ex);
        }
    }
}
