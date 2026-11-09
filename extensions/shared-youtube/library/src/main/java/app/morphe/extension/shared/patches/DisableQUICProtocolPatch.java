/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;

@SuppressWarnings("unused")
public class DisableQUICProtocolPatch {

    public static boolean disableQUICProtocol(boolean original) {
        boolean isDisabled = SharedYouTubeSettings.DISABLE_QUIC_PROTOCOL.get();
        return !isDisabled && original;
    }
}
