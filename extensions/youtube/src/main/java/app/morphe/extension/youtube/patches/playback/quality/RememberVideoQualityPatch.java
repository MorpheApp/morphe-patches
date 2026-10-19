/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.playback.quality;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.NonNull;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.patches.VideoInformation.*;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import j$.util.Optional;
import kotlin.Unit;

@SuppressWarnings({"rawtypes", "unused"})
public class RememberVideoQualityPatch {

    private static final IntegerSetting videoQualityWifi = Settings.VIDEO_QUALITY_DEFAULT_WIFI;
    private static final IntegerSetting videoQualityMobile = Settings.VIDEO_QUALITY_DEFAULT_MOBILE;
    private static final IntegerSetting shortsQualityWifi = Settings.SHORTS_QUALITY_DEFAULT_WIFI;
    private static final IntegerSetting shortsQualityMobile = Settings.SHORTS_QUALITY_DEFAULT_MOBILE;

    static {
        ShortsPlayerState.getOnChange().addObserver((Boolean isOpen) -> {
            if (!isOpen) {
                // A regular video that stayed loaded while a Short played is not started again,
                // so it would otherwise be given the quality of the Short.
                VideoInformation.setDesiredVideoResolution(getDefaultQualityResolution());
            }
            return Unit.INSTANCE;
        });
    }

    public static boolean shouldRememberVideoQuality() {
        BooleanSetting preference = ShortsPlayerState.isOpen()
                ? Settings.REMEMBER_SHORTS_QUALITY_LAST_SELECTED
                : Settings.REMEMBER_VIDEO_QUALITY_LAST_SELECTED;
        return preference.get();
    }

    public static int getDefaultQualityResolution() {
        final boolean isShorts = ShortsPlayerState.isOpen();
        IntegerSetting preference = Utils.getNetworkType() == Utils.NetworkType.MOBILE
                ? (isShorts ? shortsQualityMobile : videoQualityMobile)
                : (isShorts ? shortsQualityWifi : videoQualityWifi);
        return preference.get();
    }

    public static void saveDefaultQuality(int qualityResolution) {
        final boolean shortPlayerOpen = ShortsPlayerState.isOpen();
        final boolean isMobile = Utils.getNetworkType() == Utils.NetworkType.MOBILE;
        IntegerSetting qualitySetting;
        if (isMobile) {
            qualitySetting = shortPlayerOpen ? shortsQualityMobile : videoQualityMobile;
        } else {
            qualitySetting = shortPlayerOpen ? shortsQualityWifi : videoQualityWifi;
        }

        if (qualitySetting.get() == qualityResolution) {
            // User clicked the same video quality as the current video,
            // or changed between 1080p Premium and non-Premium.
            return;
        }
        qualitySetting.save(qualityResolution);

        if (Settings.REMEMBER_VIDEO_QUALITY_LAST_SELECTED_TOAST.get()) {
            String qualityLabel = qualityResolution + "p";
            final String toastStringId = getString(shortPlayerOpen, isMobile);
            Utils.showToastShort(str(toastStringId, qualityLabel));
        }
    }

    @NonNull
    private static String getString(boolean shortPlayerOpen, boolean isMobile) {
        final String toastStringId;
        if (shortPlayerOpen && isMobile) {
            toastStringId = "morphe_remember_video_quality_toast_shorts_mobile";
        } else if (shortPlayerOpen) {
            toastStringId = "morphe_remember_video_quality_toast_shorts_wifi";
        } else if (isMobile) {
            toastStringId = "morphe_remember_video_quality_toast_mobile";
        } else {
            toastStringId = "morphe_remember_video_quality_toast_wifi";
        }
        return toastStringId;
    }

    /**
     * Injection point.
     * <p>
     * Called when a video is opened, before it starts.
     *
     * @return If the video must not be opened, always false.
     */
    public static boolean onVideoIntentLoaded(Map<Object, Object> playbackStartDescriptorMap, String videoId) {
        VideoInformation.onNewVideoIntent(videoId);
        return false;
    }

