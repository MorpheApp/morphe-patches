/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/2542
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.theme;

import android.app.Activity;
import android.os.Build;

import androidx.annotation.RequiresApi;
import androidx.annotation.StyleRes;

/**
 * Hands the system the theme it draws the splash screen of the app with.
 * <p>
 * The system draws the splash screen before the app runs, and it resolves the resources of the app
 * with the configuration of the device, so the resource variant of the selected background can
 * never be used for it. This is the way the system takes a theme instead, and it uses the one it
 * was given for every launch that follows.
 * <p>
 * Kept in a class of its own so the API 31 classes are never loaded on an older device.
 */
@RequiresApi(api = Build.VERSION_CODES.S)
final class SplashScreenTheme {

    /**
     * @param themeId The theme of the selected background, or zero to let the system draw the
     *                splash screen of the app again.
     */
    static void apply(Activity activity, @StyleRes int themeId) {
        activity.getSplashScreen().setSplashScreenTheme(themeId);
    }

    private SplashScreenTheme() {
    }
}
