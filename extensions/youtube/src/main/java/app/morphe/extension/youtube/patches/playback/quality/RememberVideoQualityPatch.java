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
import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.innertube.ClientAbrStateOuterClass.ClientAbrState;
import app.morphe.extension.youtube.patches.VideoInformation.*;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.ShortsPlayerState;
import j$.util.Optional;

@SuppressWarnings({"rawtypes", "unused"})
public class RememberVideoQualityPatch {

    private static final IntegerSetting videoQualityWifi = Settings.VIDEO_QUALITY_DEFAULT_WIFI;
    private static final IntegerSetting videoQualityMobile = Settings.VIDEO_QUALITY_DEFAULT_MOBILE;
    private static final IntegerSetting shortsQualityWifi = Settings.SHORTS_QUALITY_DEFAULT_WIFI;
    private static final IntegerSetting shortsQualityMobile = Settings.SHORTS_QUALITY_DEFAULT_MOBILE;

    /**
     * 'Advanced' video quality setting, used when the user selects a quality from the quality menu.
     */
    private static final int VIDEO_QUALITY_SETTING_ADVANCED_MENU = 3;

    /**
     * ID of the video whose quality was selected by the user, and the resolution selected.
     * The video ID is used, because a player reload starts the same video again.
     */
    private static volatile String userSelectedQualityVideoId = "";
    private static volatile int userSelectedQualityResolution = VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE;

    private static void setUserSelectedQuality(int resolution) {
        userSelectedQualityResolution = resolution;
        userSelectedQualityVideoId = VideoInformation.getVideoId();
    }

    /**
     * @return If the user selected the quality of the current video.
     */
    private static boolean isQualitySelectedByUser() {
        String videoId = VideoInformation.getVideoId();
        return !videoId.isEmpty() && videoId.equals(userSelectedQualityVideoId);
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
        if (!userSelectedQualityVideoId.equals(videoId)) {
            // Another video is opened. The video ID of the player is updated only once
            // the video starts, so the quality selected for the current video
            // would otherwise be used when the other video starts.
            userSelectedQualityVideoId = "";
        }
        return false;
    }

