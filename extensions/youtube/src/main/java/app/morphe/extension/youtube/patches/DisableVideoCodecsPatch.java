/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/2634
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import android.os.Build;
import android.view.Display;

import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation", "RedundantSuppression"})
public class DisableVideoCodecsPatch {

    private static final int[] EMPTY_ARRAY_INT = new int[0];

    /**
     * HDR types YouTube checks via {@link Display.HdrCapabilities#getSupportedHdrTypes()}.
     * HLG is the type HyperOS often omits even when the decoder can play YouTube HDR.
     */
    private static final int[] HDR_TYPES = getHdrTypes();

    /**
     * @return Equivalent of Display.HdrCapabilities.HDR_TYPES
     */
    private static int[] getHdrTypes() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return new int[]{
                    Display.HdrCapabilities.HDR_TYPE_HLG,
                    Display.HdrCapabilities.HDR_TYPE_HDR10,
                    Display.HdrCapabilities.HDR_TYPE_HDR10_PLUS,
                    Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
            };
        }

        return new int[]{
                Display.HdrCapabilities.HDR_TYPE_HLG,
                Display.HdrCapabilities.HDR_TYPE_HDR10,
                Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION
        };
    }

    /**
     * Injection point.
     */
    public static int[] overrideSupportedHdrTypes(Display.HdrCapabilities capabilities) {
        if (Settings.DISABLE_HDR_VIDEO.get()) {
            return EMPTY_ARRAY_INT;
        }

        int[] original = capabilities == null
                ? EMPTY_ARRAY_INT
                : capabilities.getSupportedHdrTypes();
        if (!Settings.FORCE_HDR_VIDEO.get()) {
            return original;
        }

        return unionHdrTypes(original);
    }

    private static int[] unionHdrTypes(int[] original) {
        if (original.length == 0) {
            return HDR_TYPES.clone();
        }

        int missingCount = 0;
        for (int forced : HDR_TYPES) {
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
        for (int forced : HDR_TYPES) {
            if (!containsHdrType(original, forced)) {
                result[index++] = forced;
            }
        }
        return result;
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
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

