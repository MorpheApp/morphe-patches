/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4698
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.shared.patches;

import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.innertube.PlayerResponseOuterClass.PlayerGestureConfig;
import app.morphe.extension.shared.innertube.SavedVideo.OfflineState;
import app.morphe.extension.shared.innertube.SavedVideo.OfflineStateAction;
import app.morphe.extension.shared.settings.SharedYouTubeSettings;
import app.morphe.extension.shared.sponsorblock.SponsorBlockOfflineStorage;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.shared.innertube.Offline.OfflineCaptionTrack;
import app.morphe.extension.shared.innertube.Offline.OfflineCaptionTracklist;
import app.morphe.extension.shared.innertube.Offline.OfflineCaptions;
import app.morphe.extension.shared.innertube.Offline.OfflineCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineFormat;
import app.morphe.extension.shared.innertube.Offline.OfflineFormatAvailability;
import app.morphe.extension.shared.innertube.Offline.OfflineFormatLabel;
import app.morphe.extension.shared.innertube.Offline.OfflineFormatType;
import app.morphe.extension.shared.innertube.Offline.OfflinePlayerConfig;
import app.morphe.extension.shared.innertube.Offline.OfflinePlayerResponse;
import app.morphe.extension.shared.innertube.Offline.OfflinePlaylistAction;
import app.morphe.extension.shared.innertube.Offline.OfflinePlaylistEndpoint;
import app.morphe.extension.shared.innertube.Offline.OfflineStreamFormat;
import app.morphe.extension.shared.innertube.Offline.OfflineStreamingData;
import app.morphe.extension.shared.innertube.Offline.OfflineUpsellCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineUpsellResponse;
import app.morphe.extension.shared.innertube.Offline.OfflineVideoAction;
import app.morphe.extension.shared.innertube.Offline.OfflineVideoEndpoint;
import app.morphe.extension.shared.innertube.Offline.OfflineabilityContainer;
import app.morphe.extension.shared.innertube.Offline.OfflineabilityRenderer;

/**
 * Saves videos and songs with the offline downloads of the app, for both YouTube and YouTube Music.
 */
@SuppressWarnings("unused")
public class BaseInAppDownloadsPatch {

    /**
     * Qualities offered when the server does not list any, highest first.
     */
    protected static final OfflineFormatType[] DEFAULT_FORMAT_TYPES = {
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD_1080,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD_480,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_LD,
    };

    protected static final String[] DEFAULT_FORMAT_LABELS = {
            "1080p", "720p", "480p", "360p", "144p"
    };

    /**
     * Clients that answer with stream urls, which the app can save.
     * The visionOS streams need no JavaScript challenge, whose unsolved streams the server sends slowly.
     */
    protected static final List<ClientType> DOWNLOAD_CLIENTS = List.of(
            ClientType.VISIONOS_1_03,
            ClientType.VISIONOS_1_02,
            ClientType.ANDROID_VR_DASH,
            ClientType.TV_DASH,
            ClientType.ANDROID_CREATOR,
            ClientType.TV_SIMPLY
    );

    /**
     * How long a saved video stays valid before the app asks the server about it again.
     */
    protected static final int OFFLINE_POLICY_LIFETIME_SECONDS = (int) TimeUnit.DAYS.toSeconds(365);

    /**
     * Opus audio is not saved, the other audio and all video formats are.
     */
    protected static final String UNSAVABLE_AUDIO_MIME_TYPE_PREFIX = "audio/webm";

    /**
     * Format of the caption urls of the app. The caption urls of visionOS have no format, and the server
     * then answers with a legacy format whose text is escaped twice, so the saved captions show
     * entities such as {@code &#39;} instead of an apostrophe.
     * The format is not a signed parameter, so the url stays valid.
     */
    protected static final String CAPTION_URL_FORMAT = "&fmt=srv3";

    protected static boolean isInAppDownloadMode() {
        return SharedYouTubeSettings.DOWNLOAD_MODE.get() == DownloadMode.IN_APP;
    }

