/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.quality;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.stream.Collectors;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.patches.VideoFormat.FormatInterface;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class PrioritizeVideoQualityPatch {
    /**
     * Generally, 'height', 'qualityLabel', and 'qualityOrdinal' have consistent values.
     * e.g. height: 240, qualityLabel: 240p, qualityOrdinal: QUALITY_ORDINAL_240P
     * <p>
     * Sometimes, they may have inconsistent values.
     * e.g. height: 288, qualityLabel: 240p, qualityOrdinal: QUALITY_ORDINAL_360P
     * These video formats are fixed by {@link VideoInformation#fixVideoQualityResolution(String, int)}.
     * <p>
     * If AVC is the only available video codec and only inconsistent values exist, SABR playback is not starting.
     * See: <a href="https://github.com/MorpheApp/morphe-patches/issues/2713">morphe-patches#2713</a>.
     */
    private static final List<Integer> AVAILABLE_FORMAT_HEIGHT = List.of(
            // YouTube mobile app does not support 4320p.
            // 4320,
            2160, 1440, 1080, 720,
            480, 360, 240, 144
    );

    private static final boolean PRIORITIZE_VIDEO_QUALITY = Settings.VIDEO_QUALITY_PRIORITIZE.get();

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
    public static List<FormatInterface> prioritizeVideoQuality(@NonNull String videoId, @NonNull List<FormatInterface> adaptiveFormats) {
        if (PRIORITIZE_VIDEO_QUALITY) {
            try {
                int maxHeightAVC = -1;
                int maxHeightVP9 = -1;
                for (FormatInterface format : adaptiveFormats) {
                    String mimeType = format.patch_getMimeType();
                    if (mimeType == null || !mimeType.contains("video")) {
                        continue;
                    }
                    int height = format.patch_getHeight();
                    if (!AVAILABLE_FORMAT_HEIGHT.contains(height)) {
                        continue;
                    }
                    if (mimeType.contains("avc")) {
                        maxHeightAVC = Math.max(maxHeightAVC, height);
                    } else if (mimeType.contains("vp9")) {
                        maxHeightVP9 = Math.max(maxHeightVP9, height);
                    }
                    if (maxHeightAVC != -1 && maxHeightVP9 != -1) {
                        break;
                    }
                }

                final int finalMaxHeightAVC = maxHeightAVC;
                final int finalMaxHeightVP = maxHeightVP9;
                final boolean shouldRemoveVP9 = finalMaxHeightVP > -1 && finalMaxHeightVP < finalMaxHeightAVC;
                Logger.printDebug(() -> "videoId: " + videoId + ", maxHeightAVC: " + finalMaxHeightAVC +
                        ", maxHeightVP9: " + finalMaxHeightVP + ", shouldRemoveVP9: " + shouldRemoveVP9);

                if (shouldRemoveVP9) {
                    return adaptiveFormats.stream()
                            .filter(format -> {
                                String mimeType = format.patch_getMimeType();
                                return mimeType == null || !mimeType.contains("video") || !mimeType.contains("vp9");
                            })
                            .collect(Collectors.toList());
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Failed to sort adaptive formats", ex);
            }
        }

        return adaptiveFormats;
    }
}
