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

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
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
    public static void onCreate(Activity ignored) {
        if (SharedYouTubeSettings.SETTINGS_INITIALIZED.get()) {
            return;
        }
        // Save now in case this dialog somehow cannot be shown.
        SharedYouTubeSettings.SETTINGS_INITIALIZED.save(true);

        runOnMainThreadDelayed(() -> {
            Activity context = Utils.getActivity();
            if (context == null) {
                Logger.printInfo(() -> "Activity is null, skipping restart dialog");
                return;
            }

            if (context.isFinishing()) {
                Logger.printInfo(() -> "Activity is finishing, skipping restart dialog");
                return;
            }

            // Allow canceling if device is Android 9 or less to allow forcing
            // in-app dark mode before restarting (stock YouTube bug).
            Runnable cancel = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P
                    ? () -> {}
                    : null;

            Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                    context,
                    str("morphe_settings_restart_title"),   // Title.
                    str("morphe_restart_first_run"),        // Message.
                    null,                                       // No EditText.
                    str("morphe_settings_restart"),         // OK button text.
                    () -> Utils.restartApp(context),            // OK button action.
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