    /**
     * Injection point.
     * <p>
     * Called before a video or playlist is saved, or before the quality picker is shown.
     *
     * @param offlineability Offlineability renderer of the video or playlist.
     * @return The renderer with every quality available, or null to keep the original.
     */
    @Nullable
    public static byte[] unlockOfflineability(@Nullable Object offlineability) {
        try {
            if (!isInAppDownloadMode()) {
                return null;
            }

            OfflineabilityRenderer renderer = offlineability instanceof MessageLite message
                    ? OfflineabilityRenderer.parseFrom(message.toByteArray())
                    : OfflineabilityRenderer.getDefaultInstance();
            if (renderer.getOfflineable()
                    && renderer.getFormatsCount() > 0
                    && !hasLockedFormat(renderer.getFormatsList())) {
                return null;
            }

            Logger.printDebug(() -> "Unlocking offlineability, offlineable: " + renderer.getOfflineable());
            return renderer.toBuilder()
                    .setOfflineable(true)
                    .clearNotOfflineableDialog()
                    .clearFormats()
                    .addAllFormats(unlockedFormats(renderer.getFormatsList()))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "unlockOfflineability failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called with the response of the offline upsell endpoint, which offers Premium instead of saving the video.
     *
     * @param command  Command of the offline upsell endpoint.
     * @param response Response of the endpoint.
     * @return A response that saves the video instead, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceOfflineUpsell(@Nullable MessageLite command, @Nullable Object response) {
        try {
            if (!isInAppDownloadMode()
                    || command == null
                    || !(response instanceof MessageLite responseMessage)) {
                return null;
            }

            OfflineUpsellResponse upsellResponse = OfflineUpsellResponse.parseFrom(responseMessage.toByteArray());
            if (!upsellResponse.getContent().hasOfflineUpsellRenderer()) {
                return null;
            }

            String videoId = OfflineUpsellCommand.parseFrom(command.toByteArray())
                    .getOfflineUpsellEndpoint().getVideoId();
            if (videoId.isEmpty()) {
                Logger.printDebug(() -> "Offline upsell command has no video id");
                return null;
            }

            Logger.printDebug(() -> "Replacing offline upsell with saving the video: " + videoId);
            return upsellResponse.toBuilder()
                    .clearContent()
                    .setCommand(saveVideoCommand(videoId,
                            upsellResponse.getContent().getOfflineUpsellRenderer().getFormatsList()))
                    .build()
                    .toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceOfflineUpsell failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called off the main thread with the player response the app saves a video with.
     * If the server declines to save the video, the streams are fetched with another client.
     *
     * @param playerResponse Player response proto of the video.
     * @return A player response that can be saved, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceDeclinedOfflinePlayerResponse(@Nullable Object playerResponse) {
        try {
            if (!isInAppDownloadMode()
                    || !(playerResponse instanceof MessageLite message)) {
                return null;
            }

            OfflinePlayerResponse original = OfflinePlayerResponse.parseFrom(message.toByteArray());
            if (!original.hasOfflineState()) {
                return null;
            }

            String videoId = original.hasVideoDetails()
                    ? original.getVideoDetails().getVideoId()
                    : "";

            final boolean allowed = original.getOfflineState().getActionValue()
                    == OfflineStateAction.OFFLINE_STATE_ACTION_OK_VALUE;
            if (allowed && original.getStreamingData().getAdaptiveFormatsCount() > 0) {
                if (!videoId.isEmpty()) {
                    SponsorBlockOfflineStorage.fetchAndSaveSegments(videoId);
                }
                return null;
            }

            if (videoId.isEmpty()) {
                Logger.printDebug(() -> "Declined offline player response has no video id");
                return null;
            }

            StreamingDataRequest.StreamData streams = StreamingDataRequest.fetchRequestForDownload(
                    videoId, DOWNLOAD_CLIENTS, DOWNLOAD_CLIENTS.get(0)).getStream();
            if (streams == null) {
                Logger.printDebug(() -> "No streams to save the video with: " + videoId);
                return null;
            }

            // The declined response lacks the parts the app needs to select the streams to save,
            // such as the player config. The response of the other client is used instead,
            // and only the offline state token and player config are kept from the declined response.
            OfflinePlayerResponse.Builder builder = OfflinePlayerResponse.parseFrom(streams.streamingData()).toBuilder();
            builder.setStreamingData(savableStreamingData(builder.getStreamingData()))
                    .setOfflineState(playableOfflineState(original.getOfflineState().getToken()));
            if (builder.hasCaptions()) {
                builder.setCaptions(srv3Captions(builder.getCaptions()));
            }

            // Without the ExoPlayer config the app only looks at the progressive formats, which are not savable,
            // and the playback speed menu is unavailable.
            // The player config of the app's own response fits the device, so it's kept if there is one.
            OfflinePlayerConfig playerConfig = original.hasPlayerConfig()
                    ? original.getPlayerConfig()
                    : builder.getPlayerConfig();
            PlayerGestureConfig.Builder gestures = playerConfig.getPlayerGestureConfig().toBuilder();
            if (!gestures.hasDownAndOutPortraitAllowed()) {
                gestures.setDownAndOutPortraitAllowed(true);
            }
            if (!gestures.hasDownAndOutLandscapeAllowed()) {
                gestures.setDownAndOutLandscapeAllowed(true);
            }
            builder.setPlayerConfig(playerConfig.toBuilder()
                    .setPlayerGestureConfig(gestures)
                    .setExoPlayerConfig(playerConfig.getExoPlayerConfig().toBuilder()
                            .setUseExoPlayer(true)
                            .setEnableVariableSpeedPlayback(true)));

            SponsorBlockOfflineStorage.fetchAndSaveSegments(videoId);

            Logger.printDebug(() -> "Replaced declined offline player response of: " + videoId
                    + " savable formats: " + countSavableFormats(builder.getStreamingData())
                    + " of: " + builder.getStreamingData().getAdaptiveFormatsCount());
            return builder.build().toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceDeclinedOfflinePlayerResponse failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called off the main thread with the offline state the server gives a saved video when the app
     * refreshes its saved videos, which the server schedules about every 12 hours.
     * The server disables saved videos that need Premium to be saved, such as music videos,
     * and the app then shows an error instead of playing them.
     *
     * @param offlineState New offline state of a saved video.
     * @return An offline state that keeps the video playable, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceDisabledOfflineState(@Nullable MessageLite offlineState) {
        try {
            if (!isInAppDownloadMode() || offlineState == null) {
                return null;
            }

            OfflineState state = OfflineState.parseFrom(offlineState.toByteArray());
            if (state.getAction() != OfflineStateAction.OFFLINE_STATE_ACTION_DISABLE) {
                return null;
            }

            Logger.printDebug(() -> "Replacing disabled offline state with reason: " + state.getDisabledReason());
            return playableOfflineState(state.getToken()).toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceDisabledOfflineState failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called after the player response model is created from the replaced player response.
     *
     * @param playerResponseModel The created model, or null if the app could not parse the replaced player response.
     */
    public static void onOfflinePlayerResponseReplaced(@Nullable Object playerResponseModel) {
        SpoofVideoStreamsPatch.setKeepStreamsOnThread(false);
        if (playerResponseModel == null) {
            Logger.printException(() -> "Could not create the player response model of the replaced player response");
        }
    }

    /**
     * @return Offline state that keeps a saved video playable, without asking the server again for a long time.
     */
    protected static OfflineState playableOfflineState(String token) {
        return OfflineState.newBuilder()
                .setToken(token)
                .setPolicyLifetimeSeconds(OFFLINE_POLICY_LIFETIME_SECONDS)
                .setAction(OfflineStateAction.OFFLINE_STATE_ACTION_OK)
                .build();
    }

    /**
     * @return A command of the offline video endpoint that saves the video with the formats.
     */
    protected static OfflineCommand saveVideoCommand(String videoId, List<OfflineFormat> formats) {
        OfflineVideoEndpoint endpoint = OfflineVideoEndpoint.newBuilder()
                .setVideoId(videoId)
                .setAction(OfflineVideoAction.OFFLINE_VIDEO_ACTION_ADD)
                .setOfflineability(offlineability(formats))
                .build();
        return OfflineCommand.newBuilder().setOfflineVideoEndpoint(endpoint).build();
    }

    /**
     * @return A command of the offline playlist endpoint that saves the playlist with the formats.
     */
    protected static OfflineCommand savePlaylistCommand(
            String playlistId,
            OfflinePlaylistAction action,
            List<OfflineFormat> formats
    ) {
        String normalizedId = playlistId.startsWith("VL") ? playlistId.substring(2) : playlistId;
        OfflinePlaylistEndpoint endpoint = OfflinePlaylistEndpoint.newBuilder()
                .setPlaylistId(normalizedId)
                .setAction(action)
                .setOfflineability(offlineability(formats))
                .build();
        return OfflineCommand.newBuilder().setOfflinePlaylistEndpoint(endpoint).build();
    }

    /**
     * @return Offlineability that saves with the formats.
     */
    protected static OfflineabilityContainer offlineability(List<OfflineFormat> formats) {
        OfflineabilityRenderer renderer = OfflineabilityRenderer.newBuilder()
                .setOfflineable(true)
                .addAllFormats(unlockedFormats(formats))
                .build();
        return OfflineabilityContainer.newBuilder()
                .setOfflineabilityRenderer(renderer)
                .build();
    }

    protected static boolean hasLockedFormat(List<OfflineFormat> formats) {
        for (OfflineFormat format : formats) {
            if (isLocked(format)) {
                return true;
            }
        }
        return false;
    }

    /**
     * @return The formats with the Premium formats available, or the default formats if there are none.
     */
    protected static List<OfflineFormat> unlockedFormats(List<OfflineFormat> formats) {
        List<OfflineFormat> unlocked = new ArrayList<>();
        if (formats.isEmpty()) {
            for (int i = 0; i < DEFAULT_FORMAT_TYPES.length; i++) {
                unlocked.add(OfflineFormat.newBuilder()
                        .setName(OfflineFormatLabel.newBuilder().setSimpleText(DEFAULT_FORMAT_LABELS[i]))
                        .setFormatType(DEFAULT_FORMAT_TYPES[i])
                        .setAvailability(OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_FREE)
                        .build());
            }
            return unlocked;
        }

        for (OfflineFormat format : formats) {
            unlocked.add(isLocked(format)
                    ? format.toBuilder()
                            .setAvailability(OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_FREE)
                            .build()
                    : format);
        }
        return unlocked;
    }

    protected static boolean isLocked(OfflineFormat format) {
        return format.getAvailability() == OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_PREMIUM_LOCKED;
    }

    protected static int countSavableFormats(OfflineStreamingData streamingData) {
        int count = 0;
        for (OfflineStreamFormat format : streamingData.getAdaptiveFormatsList()) {
            if (format.getOfflineEligible()) {
                count++;
            }
        }
        return count;
    }

    protected static OfflineStreamingData savableStreamingData(OfflineStreamingData streamingData) {
        OfflineStreamingData.Builder builder = streamingData.toBuilder();
        for (int i = 0, count = builder.getAdaptiveFormatsCount(); i < count; i++) {
            OfflineStreamFormat format = builder.getAdaptiveFormats(i);
            String mimeType = format.getMimeType();
            if (!mimeType.isEmpty() && !mimeType.startsWith(UNSAVABLE_AUDIO_MIME_TYPE_PREFIX)) {
                builder.setAdaptiveFormats(i, format.toBuilder().setOfflineEligible(true));
            }
        }
        return builder.build();
    }

    protected static OfflineCaptions srv3Captions(OfflineCaptions captions) {
        OfflineCaptionTracklist.Builder tracklist = captions.getPlayerCaptionsTracklistRenderer().toBuilder();
        for (int i = 0, count = tracklist.getCaptionTracksCount(); i < count; i++) {
            OfflineCaptionTrack track = tracklist.getCaptionTracks(i);
            String baseUrl = track.getBaseUrl().replaceAll("&fmt=[^&]*", "") + CAPTION_URL_FORMAT;
            tracklist.setCaptionTracks(i, track.toBuilder().setBaseUrl(baseUrl));
        }
        return captions.toBuilder().setPlayerCaptionsTracklistRenderer(tracklist).build();
    }
}