    /**
     * Injection point.
     * <p>
     * Overrides the initial video quality to not follow the 'Video quality preferences' in YouTube settings.
     * (e.g. 'Auto (recommended)' - 360p/480p, 'Higher picture quality' - 720p/1080p...)
     * If the maximum video quality available is 1080p and the default video quality is 2160p,
     * 1080p is used as an initial video quality.
     * <p>
     * Called before {@link #newVideoStarted(VideoInformation.PlaybackController)}.
     */
    public static Optional getInitialVideoQuality(Optional optional) {
        // YouTube can reload the video after the user selects a quality, such as when
        // the selected quality needs another codec. The reload has no initial quality,
        // or a copy of the default quality used when the video started.
        // The video ID of the player is updated only once the video starts, so the selected
        // quality is not used once the player response of another video is received,
        // such as the next video of the autoplay.
        if (VideoInformation.isQualitySelectedByUser()
                && VideoInformation.getPlayerResponseVideoId().equals(VideoInformation.getStartingOrPlayingVideoId())) {
            int selectedRes = VideoInformation.getUserSelectedQualityResolution();
            Logger.printDebug(() -> "initialVideoQuality selected by the user: " + selectedRes);
            return selectedRes > 0
                    ? Optional.of(selectedRes)
                    : optional;
        }

        int preferredQuality = getDefaultQualityResolution();
        if (preferredQuality != VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE) {
            Logger.printDebug(() -> "initialVideoQuality: " + preferredQuality);
            return Optional.of(preferredQuality);
        }
        return optional;
    }

    /**
     * Injection point.
     *
     * @param userSelectedQualityIndex Element index of {@link VideoInformation#getCurrentQualities()}.
     */
    public static void userChangedShortsQuality(int userSelectedQualityIndex) {
        try {
            VideoQualityInterface[] currentQualities = VideoInformation.getCurrentQualities();
            if (currentQualities == null) {
                Logger.printDebug(() -> "Cannot save default quality, qualities is null");
                // The resolution is not known, so a reload uses the quality saved by YouTube.
                VideoInformation.userSelectedQuality(VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE);
                return;
            }
            VideoQualityInterface quality = currentQualities[userSelectedQualityIndex];
            int resolution = quality.patch_getResolution();
            VideoInformation.userSelectedQuality(resolution);

            if (shouldRememberVideoQuality()) {
                saveDefaultQuality(resolution);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "userChangedShortsQuality failure", ex);
        }
    }

    /**
     * Injection point. Regular videos.
     *
     * @param quality Quality selected by the user.
     */
    public static void userChangedQuality(VideoQualityInterface quality) {
        userChangedQuality(quality.patch_getResolution());
    }

    /**
     * @param videoResolution Human-readable resolution: 480, 720, 1080.
     */
    public static void userChangedQuality(int videoResolution) {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "User changed quality to: " + videoResolution);
        VideoInformation.userSelectedQuality(videoResolution);

        if (shouldRememberVideoQuality()) {
            saveDefaultQuality(videoResolution);
        }
    }

    /**
     * Injection point.
     */
    public static void newVideoStarted(VideoInformation.PlaybackController ignoredPlayerController) {
        VideoInformation.setDesiredVideoResolution(getDefaultQualityResolution());
    }

    /**
     * @return If a default quality is set, so YouTube must not choose the quality automatically.
     */
    private static boolean isDefaultQualitySet() {
        return getDefaultQualityResolution() != VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE;
    }

    /**
     * Injection point.
     * <p>
     * If this flag is off, YouTube ignores the initial video quality set by
     * {@link #getInitialVideoQuality(Optional)} and automatically chooses the quality
     * when the video starts, both on the device and on the server with SABR streams.
     *
     * @return If the initial video quality is used as a fixed quality.
     */
    public static boolean useInitialVideoQuality(boolean original) {
        return original || isDefaultQualitySet();
    }

    /**
     * Injection point.
     *
     * <p>If this flag is off, the minimum and maximum quality allowed by the player
     * are not sent with the SABR requests, and the server can choose another quality.
     */
    public static boolean sendQualityConstraintToServer(boolean original) {
        return original || isDefaultQualitySet();
    }

    /**
     * Injection point.
     * <p>
     * Overrides the 'Video quality preferences' in YouTube settings with the setting
     * used when the user selects a quality from the quality menu, so neither the device
     * nor the SABR server choose the quality automatically (such as with 'Data saver').
     *
     * @return If the 'Advanced' video quality setting is used.
     */
    public static boolean useAdvancedVideoQualitySetting() {
        return isDefaultQualitySet();
    }
}
