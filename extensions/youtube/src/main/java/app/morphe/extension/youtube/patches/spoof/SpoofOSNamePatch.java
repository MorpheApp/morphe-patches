/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.spoof;

import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.Utils;

public class SpoofOSNamePatch {
    @NonNull
    private static String osName = "";

    private static String getOSName() {
        if (osName.isEmpty()) {
            PackageManager pm = Utils.getContext().getPackageManager();
            if (pm.hasSystemFeature("android.hardware.type.watch")) {
                osName = "Android Wear";
            } else if (pm.hasSystemFeature("android.hardware.type.automotive")) {
                osName = "Android Automotive";
            } else if (pm.hasSystemFeature("org.chromium.arc")) {
                osName = "ChromeOS";
            } else {
                osName = "Android";
            }
        }

        return osName;
    }

    public static String getOSName(boolean enabled) {
        return enabled ? "Android Automotive" : getOSName();
    }
}
