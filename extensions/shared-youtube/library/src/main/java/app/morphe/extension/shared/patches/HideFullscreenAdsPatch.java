/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.ByteTrieSearch.convertStringsToBytes;

import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class HideFullscreenAdsPatch {

    private static final ByteTrieSearch FULLSCREEN_AD_SEARCH = new ByteTrieSearch(
            convertStringsToBytes("_interstitial")
    );

    /**
     * Injection point.
     */
    public static void closeFullscreenAd(Object customDialog, @Nullable byte[] buffer) {
        try {
            if (!SharedYouTubeSettings.HIDE_FULLSCREEN_ADS.get()) {
                return;
            }

            if (buffer == null) {
                Logger.printDebug(() -> "buffer is null");
                return;
            }

            if (customDialog instanceof Dialog dialog && FULLSCREEN_AD_SEARCH.matches(buffer)) {
                Logger.printDebug(() -> "Closing fullscreen ad");

                Window window = dialog.getWindow();

                if (window != null) {
                    // Set the dialog size to 0 before closing
                    // If the dialog is not resized to 0, it will remain visible for about a second before closing
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.height = 0;
                    params.width = 0;

                    // Change the size of dialog to 0
                    window.setAttributes(params);

                    // Disable dialog's background dim
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);

                    // Restore window flags
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);

                    // Restore decorView visibility
                    View decorView = window.getDecorView();
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                }

                // Dismiss dialog
                dialog.dismiss();
            }
        } catch (Exception ex) {
            Logger.printException(() -> "closeFullscreenAd failure", ex);
        }
    }
}