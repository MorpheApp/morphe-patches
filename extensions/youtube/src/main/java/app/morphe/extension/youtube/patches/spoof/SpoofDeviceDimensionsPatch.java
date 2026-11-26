package app.morphe.extension.youtube.patches.spoof;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import androidx.annotation.Nullable;

import java.util.Locale;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public class SpoofDeviceDimensionsPatch {
    private static final boolean SPOOF = Settings.SPOOF_DEVICE_DIMENSIONS.get();

    /**
     * Video types whose hardware decoder sizes the stream selection of the app checks.
     */
    private static final String[] VIDEO_MIME_TYPES = {
            "video/x-vnd.on2.vp9", "video/avc", "video/av01"
    };

    /**
     * Name prefixes of the software decoders, for Android versions that cannot tell.
     */
    private static final String[] SOFTWARE_DECODER_PREFIXES = {
            "omx.google.", "c2.android.", "c2.google."
    };

    private static final int FULL_HD_HEIGHT = 1080;
    private static final int FULL_HD_WIDTH = 1920;

    @Nullable
    private static Boolean videoDecoderSizeLimited;

    /**
     * Injection point.
     */
    public static int getMinHeightOrWidth(int minHeightOrWidth) {
        return SPOOF ? 64 : minHeightOrWidth;
    }

    /**
     * Injection point.
     */
    public static int getMaxHeightOrWidth(int maxHeightOrWidth) {
        return SPOOF ? 4096 : maxHeightOrWidth;
    }

    /**
     * If the app uses only the hardware decoders, it drops the streams larger than the decoder
     * supports. This checks the first hardware decoder of each video type, which is what the app
     * normally uses, so it can be wrong if the app picks another decoder.
     *
     * @return If a hardware video decoder supports less than 1080p, which this patch can unlock.
     */
    public static boolean isVideoDecoderSizeLimited() {
        if (videoDecoderSizeLimited == null) {
            videoDecoderSizeLimited = findVideoDecoderSizeLimit();
        }
        return videoDecoderSizeLimited;
    }

    private static boolean findVideoDecoderSizeLimit() {
        MediaCodecInfo[] codecs = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();

        for (String mimeType : VIDEO_MIME_TYPES) {
            for (MediaCodecInfo codec : codecs) {
                if (codec.isEncoder() || !isHardwareDecoder(codec) || !supportsType(codec, mimeType)) {
                    continue;
                }

                MediaCodecInfo.VideoCapabilities capabilities =
                        codec.getCapabilitiesForType(mimeType).getVideoCapabilities();
                final int maxHeight = capabilities.getSupportedHeights().getUpper();
                final int maxWidth = capabilities.getSupportedWidths().getUpper();
                if (maxHeight < FULL_HD_HEIGHT || maxWidth < FULL_HD_WIDTH) {
                    Logger.printDebug(() -> "Video decoder size is limited: " + codec.getName()
                            + " max width: " + maxWidth + " max height: " + maxHeight);
                    return true;
                }
                break;
            }
        }

        return false;
    }

    private static boolean isHardwareDecoder(MediaCodecInfo codec) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return codec.isHardwareAccelerated() && !codec.isAlias();
        }

        String name = codec.getName().toLowerCase(Locale.US);
        for (String prefix : SOFTWARE_DECODER_PREFIXES) {
            if (name.startsWith(prefix)) {
                return false;
            }
        }
        return true;
    }

    private static boolean supportsType(MediaCodecInfo codec, String mimeType) {
        for (String type : codec.getSupportedTypes()) {
            if (type.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        return false;
    }
}
