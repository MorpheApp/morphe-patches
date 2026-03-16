/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.shared.Utils.runOnMainThreadDelayed;
import static app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment.showRestartDialog;

import android.app.Activity;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class InitializationPatch {

    /**
     * Some layouts that depend on litho do not load when the app is first installed.
     * (Also reproduced on un-patched YouTube)
     * <p>
     * To fix this, show the restart dialog when the app is installed for the first time.
     */
    public static void onCreate(Activity mActivity) {
        if (!SharedYouTubeSettings.SETTINGS_INITIALIZED.get()) {
            runOnMainThreadDelayed(() ->
                    SharedYouTubeSettings.SETTINGS_INITIALIZED.save(true), 1000);
            runOnMainThreadDelayed(() ->
                    showRestartDialog(mActivity, str("morphe_restart_first_run")), 3500);
        }
    }
}