/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4757
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import app.morphe.extension.music.settings.Settings;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.IntegerSetting;

@SuppressWarnings("unused")
public final class RememberVideoQualityPatch {

    /**
     * Interface to use obfuscated methods.
     */
    public interface VideoQualityInterface {
        // Method is added during patching.
        int patch_getResolution();
    }

    /**
     * Interface to use obfuscated methods.
     */
    public interface VideoQualityMenuInterface {
        // Method is added during patching.
        void patch_setQuality(int resolution);
    }

    /**
     * Video resolution of the automatic quality option.
     */
    private static final int AUTOMATIC_VIDEO_QUALITY_VALUE = -2;

    /**
     * Video ID of the video playing.
     */
    private static volatile String currentVideoId = "";

    /**
     * If the default quality is set when the qualities of the video playing are available.
     */
    private static volatile boolean qualityNeedsUpdating;

    /**
     * Injection point.
     * <p>
     * Called when the video ID of the player changes.
     */
    public static void newVideoStarted(@Nullable String videoId) {
        if (videoId == null || videoId.equals(currentVideoId)) {
            return;
        }

        currentVideoId = videoId;
        qualityNeedsUpdating = true;
    }

    private static int getDefaultQualityResolution() {
        IntegerSetting setting = Utils.getNetworkType() == Utils.NetworkType.MOBILE
                ? Settings.VIDEO_QUALITY_DEFAULT_MOBILE
                : Settings.VIDEO_QUALITY_DEFAULT_WIFI;
        return setting.get();
    }

    /**
     * Injection point.
     * <p>
     * Called when the format of the video stream changes.
     *
     * @param qualities            Qualities of the quality menu, with the automatic quality first.
     * @param menu                 The quality menu.
     * @param originalQualityIndex Index of the quality playing.
     * @return Index of the quality to show as selected in the quality menu.
     */
    public static int setVideoQuality(@Nullable VideoQualityInterface[] qualities,
                                      VideoQualityMenuInterface menu,
                                      int originalQualityIndex) {
        try {
            if (!qualityNeedsUpdating || qualities == null) {
                return originalQualityIndex;
            }

            final int defaultResolution = getDefaultQualityResolution();
            if (defaultResolution == AUTOMATIC_VIDEO_QUALITY_VALUE) {
                qualityNeedsUpdating = false;
                return originalQualityIndex;
            }

            // The highest quality that is not higher than the default quality,
            // or the lowest quality if all qualities are higher.
            int qualityIndex = -1;
            int qualityResolution = Integer.MIN_VALUE;
            int lowestQualityIndex = -1;
            int lowestQualityResolution = Integer.MAX_VALUE;

            for (int i = 0; i < qualities.length; i++) {
                final int resolution = qualities[i].patch_getResolution();
                if (resolution <= 0) {
                    // Automatic quality.
                    continue;
                }

                if (resolution <= defaultResolution && resolution > qualityResolution) {
                    qualityIndex = i;
                    qualityResolution = resolution;
                }
                if (resolution < lowestQualityResolution) {
                    lowestQualityIndex = i;
                    lowestQualityResolution = resolution;
                }
            }

            if (qualityIndex < 0) {
                if (lowestQualityIndex < 0) {
                    // No qualities yet.
                    return originalQualityIndex;
                }
                qualityIndex = lowestQualityIndex;
                qualityResolution = lowestQualityResolution;
            }

            qualityNeedsUpdating = false;

            // The quality is set even if it is playing,
            // so the player does not change the quality automatically.
            final int resolution = qualityResolution;
            Logger.printDebug(() -> "Changing quality to default: " + resolution + "p");
            menu.patch_setQuality(resolution);

            return qualityIndex;
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoQuality failure", ex);
        }

        return originalQualityIndex;
    }

    /**
     * Injection point.
     * <p>
     * Called when the user selects a quality in the quality menu.
     *
     * @param resolution Resolution of the quality selected, or -2 for the automatic quality.
     */
    public static void userChangedQuality(int resolution) {
        try {
            Logger.printDebug(() -> "User changed quality to: " + resolution);

            // The quality selected for the video playing is kept.
            qualityNeedsUpdating = false;

            if (Settings.REMEMBER_VIDEO_QUALITY_LAST_SELECTED.get()) {
                saveDefaultQuality(resolution);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "userChangedQuality failure", ex);
        }
    }

    private static void saveDefaultQuality(int resolution) {
        final boolean isMobile = Utils.getNetworkType() == Utils.NetworkType.MOBILE;
        IntegerSetting setting = isMobile
                ? Settings.VIDEO_QUALITY_DEFAULT_MOBILE
                : Settings.VIDEO_QUALITY_DEFAULT_WIFI;
        if (setting.get() == resolution) {
            return;
        }
        setting.save(resolution);

        if (Settings.REMEMBER_VIDEO_QUALITY_LAST_SELECTED_TOAST.get()) {
            String qualityName = resolution == AUTOMATIC_VIDEO_QUALITY_VALUE
                    ? str("morphe_video_quality_default_entry_1")
                    : resolution + "p";
            Utils.showToastShort(str(isMobile
                    ? "morphe_remember_video_quality_toast_mobile"
                    : "morphe_remember_video_quality_toast_wifi", qualityName));
        }
    }
}
