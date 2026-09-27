/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3291
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.pm.ActivityInfo;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;

@SuppressWarnings("unused")
public class ForcePortraitOrientationPatch {

    /**
     * Injection point.
     */
    @SuppressLint("SourceLockedOrientationActivity")
    public static void setRequestedOrientation(Activity activity) {
        try {
            if (Settings.FORCE_PORTRAIT_ORIENTATION.get()) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "setRequestedOrientation failure", ex);
        }
    }
}
