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

import java.util.List;

import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation", "RedundantSuppression"})
public class DisableVideoCodecsPatch {

    public enum VideoCodec {
        DEFAULT,
        VP9,
        AVC
    }

    private static final VideoCodec VIDEO_CODEC = resolveVideoCodec();

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
     * @return If AVC can be forced, which needs spoofing to be off or a spoof client that has AVC formats.
     */
    public static boolean isAvcAvailable() {
        return !SpoofVideoStreamsPatch.isPatchIncluded()
                || !SharedYouTubeSettings.SPOOF_VIDEO_STREAMS.get() || List.of(
                ClientType.ANDROID_CREATOR,
                ClientType.ANDROID_VR_DASH,
                ClientType.ANDROID_VR_SABR,
                ClientType.VISIONOS_1_02).contains(Settings.SPOOF_VIDEO_STREAMS_CLIENT_TYPE.get());
    }

    private static VideoCodec resolveVideoCodec() {
        VideoCodec codec = Settings.VIDEO_CODEC.get();
        if (codec == VideoCodec.AVC && !isAvcAvailable()) {
            return VideoCodec.VP9;
        }
        return codec;
    }

    /**
     * @return The codec in use, which is VP9 if AVC is selected but cannot be forced.
     */
    public static VideoCodec getVideoCodec() {
        return VIDEO_CODEC;
    }

    /**
     * Injection point.
     */
    public static boolean allowVP9() {
        return VIDEO_CODEC != VideoCodec.AVC;
    }

    /**
     * Injection point.
     */
    public static boolean allowCodec(String mimeType) {
        return !(VIDEO_CODEC == VideoCodec.VP9 && "video/av01".equals(mimeType));
    }
}
