/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3905
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;


import androidx.annotation.Nullable;

import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.youtube.innertube.Offline.OfflineCommand;
import app.morphe.extension.youtube.innertube.Offline.OfflineDownloadAction;
import app.morphe.extension.youtube.innertube.Offline.OfflineDownloadActionCommand;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormat;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatAvailability;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatLabel;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatType;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerConfig;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerResponse;
import app.morphe.extension.youtube.innertube.Offline.OfflineState;
import app.morphe.extension.youtube.innertube.Offline.OfflineStateAction;
import app.morphe.extension.youtube.innertube.Offline.OfflineStreamFormat;
import app.morphe.extension.youtube.innertube.Offline.OfflineStreamingData;
import app.morphe.extension.youtube.innertube.Offline.OfflineUpsellCommand;
import app.morphe.extension.youtube.innertube.Offline.OfflineUpsellResponse;
import app.morphe.extension.youtube.innertube.Offline.OfflineVideoAction;
import app.morphe.extension.youtube.innertube.Offline.OfflineVideoEndpoint;
import app.morphe.extension.youtube.innertube.Offline.OfflineabilityContainer;
import app.morphe.extension.youtube.innertube.Offline.OfflineabilityRenderer;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class InAppDownloadsPatch {

    /**
     * Qualities offered when the server does not list any, highest first.
     */
    private static final OfflineFormatType[] DEFAULT_FORMAT_TYPES = {
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD_1080,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_HD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD_480,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_SD,
            OfflineFormatType.OFFLINE_FORMAT_TYPE_LD,
    };

    private static final String[] DEFAULT_FORMAT_LABELS = {
            "1080p", "720p", "480p", "360p", "144p"
    };

    /**
     * Clients that answer with stream urls, which the app can save.
     * The visionOS streams need no JavaScript challenge, whose unsolved streams the server sends slowly.
     */
    private static final List<ClientType> DOWNLOAD_CLIENTS = List.of(
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
    private static final int OFFLINE_POLICY_LIFETIME_SECONDS = (int) TimeUnit.DAYS.toSeconds(365);

    /**
     * Opus audio is not saved, the other audio and all video formats are.
     */
    private static final String UNSAVABLE_AUDIO_MIME_TYPE_PREFIX = "audio/webm";

    /**
     * Injection point.
     * <p>
     * Called for every command the app resolves.
     * The Download button asks the server which download action to take, and the server answers
     * with the Premium upsell. Instead, the video is saved directly.
     *
     * @param command Command that is resolved.
     * @return A command that saves the video, or null to keep the original.
     */
    @Nullable
    public static byte[] replaceDownloadActionCommand(@Nullable MessageLite command) {
        try {
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP || command == null) {
                return null;
            }

            OfflineDownloadActionCommand downloadActionCommand =
                    OfflineDownloadActionCommand.parseFrom(command.toByteArray());
            if (!downloadActionCommand.hasDownloadAction()) {
                return null;
            }

            // Playlists are saved with their own endpoint, which is left as it is.
            OfflineDownloadAction downloadAction = downloadActionCommand.getDownloadAction();
            String videoId = downloadAction.getVideoId();
            if (videoId.isEmpty() || downloadAction.hasPlaylistId()) {
                return null;
            }

            Logger.printDebug(() -> "Replacing download action with saving the video: " + videoId);
            return saveVideoCommand(videoId, List.of()).toByteArray();
        } catch (Exception ex) {
            Logger.printException(() -> "replaceDownloadActionCommand failure", ex);
            return null;
        }
    }

    /**
     * Injection point.
     * <p>
     * Called before a video is saved, or before the quality picker of the video is shown.
     *
     * @param offlineability Offlineability renderer of the video.
     * @return The renderer with every quality available, or null to keep the original.
     */
    @Nullable
    public static byte[] unlockOfflineability(@Nullable Object offlineability) {
        try {
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP
                    || !(offlineability instanceof MessageLite message)) {
                return null;
            }

            OfflineabilityRenderer renderer = OfflineabilityRenderer.parseFrom(message.toByteArray());
            if (renderer.getOfflineable() && !hasLockedFormat(renderer.getFormatsList())) {
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
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP
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
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP
                    || !(playerResponse instanceof MessageLite message)) {
                return null;
            }

            OfflinePlayerResponse original = OfflinePlayerResponse.parseFrom(message.toByteArray());
            if (!original.hasOfflineState()) {
                return null;
            }
            final boolean allowed = original.getOfflineState().getActionValue()
                    == OfflineStateAction.OFFLINE_STATE_ACTION_OK_VALUE;
            if (allowed && original.getStreamingData().getAdaptiveFormatsCount() > 0) {
                return null;
            }

            String videoId = original.getVideoDetails().getVideoId();
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
                    .setOfflineState(OfflineState.newBuilder()
                            .setToken(original.getOfflineState().getToken())
                            .setPolicyLifetimeSeconds(OFFLINE_POLICY_LIFETIME_SECONDS)
                            .setAction(OfflineStateAction.OFFLINE_STATE_ACTION_OK));

            // Without the ExoPlayer config the app only looks at the progressive formats, which are not savable.
            // The player config of the app's own response fits the device, so it's kept if there is one.
            OfflinePlayerConfig playerConfig = original.hasPlayerConfig()
                    ? original.getPlayerConfig()
                    : builder.getPlayerConfig();
            builder.setPlayerConfig(playerConfig.toBuilder().setExoPlayerConfig(
                    playerConfig.getExoPlayerConfig().toBuilder().setUseExoPlayer(true)));

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
     * @return A command of the offline video endpoint that saves the video with the formats.
     */
    private static OfflineCommand saveVideoCommand(String videoId, List<OfflineFormat> formats) {
        OfflineabilityRenderer renderer = OfflineabilityRenderer.newBuilder()
                .setOfflineable(true)
                .addAllFormats(unlockedFormats(formats))
                .build();
        OfflineVideoEndpoint endpoint = OfflineVideoEndpoint.newBuilder()
                .setVideoId(videoId)
                .setAction(OfflineVideoAction.OFFLINE_VIDEO_ACTION_ADD)
                .setOfflineability(OfflineabilityContainer.newBuilder()
                        .setOfflineabilityRenderer(renderer))
                .build();
        return OfflineCommand.newBuilder().setOfflineVideoEndpoint(endpoint).build();
    }

    private static boolean hasLockedFormat(List<OfflineFormat> formats) {
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
    private static List<OfflineFormat> unlockedFormats(List<OfflineFormat> formats) {
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

    private static boolean isLocked(OfflineFormat format) {
        return format.getAvailability() == OfflineFormatAvailability.OFFLINE_FORMAT_AVAILABILITY_PREMIUM_LOCKED;
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

    private static int countSavableFormats(OfflineStreamingData streamingData) {
        int count = 0;
        for (OfflineStreamFormat format : streamingData.getAdaptiveFormatsList()) {
            if (format.getOfflineEligible()) {
                count++;
            }
        }
        return count;
    }

    private static OfflineStreamingData savableStreamingData(OfflineStreamingData streamingData) {
        OfflineStreamingData.Builder builder = streamingData.toBuilder();
        for (int i = 0, count = builder.getAdaptiveFormatsCount(); i < count; i++) {
            OfflineStreamFormat format = builder.getAdaptiveFormats(i);
            if (isSavableMimeType(format.getMimeType())) {
                builder.setAdaptiveFormats(i, format.toBuilder().setOfflineEligible(true));
            }
        }
        return builder.build();
    }

    private static boolean isSavableMimeType(String mimeType) {
        return !mimeType.isEmpty() && !mimeType.startsWith(UNSAVABLE_AUDIO_MIME_TYPE_PREFIX);
    }
}
