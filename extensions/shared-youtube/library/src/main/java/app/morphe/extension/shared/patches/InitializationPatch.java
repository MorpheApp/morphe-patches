/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.Utils.runOnMainThreadDelayed;

import android.app.Activity;
import android.app.Dialog;
import android.os.Build;
import android.util.Pair;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.ui.CustomDialog;

@SuppressWarnings("unused")
public class InitializationPatch {

    /**
     * Some layouts that depend on litho do not load when the app is first installed.
     * (Also reproduced on un-patched YouTube)
     * <p>
     * To fix this, show the restart dialog when the app is installed for the first time.
     */
    public static void onCreate(Activity activity) {
        if (SharedYouTubeSettings.SETTINGS_INITIALIZED.get()) {
            return;
        }

        // TODO: Eventually remove this check.
        // Don't prompt to restart on an app upgrade from older patches that did not ask to restart.
        if (System.currentTimeMillis() - BaseSettings.FIRST_TIME_APP_LAUNCHED.get() > (10 * 60 * 1000)) {
            // App was first launched more than 10 minutes ago.
            SharedYouTubeSettings.SETTINGS_INITIALIZED.save(true);
            return;
        }

        runOnMainThreadDelayed(() -> SharedYouTubeSettings.SETTINGS_INITIALIZED.save(true), 1000);
        runOnMainThreadDelayed(() -> {
            // Allow canceling if device is Android 9 or less to allow forcing
            // in-app dark mode before restarting (stock YouTube bug).
            Runnable cancel = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    ? () -> {}
                    : null;

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    activity,
                    str("morphe_settings_restart_title"),   // Title.
                    str("morphe_restart_first_run"),        // Message.
                    null,                                       // No EditText.
                    str("morphe_settings_restart"),         // OK button text.
                    () -> Utils.restartApp(activity),           // OK button action.
                    cancel,                                     // Cancel button.
                    null,                                       // No Neutral button text.
                    null,                                       // No Neutral button action.
                    true                                        // Dismiss dialog when onNeutralClick.
            );

            Dialog dialog = dialogPair.first;
            dialog.setCancelable(false);
            dialog.show();
        }, 3500);
    }
}