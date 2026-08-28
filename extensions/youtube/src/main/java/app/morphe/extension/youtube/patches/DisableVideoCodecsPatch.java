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

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation", "RedundantSuppression"})
public class DisableVideoCodecsPatch {

    /**
     * HDR types YouTube checks via {@link Display.HdrCapabilities#getSupportedHdrTypes()}.
     * HLG is the type HyperOS often omits even when the decoder can play YouTube HDR.
     */
    private static final int[] FORCED_HDR_TYPES = {
            Display.HdrCapabilities.HDR_TYPE_HLG,
            Display.HdrCapabilities.HDR_TYPE_HDR10,
            Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
    };

    /**
     * Injection point.
     */
    public static int[] overrideSupportedHdrTypes(Display.HdrCapabilities capabilities) {
        if (Settings.DISABLE_HDR_VIDEO.get()) {
            return new int[0];
        }

        int[] original = capabilities == null
                ? new int[0]
                : capabilities.getSupportedHdrTypes();
        if (!Settings.FORCE_HDR_VIDEO.get()) {
            return original;
        }

        return unionHdrTypes(original);
    }

    private static int[] unionHdrTypes(int[] original) {
        if (original == null || original.length == 0) {
            return FORCED_HDR_TYPES.clone();
        }

        int missingCount = 0;
        for (int forced : FORCED_HDR_TYPES) {
            if (!containsHdrType(original, forced)) {
                missingCount++;
            }
        }
        if (missingCount == 0) {
            return original;
        }

        int[] result = new int[original.length + missingCount];
        System.arraycopy(original, 0, result, 0, original.length);
        int index = original.length;
        for (int forced : FORCED_HDR_TYPES) {
            if (!containsHdrType(original, forced)) {
                result[index++] = forced;
            }
        }
        return result;
    }

    private static boolean containsHdrType(int[] types, int type) {
        for (int existing : types) {
            if (existing == type) {
                return true;
            }
        }
        return false;
    }

    /**
     * Injection point.
     */
    public static boolean allowVP9() {
        return !Settings.FORCE_AVC_CODEC.get();
    }
}