    /**
     * Injection point.
     * <p>
     * Overrides the initial video quality to not follow the 'Video quality preferences' in YouTube settings.
     * (e.g. 'Auto (recommended)' - 360p/480p, 'Higher picture quality' - 720p/1080p...)
     * If the maximum video quality available is 1080p and the default video quality is 2160p,
     * 1080p is used as an initial video quality.
     */
    public static Optional getInitialVideoQuality(Optional optional) {
        // YouTube can reload the video after the user selects a quality, such as when
        // the selected quality needs another codec. The reload has no initial quality,
        // or a copy of the default quality used when the video started.
        // The video ID of the player is updated only once the video starts, so the selected
        // quality is not used once the player response of another video is received,
        // such as the next video of the autoplay.
        if (isQualitySelectedByUser() && VideoInformation.getPlayerResponseVideoId()
                .equals(VideoInformation.getVideoId())) {
            final int selectedResolution = userSelectedQualityResolution;
            Logger.printDebug(() -> "initialVideoQuality selected by the user: " + selectedResolution);
            // With 'Auto' selected, YouTube chooses the quality.
            return selectedResolution > 0
                    ? Optional.of(selectedResolution)
                    : Optional.empty();
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
     * <p>
     * The initial video quality is only used when the video starts. While the video plays,
     * the player and the SABR requests use the quality last selected in the quality menu,
     * and without one the quality is chosen automatically (such as 720p for the size of the player).
     * The default quality is used as the quality last selected, until the user selects a quality.
     * With some accounts the quality last selected is used only for the video it was selected for,
     * so the default quality is then used as if it was selected for the current video.
     *
     * @return The resolution to use as the quality last selected,
     *         or -1 to use the quality last selected in the quality menu.
     */
    public static int getLastSelectedVideoQuality() {
        try {
            if (isQualitySelectedByUser()) {
                return -1;
            }

            final int preferredQuality = getDefaultQualityResolution();
            return preferredQuality == VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE
                    ? -1
                    : getAvailableQualityResolution(preferredQuality);
        } catch (Exception ex) {
            Logger.printException(() -> "getLastSelectedVideoQuality failure", ex);
            return -1;
        }
    }

    /**
     * @return The resolution the SABR requests must keep: the quality selected by the user
     *         for the current video, otherwise the default quality.
     *         -1 to send the requests unchanged, with 'Auto' selected or no default quality.
     */
    private static int getStickyVideoQuality() {
        if (isQualitySelectedByUser()) {
            final int selectedResolution = userSelectedQualityResolution;
            return selectedResolution > 0 ? selectedResolution : -1;
        }

        return getLastSelectedVideoQuality();
    }

    /**
     * Injection point.
     * <p>
     * The server chooses the quality of SABR streams from the client ABR state sent with each request.
     * The first request of a video sends the initial video quality as the sticky resolution,
     * but the requests sent while the video plays have none, so the server can switch to another quality,
     * such as a lower one for the size of the player or in power saving mode.
     * The quality is sent as if the user just selected it, in every request.
     *
     * @return The client ABR state with the video quality, or null to keep the original.
     */
    @Nullable
    public static byte[] overrideClientAbrState(@Nullable MessageLite clientAbrState) {
        try {
            if (clientAbrState == null) {
                return null;
            }

            final int resolution = getStickyVideoQuality();
            if (resolution < 0) {
                return null;
            }

            ClientAbrState state = ClientAbrState.parseFrom(clientAbrState.toByteArray());
            ClientAbrState.Builder builder = state.toBuilder()
                    .setStickyResolution(resolution)
                    .setVideoQualitySetting(VIDEO_QUALITY_SETTING_ADVANCED_MENU)
                    .setLastManualSelectedResolution(resolution)
                    .setPowerSaveMode(false);

            if (!state.hasLastManualSelectedResolution()) {
                // No quality was selected in the quality menu, so it was selected just now
                // and it is the quality that is playing.
                builder.setTimeSinceLastManualFormatSelectionMs(0)
                        .setLastManualDirection(0);
            }

            // The server does not choose a quality above the size of the player.
            // The resolution is the short side of the video, so it is compared with the short side of the player.
            if (state.hasClientViewportWidth() && state.hasClientViewportHeight()) {
                final int width = state.getClientViewportWidth();
                final int height = state.getClientViewportHeight();
                final int shortSide = Math.min(width, height);
                if (shortSide > 0 && shortSide < resolution) {
                    builder.setClientViewportWidth((int) Math.ceil((double) width * resolution / shortSide))
                            .setClientViewportHeight((int) Math.ceil((double) height * resolution / shortSide));
                }
            }

            return builder.build().toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "overrideClientAbrState failure", ex);
            return null;
        }
    }

    /**
     * The quality last selected is used as the only quality allowed by the player.
     * If the video does not have it, all lower qualities are allowed and
     * the player chooses one of them automatically, such as 720p for the size of the player.
     *
     * @return The highest quality of the current video that is not above the preferred quality,
     *         or the preferred quality if the qualities of the video are not known yet
     *         or all of them are above it (the player then uses the lowest quality).
     */
    private static int getAvailableQualityResolution(int preferredQuality) {
        VideoQualityInterface[] qualities = VideoInformation.getCurrentQualities();
        if (qualities != null) {
            // Ordered from largest to smallest, with index 0 being 'Auto'.
            for (VideoQualityInterface quality : qualities) {
                final int resolution = quality.patch_getResolution();
                if (resolution != VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE && resolution <= preferredQuality) {
                    return resolution;
                }
            }
        }

        return preferredQuality;
    }

    /**
     * Injection point.
     * @param userSelectedQualityIndex Element index of {@link VideoInformation#getCurrentQualities()}.
     */
    public static void userChangedShortsQuality(int userSelectedQualityIndex) {
        try {
            VideoQualityInterface[] currentQualities = VideoInformation.getCurrentQualities();
            if (currentQualities == null) {
                Logger.printDebug(() -> "Cannot save default quality, qualities is null");
                // The resolution is not known, so a reload uses the quality saved by YouTube.
                setUserSelectedQuality(VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE);
                return;
            }
            final int resolution = currentQualities[userSelectedQualityIndex].patch_getResolution();
            setUserSelectedQuality(resolution);

            if (shouldRememberVideoQuality()) {
                saveDefaultQuality(resolution);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "userChangedShortsQuality failure", ex);
        }
    }

    /**
     * Injection point.  Regular videos.
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
        setUserSelectedQuality(videoResolution);

        if (shouldRememberVideoQuality()) {
            saveDefaultQuality(videoResolution);
        }
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
