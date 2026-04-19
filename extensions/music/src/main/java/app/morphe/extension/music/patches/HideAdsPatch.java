/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.ByteTrieSearch.convertStringsToBytes;

import android.app.Dialog;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.ByteTrieSearch;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.Utils;

@SuppressWarnings("unused")
public class HideAdsPatch {

    private static final ByteTrieSearch FULLSCREEN_AD_SEARCH = new ByteTrieSearch(
            convertStringsToBytes("_interstitial")
    );

    /**
     * Injection point.
     */
    public static boolean hideGetPremiumLabel() {
        return Settings.HIDE_GET_PREMIUM_LABEL.get();
    }

    /**
     * Injection point.
     */
    // TODO: Extract this into a youtube-shared patch
    public static void closeFullscreenAd(Object customDialog, @Nullable byte[] buffer) {
        try {
            if (!SharedYouTubeSettings.HIDE_FULLSCREEN_ADS.get()) {
                return;
            }

            if (buffer == null) return;

            if (customDialog instanceof Dialog dialog && FULLSCREEN_AD_SEARCH.matches(buffer)) {
                Logger.printDebug(() -> "Closing YT Music fullscreen ad");

                Window window = dialog.getWindow();
                if (window != null) {
                    WindowManager.LayoutParams params = window.getAttributes();
                    params.height = 0;
                    params.width = 0;
                    window.setAttributes(params);
                    window.clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
                    window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED);

                    View decorView = window.getDecorView();
                    decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
                }

                Utils.runOnMainThreadDelayed(dialog::onBackPressed, 100);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "closeFullscreenAd failure", ex);
        }
    }
}