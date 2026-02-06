package app.morphe.extension.youtube.patches.playback.quality;

import static app.morphe.extension.shared.Utils.isNotEmpty;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.FormatOuterClass.Format;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class PrioritizeVideoQualityPatch {
    private static final boolean PRIORITIZE_VIDEO_QUALITY = Settings.PRIORITIZE_VIDEO_QUALITY.get();

    /**
     * Injection point.
     * <p>
     * Some videos have the following video codecs:
     * 1. 1080p AVC
     * 2. 720p AVC
     * 3. 360p VP9
     * <p>
     * If the device supports VP9, 1080p AVC and 720p AVC are ignored,
     * and 360p VP9 is used as the highest video quality.
     * This is the intended behavior of YouTube,
     * which is why the video quality flyout menu is unavailable for some videos.
     * <p>
     * Although VP9 is a more advanced codec than AVC, using 1080p AVC is better than using 360p VP9.
     * <p>
     * This function removes all VP9 codecs if the highest resolution video codec is AVC.
     */
    public static List<Object> prioritizeVideoQuality(@Nullable String videoId, @NonNull List<Object> adaptiveFormats) {
        if (PRIORITIZE_VIDEO_QUALITY && isNotEmpty(videoId) && !"zzzzzzzzzzz".equals(videoId)) {
            try {
                int maxHeightAVC = -1;
                int maxHeightVP9 = -1;
                for (Object o : adaptiveFormats) {
                    if (o instanceof MessageLite m) {
                        var adaptiveFormat = Format.parseFrom(m.toByteArray());
                        if (adaptiveFormat != null) {
                            String mimeType = adaptiveFormat.getMimeType();
                            if (mimeType != null && mimeType.contains("video")) {
                                int height = adaptiveFormat.getHeight();
                                if (mimeType.contains("avc")) {
                                    maxHeightAVC = Math.max(maxHeightAVC, height);
                                } else if (mimeType.contains("vp9")) {
                                    maxHeightVP9 = Math.max(maxHeightVP9, height);
                                }
                                if (maxHeightAVC != -1 && maxHeightVP9 != -1) {
                                    break;
                                }
                            }
                        }
                    }
                }

                boolean shouldRemoveVP9 = maxHeightAVC > 0 && maxHeightVP9 > 0 && maxHeightVP9 < maxHeightAVC;

                if (shouldRemoveVP9) {
                    ArrayList<Object> newFormats = new ArrayList<>(adaptiveFormats.size());

                    for (Object adaptiveFormat : adaptiveFormats) {
                        if (adaptiveFormat instanceof MessageLite messageLite) {
                            var parsedAdaptiveFormat = Format.parseFrom(messageLite.toByteArray());
                            if (parsedAdaptiveFormat != null) {
                                String mimeType = parsedAdaptiveFormat.getMimeType();
                                boolean isVideoType = mimeType != null && mimeType.contains("video");

                                if (!isVideoType || !mimeType.contains("vp9")) {
                                    newFormats.add(adaptiveFormat);
                                }
                            }
                        }
                    }

                    return newFormats;
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to sort adaptive formats", ex);
            }
        }

        return adaptiveFormats;
    }
}