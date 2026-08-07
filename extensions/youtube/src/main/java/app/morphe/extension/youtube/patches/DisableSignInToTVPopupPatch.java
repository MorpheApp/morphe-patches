/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class DisableSignInToTVPopupPatch {

    /**
     * Injection point.
     */
    public static boolean disableSignInToTVPopup() {
        return Settings.DISABLE_SIGN_IN_TO_TV_POPUP.get();
    }

    /**
     * Injection point.
     */
    public static boolean disableConnectYourDevicesPopup() {
        return Settings.DISABLE_CONNECT_YOUR_DEVICES_POPUP.get();
    }
}
