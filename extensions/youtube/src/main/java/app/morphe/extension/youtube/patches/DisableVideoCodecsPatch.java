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

import android.view.Display;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation", "RedundantSuppression"})
public class DisableVideoCodecsPatch {

    /**
     * Injection point.
     */
    public static int[] disableHdrVideo(Display.HdrCapabilities capabilities) {
        return Settings.DISABLE_HDR_VIDEO.get()
                ? new int[0]
                : capabilities.getSupportedHdrTypes();
    }

    /**
     * Injection point.
     */
    public static boolean allowVP9() {
        return !Settings.FORCE_AVC_CODEC.get();
    }


    /**
     * Injection point.
     */
    public static boolean useReelForcedAV1HWDecodeFeatureFlag(boolean original) {
        if (original) {
            Logger.printDebug(() -> "useReelForcedAV1HWDecode is set on");
        }

        return Settings.FORCE_AV1_IN_SHORTS.get();
    }
}

