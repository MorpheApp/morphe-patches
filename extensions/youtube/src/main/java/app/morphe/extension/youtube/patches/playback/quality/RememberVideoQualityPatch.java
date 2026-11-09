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

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.youtube.patches.VideoFormat.FormatInterface;
import app.morphe.extension.youtube.patches.VideoInformation;
import app.morphe.extension.youtube.innertube.ClientAbrStateOuterClass.ClientAbrState;
import app.morphe.extension.youtube.patches.VideoInformation.*;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;
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
     * ID of the regular video whose quality was selected by the user, and the resolution selected.
     * The video ID is used, because a player reload starts the same video again.
     */
    private static volatile String regularSelectedQualityVideoId = "";
    private static volatile int regularSelectedQualityResolution = VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE;

    /**
     * ID of the Short whose quality was selected by the user, and the resolution selected.
     * The regular player keeps its video while Shorts play, such as in the miniplayer,
     * so each player keeps the quality selected for its own video.
     */
    private static volatile String shortsSelectedQualityVideoId = "";
    private static volatile int shortsSelectedQualityResolution = VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE;

    /**
     * If a Short was opened, and the regular player did not play its video again since then.
     * The video ID is the one of the regular video until the Short starts, so the quality
     * selected for the regular video is not used meanwhile, otherwise the Short would load with it.
     */
    private static volatile boolean shortsOpened;

    /**
     * ID of the video whose buffered video is cleared once the player sets the quality
     * selected by the user, or an empty string.
     */
    private static volatile String clearVideoBufferVideoId = "";

    /**
     * Playback time in microseconds when the user selected the quality, after which
     * the buffered video is cleared, or -1.
     */
    private static volatile long clearVideoBufferTimeUs = -1;

    /**
     * ID of the video whose buffered segment that was playing when the user selected the quality
     * is cleared once it was played, or an empty string.
     */
    private static volatile String clearPlayedVideoSegmentVideoId = "";

    /**
     * The quality selected in the Shorts quality menu, until the menu sets it.
     */
    @Nullable
    private static volatile VideoQualityInterface shortsMenuSelectedQuality;

    /**
     * Number of videos whose video formats are kept. The next Shorts are loaded before they play.
     */
    private static final int MAX_VIDEO_FORMAT_CODECS = 20;

    /**
     * The codec of each video format by format ID (itag), by video ID.
     */
    private static final Map<String, Map<Integer, String>> videoFormatCodecs = Collections.synchronizedMap(
            Utils.createSizeRestrictedMap(MAX_VIDEO_FORMAT_CODECS, true));

    /**
     * Format ID (itag) of the video format playing.
     */
    private static volatile int currentVideoFormatId;

    static {
        // A quality selected by the user is used for its video until another video plays or its player closes,
        // such as when the video is loaded again after the app returns from the background.
        // A video shown again starts with the default quality.
        ShortsPlayerState.getOnChange().addObserver((Boolean isOpen) -> {
            shortsOpened = isOpen;
            if (!isOpen) {
                clearShortsSelectedQuality();
            }
        });
        VideoInformation.onVideoIdChange.addObserver((String videoId) -> {
            if (!ShortsPlayerState.isOpen() || videoId.equals(regularSelectedQualityVideoId)) {
                // The regular player plays a video, such as its video again when the Shorts player closes.
                shortsOpened = false;
            }
            // Another Short plays, such as after a swipe. The video of the regular player can change
            // for a moment while its video plays, such as for an ad, so only the Shorts player is checked.
            if (ShortsPlayerState.isOpen() && !videoId.equals(shortsSelectedQualityVideoId)) {
                clearShortsSelectedQuality();
            }
        });
        PlayerType.getOnChange().addObserver((PlayerType type) -> {
            if (type == PlayerType.NONE) {
                clearRegularSelectedQuality();
            }
        });
    }

    private static void clearRegularSelectedQuality() {
        regularSelectedQualityVideoId = "";
        clearVideoBufferTimeUs = -1;
    }

    private static void clearShortsSelectedQuality() {
        shortsSelectedQualityVideoId = "";
        clearVideoBufferTimeUs = -1;
    }

    private static void setUserSelectedQuality(int resolution, boolean isShorts) {
        final String videoId = VideoInformation.getVideoId();
        if (isShorts) {
            shortsSelectedQualityResolution = resolution;
            shortsSelectedQualityVideoId = videoId;
        } else {
            regularSelectedQualityResolution = resolution;
            regularSelectedQualityVideoId = videoId;
        }
        clearVideoBufferVideoId = videoId;
        // The playback time is read on the main thread,
        // as the player may set the video formats on another thread.
        final long videoTime = VideoInformation.getVideoTime();
        clearVideoBufferTimeUs = videoTime >= 0 ? videoTime * 1000 : -1;
    }

    /**
     * The quality menu can have qualities the player cannot play, such as a 144p quality
     * that has only AVC formats while a VP9 format plays. The player then has no video to play
     * and reloads the video. A quality without formats is found by the player by its resolution.
     *
     * @return If the quality has a format with the codec of the video format playing,
     *         or if it is not known.
     */
    private static boolean isQualityPlayable(VideoQualityInterface quality) {
        final Map<Integer, String> codecs = videoFormatCodecs.get(VideoInformation.getVideoId());
        if (codecs == null) {
            return true;
        }
        final String currentCodec = codecs.get(currentVideoFormatId);
        final Collection<?> formatIds = quality.patch_getFormatIds();
        if (currentCodec == null || formatIds == null || formatIds.isEmpty()) {
            return true;
        }
        for (Object formatId : formatIds) {
            if (currentCodec.equals(codecs.get(formatId))) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The quality selected, or if the player cannot play it, the closest quality
     *         the player can play between the quality selected and the quality playing.
     */
    public static VideoQualityInterface getPlayableQuality(VideoQualityInterface selectedQuality) {
        try {
            final VideoQualityInterface[] qualities = VideoInformation.getCurrentQualities();
            final VideoQualityInterface currentQuality = VideoInformation.getCurrentQuality();
            final int selectedResolution = selectedQuality.patch_getResolution();
            if (qualities == null || currentQuality == null || selectedResolution <= 0
                    || isQualityPlayable(selectedQuality)) {
                return selectedQuality;
            }

            final boolean selectedHigherQuality = selectedResolution > currentQuality.patch_getResolution();
            VideoQualityInterface playableQuality = selectedQuality;
            // The qualities are ordered from largest to smallest.
            for (VideoQualityInterface quality : qualities) {
                final int resolution = quality.patch_getResolution();
                if (resolution <= 0 || !isQualityPlayable(quality)) {
                    continue;
                }
                if (selectedHigherQuality
                        ? playableQuality == selectedQuality && resolution < selectedResolution
                        : resolution > selectedResolution) {
                    playableQuality = quality;
                }
            }

            final VideoQualityInterface replacement = playableQuality;
            Logger.printDebug(() -> "Selected quality " + selectedQuality
                    + " cannot be played, using: " + replacement);
            return playableQuality;
        } catch (Exception ex) {
            Logger.printException(() -> "getPlayableQuality failure", ex);
            return selectedQuality;
        }
    }

    /**
     * Injection point.
     */
    public static List<FormatInterface> setVideoFormats(@NonNull String videoId, @NonNull List<FormatInterface> adaptiveFormats) {
        try {
            final Map<Integer, String> codecs = new HashMap<>();
            for (FormatInterface format : adaptiveFormats) {
                final String mimeType = format.patch_getMimeType();
                if (mimeType != null && mimeType.startsWith("video")) {
                    // The codec without its profile, such as 'video/mp4; codecs="avc1' for 'video/mp4; codecs="avc1.4d401e"'.
                    final int profileIndex = mimeType.indexOf('.');
                    codecs.put(format.patch_getItag(), profileIndex > 0 ? mimeType.substring(0, profileIndex) : mimeType);
                }
            }
            videoFormatCodecs.put(videoId, codecs);
        } catch (Exception ex) {
            Logger.printException(() -> "setVideoFormats failure", ex);
        }
        return adaptiveFormats;
    }

    /**
     * Injection point.
     *
     * @param formatId Format ID (itag) of the video format playing.
     */
    public static void setCurrentVideoFormat(int formatId) {
        currentVideoFormatId = formatId;
    }

    /**
     * @return The resolution the user selected for the current video in the player that plays it,
     *         or null if the user did not select the quality of the current video.
     */
    @Nullable
    private static Integer getUserSelectedQuality() {
        final String videoId = VideoInformation.getVideoId();
        if (videoId.isEmpty()) {
            return null;
        }
        if (ShortsPlayerState.isOpen() && videoId.equals(shortsSelectedQualityVideoId)) {
            return shortsSelectedQualityResolution;
        }
        if (!shortsOpened && videoId.equals(regularSelectedQualityVideoId)) {
            return regularSelectedQualityResolution;
        }
        return null;
    }

    /**
     * YouTube can load the current video again, such as after the user selects a quality
     * that needs another codec, or when the app returns from the background.
     * The video ID of the player is updated only once the video starts, so the selected
     * quality is not used once the player response of another video is received,
     * such as the next video of the autoplay.
     * The Shorts player starts the next Short before its player response is received,
     * so a quality selected in the Shorts quality menu is not used, otherwise the next Short
     * would start with it. The Short it was selected for keeps it with the formats saved
     * with the quality last selected (see {@link #useSelectedQualityFormats(boolean)}).
     *
     * @return If the quality selected by the user for the regular video is used when the video loads again.
     */
    private static boolean useUserSelectedQualityOnReload() {
        final String videoId = VideoInformation.getVideoId();
        return !shortsOpened && !videoId.isEmpty() && videoId.equals(regularSelectedQualityVideoId)
                && VideoInformation.getPlayerResponseVideoId().equals(videoId);
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
            Utils.showSnackbarShort(str(toastStringId, qualityResolution + "p"));
        }
    }

    /**
     * Injection point.
     * <p>
     * Called when a video is opened, before it starts.
     *
     * @return If the video must not be opened, always false.
     */
    public static boolean onVideoIntentLoaded(Map<Object, Object> playbackStartDescriptorMap, String videoId) {
        if (!regularSelectedQualityVideoId.equals(videoId)) {
            // Another video is opened. The video ID of the player is updated only once
            // the video starts, so the quality selected for the current video
            // would otherwise be used when the other video starts.
            clearRegularSelectedQuality();
        }
        return false;
    }

    /**
     * Injection point.
     * <p>
     * Called when a Short is opened, before it starts. The regular player keeps its video
     * and the quality selected for it, such as when the video is in the miniplayer.
     *
     * @return If the Short must not be opened, always false.
     */
    public static boolean onShortsIntentLoaded(Map<Object, Object> playbackStartDescriptorMap, String videoId) {
        shortsOpened = true;
        if (!shortsSelectedQualityVideoId.equals(videoId)) {
            clearShortsSelectedQuality();
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
        if (useUserSelectedQualityOnReload()) {
            final int selectedResolution = regularSelectedQualityResolution;
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
     * The player loads the video again with the playback start parameters it was started with,
     * such as when the app returns from the background with background playback off.
     * Their initial video quality is the one used when the video started
     * (see {@link #getInitialVideoQuality(Optional)}), so the quality selected by the user
     * for the video is used instead.
     * <p>
     * The Shorts player also loads the next Shorts before they play, while the current Short
     * is still the video of the player, so a quality selected in the Shorts quality menu
     * is used only by the player that loads the Short it was selected for.
     * The regular player can load its video again after Shorts played, such as when its video
     * was in the miniplayer, while the player response is still the one of the last Short,
     * so the video it loads is checked too.
     *
     * @param resolution Initial video quality of the playback start parameters, or null.
     * @param videoId    Video the player loads, or null if not known.
     * @return The initial video quality to use, or null if YouTube chooses the quality.
     */
    @Nullable
    public static Integer getPlaybackStartVideoQuality(@Nullable Integer resolution, @Nullable String videoId) {
        final int selectedResolution;
        if (videoId != null && !videoId.isEmpty() && videoId.equals(shortsSelectedQualityVideoId)) {
            selectedResolution = shortsSelectedQualityResolution;
        } else if (videoId != null
                ? !videoId.isEmpty() && videoId.equals(regularSelectedQualityVideoId)
                : !ShortsPlayerState.isOpen() && useUserSelectedQualityOnReload()) {
            selectedResolution = regularSelectedQualityResolution;
        } else {
            return resolution;
        }

        Logger.printDebug(() -> "Playback start video quality selected by the user: " + selectedResolution);
        // With 'Auto' selected, YouTube chooses the quality.
        return selectedResolution > 0 ? selectedResolution : null;
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
            if (getUserSelectedQuality() != null) {
                return -1;
            }

            final int preferredQuality = getDefaultQualityResolution();
            if (preferredQuality == VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE) {
                return -1;
            }

            // The quality last selected is used as the only quality allowed by the player.
            // If the video does not have it, all lower qualities are allowed and
            // the player chooses one of them automatically, such as 720p for the size of the player.
            // So the highest quality of the current video that is not above the default quality
            // is used, or the default quality if the qualities of the video are not known yet
            // or all of them are above it (the player then uses the lowest quality).
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
        } catch (Exception ex) {
            Logger.printException(() -> "getLastSelectedVideoQuality failure", ex);
            return -1;
        }
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

            // The requests keep the quality selected by the user for the current video,
            // otherwise the default quality. They are sent unchanged with 'Auto' selected
            // or no default quality.
            final int resolution;
            final Integer selectedResolution = getUserSelectedQuality();
            if (selectedResolution != null) {
                resolution = selectedResolution > 0 ? selectedResolution : -1;
            } else {
                resolution = getLastSelectedVideoQuality();
            }
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
     * Injection point.
     * <p>
     * The quality menu of Shorts is also the quality menu of regular videos.
     *
     * @param menuQualities            Qualities of the quality menu, with index 0 being 'Auto', or null if not known.
     *                                 The menu keeps the qualities it was opened with, which can differ from
     *                                 {@link VideoInformation#getCurrentQualities()}, such as when YouTube removes
     *                                 a quality the player cannot play after the menu was opened.
     * @param userSelectedQualityIndex Element index of the quality selected in the menu qualities.
     * @return The element index of the quality to set (see {@link #getPlayableQuality(VideoQualityInterface)}).
     */
    public static int userChangedShortsQuality(@Nullable VideoQualityInterface[] menuQualities,
                                               int userSelectedQualityIndex) {
        try {
            if (menuQualities == null || userSelectedQualityIndex < 0
                    || userSelectedQualityIndex >= menuQualities.length) {
                Logger.printDebug(() -> "Cannot save default quality, the quality selected is not known");
                // The resolution is not known, so a reload uses the quality saved by YouTube.
                setUserSelectedQuality(VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE, ShortsPlayerState.isOpen());
                clearShortsMenuSelectedQuality();
                return userSelectedQualityIndex;
            }
            final VideoQualityInterface selectedQuality = menuQualities[userSelectedQualityIndex];
            VideoQualityInterface quality = getPlayableQuality(selectedQuality);
            int qualityIndex = userSelectedQualityIndex;
            if (quality != selectedQuality) {
                // The quality found is one of the current qualities, so it is found in the menu by its name.
                qualityIndex = -1;
                final String qualityName = quality.patch_getQualityName();
                for (int i = 0; i < menuQualities.length; i++) {
                    if (qualityName.equals(menuQualities[i].patch_getQualityName())) {
                        qualityIndex = i;
                        break;
                    }
                }
                if (qualityIndex < 0) {
                    quality = selectedQuality;
                }
            }
            final int resolution = quality.patch_getResolution();
            setUserSelectedQuality(resolution, ShortsPlayerState.isOpen());
            if (resolution == VideoInformation.AUTOMATIC_VIDEO_QUALITY_VALUE) {
                // 'Auto' has no formats, so the menu sets it by its resolution.
                clearShortsMenuSelectedQuality();
            } else {
                shortsMenuSelectedQuality = quality;
            }

            if (shouldRememberVideoQuality()) {
                saveDefaultQuality(resolution);
            }
            return qualityIndex < 0 ? userSelectedQualityIndex : qualityIndex;
        } catch (Exception ex) {
            Logger.printException(() -> "userChangedShortsQuality failure", ex);
            return userSelectedQualityIndex;
        }
    }

    /**
     * Injection point.
     * <p>
     * The Shorts quality menu sets the selected quality by its resolution, which YouTube can get
     * wrong, such as 720 for the 480p quality of some Shorts (see
     * {@link VideoInformation#fixVideoQualityResolution(String, int)}). The player then plays
     * another quality. The player finds a quality by its formats, so the quality selected in
     * the menu is set instead, as with the quality menu of regular videos.
     *
     * @param menu The object the Shorts quality menu sets the quality with.
     * @return If the quality selected was set, so the menu must not set it by its resolution.
     */
    public static boolean setShortsMenuSelectedQuality(VideoQualityMenuInterface menu) {
        VideoQualityInterface quality = shortsMenuSelectedQuality;
        clearShortsMenuSelectedQuality();
        if (quality == null) {
            return false;
        }

        try {
            menu.patch_setQuality(quality);
            return true;
        } catch (Exception ex) {
            Logger.printException(() -> "setShortsMenuSelectedQuality failure", ex);
            return false;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called after the Shorts quality menu handled a click, so the quality selected is not set
     * by another caller if the menu did not set it.
     */
    public static void clearShortsMenuSelectedQuality() {
        shortsMenuSelectedQuality = null;
    }

    /**
     * Injection point.  Regular videos.
     * @param selectedQuality Quality selected by the user.
     * @return The quality to set (see {@link #getPlayableQuality(VideoQualityInterface)}).
     */
    public static VideoQualityInterface userChangedQuality(VideoQualityInterface selectedQuality) {
        final VideoQualityInterface quality = getPlayableQuality(selectedQuality);
        userChangedQuality(quality.patch_getResolution());
        return quality;
    }

    /**
     * @param videoResolution Human-readable resolution: 480, 720, 1080.
     */
    public static void userChangedQuality(int videoResolution) {
        Utils.verifyOnMainThread();
        Logger.printDebug(() -> "User changed quality to: " + videoResolution);
        setUserSelectedQuality(videoResolution, false);

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

    /**
     * Injection point.
     * <p>
     * If this flag is on, the formats of the quality selected in the quality menu are saved with
     * the quality last selected, and the player keeps playing them. If it is off, only the resolution
     * YouTube gives the quality is saved, and the player can switch to another quality of that resolution,
     * such as 720p60 after selecting a 480p quality that YouTube gives the resolution 720
     * (see {@link VideoInformation#fixVideoQualityResolution(String, int)}).
     * <p>
     * With this flag on, Shorts played with the default quality can restart from the beginning
     * when the formats change, and the formats saved for a Short are also used for the next Shorts.
     * So it is on only while the Short the user selected the quality for is playing.
     *
     * @return If the formats of the selected quality are saved and used by the player.
     */
    public static boolean useSelectedQualityFormats(boolean original) {
        final String videoId = VideoInformation.getVideoId();
        return ShortsPlayerState.isOpen() && !videoId.isEmpty() && videoId.equals(shortsSelectedQualityVideoId);
    }

    /**
     * Injection point.
     * <p>
     * When the quality changes, the player keeps playing the video already buffered and
     * plays the new quality only after it. Shorts are often buffered entirely when they open,
     * so the quality selected for a Short is not played, not even when the Short loops.
     * So the buffered video is cleared the first time the player sets the video formats
     * after the user selected the quality of the current video.
     *
     * @return If the buffered video is cleared.
     */
    public static boolean clearVideoBufferOnQualityChange(boolean original) {
        final String videoId = clearVideoBufferVideoId;
        if (videoId.isEmpty()) {
            return original;
        }

        clearVideoBufferVideoId = "";
        // Not cleared if another video started before the player set the selected quality.
        if (original || clearVideoBufferTimeUs < 0 || !videoId.equals(VideoInformation.getVideoId())) {
            clearVideoBufferTimeUs = -1;
            return original;
        }
        return true;
    }

    /**
     * Injection point.
     * <p>
     * The player clears the whole buffered video and buffers it again from the time the video
     * started or was last seeked to, such as the start of a Short. The video then plays
     * from that time and is played faster until it reaches the audio.
     * The buffered video is cleared only after the segment that is playing, as YouTube does
     * when the audio track changes, so the new quality is played after it without a gap.
     * The video buffered before that segment is cleared too, so a Short that loops
     * does not play the previous quality from its start.
     * The segment itself is cleared once it was played (see {@link #shouldClearPlayedVideoSegment()}).
     *
     * @return The playback time in microseconds after and before whose segment the buffered video
     *         is cleared, or -1 to clear the whole buffered video.
     */
    public static long getClearVideoBufferTime() {
        final long timeUs = clearVideoBufferTimeUs;
        clearVideoBufferTimeUs = -1;
        if (timeUs >= 0) {
            clearPlayedVideoSegmentVideoId = VideoInformation.getVideoId();
        }
        return timeUs;
    }

    /**
     * Injection point.
     * <p>
     * The segment that was playing when the user selected the quality keeps the previous quality.
     * A Short is often buffered entirely, so it plays that segment again when it loops,
     * such as its first segment if the quality was selected right after the Short started.
     * While this returns true, the player clears the video buffered before the playback time,
     * which it does only once the playback time is after the first segment buffered.
     *
     * @return If the video buffered before the playback time is cleared.
     */
    public static boolean shouldClearPlayedVideoSegment() {
        final String videoId = clearPlayedVideoSegmentVideoId;
        if (videoId.isEmpty()) {
            return false;
        }
        if (!videoId.equals(VideoInformation.getVideoId())) {
            // Another video started before the segment was played.
            clearPlayedVideoSegmentVideoId = "";
            return false;
        }
        return true;
    }

    /**
     * Injection point.
     * <p>
     * Called when the segment that was playing when the user selected the quality was cleared.
     */
    public static void onPlayedVideoSegmentCleared() {
        Logger.printDebug(() -> "Cleared the video segment buffered with the previous quality");
        clearPlayedVideoSegmentVideoId = "";
    }
}
