/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import static app.morphe.extension.shared.ByteTrieSearch.convertStringsToBytes;
import static app.morphe.extension.shared.patches.AppCheckPatch.IS_YOUTUBE;

import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class HideFullscreenAdsPatch {

    private static final ByteTrieSearch FULLSCREEN_AD_SEARCH = new ByteTrieSearch(
            convertStringsToBytes("_interstitial")
    );

    /**
     * Injection point.
     * Invoke only in old clients.
     */
    public static void hideFullscreenAds(View view) {
        Utils.hideViewBy0dpUnderCondition(SharedYouTubeSettings.HIDE_FULLSCREEN_ADS, view);
    }

    /**
     * Rest of the implementation added by patch.
     */
    private static void closeDialog(Object customDialog) {
        // Casting customDialog to 'android.app.Dialog' and calling [dialog.onBackPressed()] also works with limitations.
        // If the targetSDKVersion is 36+ and the device is running Android 16+, this method will most likely not work.
        //
        // So the patch call the 'onBackPressed()' method of the custom dialog class.
        // The 'onBackPressed()' method of the customDialog class handles the OnBackInvokedDispatcher.
    }

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
                if (IS_YOUTUBE) {
                    dialog.dismiss();
                } else {
                    // In YouTube Music, the home feed doesn't load when the dialog is closed with [Dialog.dismiss()].
                    // Use [Dialog.onBackPressed()] to close the dialog, even with a 100ms delay.
                    Utils.runOnMainThreadDelayed(() -> closeDialog(customDialog), 100);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "closeFullscreenAd failure", ex);
        }
    }
}