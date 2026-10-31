/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3905
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;


import android.net.Uri;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.sponsorblock.SponsorBlockOfflineStorage;
import app.morphe.extension.shared.spoof.ClientType;
import app.morphe.extension.shared.spoof.SpoofVideoStreamsPatch;
import app.morphe.extension.shared.spoof.requests.StreamingDataRequest;
import app.morphe.extension.youtube.innertube.Offline.ButtonCommand;
import app.morphe.extension.youtube.innertube.Offline.DownloadButtonColor;
import app.morphe.extension.youtube.innertube.Offline.DownloadButtonLayout;
import app.morphe.extension.youtube.innertube.Offline.DownloadButtonSize;
import app.morphe.extension.youtube.innertube.Offline.DownloadButtonStyle;
import app.morphe.extension.youtube.innertube.Offline.EntityKey;
import app.morphe.extension.youtube.innertube.Offline.FlexibleAction;
import app.morphe.extension.youtube.innertube.Offline.FlexibleActionsRow;
import app.morphe.extension.youtube.innertube.Offline.FlexibleActionsViewModel;
import app.morphe.extension.youtube.innertube.Offline.OfflineCommand;
import app.morphe.extension.youtube.innertube.Offline.OfflineDownloadAction;
import app.morphe.extension.youtube.innertube.Offline.OfflineDownloadActionCommand;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormat;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatAvailability;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatLabel;
import app.morphe.extension.youtube.innertube.Offline.OfflineFormatType;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerConfig;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlayerResponse;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlaylistAction;
import app.morphe.extension.youtube.innertube.Offline.OfflinePlaylistEndpoint;
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
import app.morphe.extension.youtube.innertube.Offline.PlaylistDownloadButtonViewModel;
import app.morphe.extension.youtube.innertube.Offline.PlaylistDownloadStatusEntityId;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
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
     * Name of the template of the page headers, such as the header of a playlist page.
     */
    private static final byte[] PAGE_HEADER_TEMPLATE = "page_header".getBytes(StandardCharsets.US_ASCII);

    /**
     * Field of the buttons of a page header, in the proto of the element.
     */
    private static final int FLEXIBLE_ACTIONS_VIEW_MODEL_FIELD = 520990934;

    /**
     * Entities the Download button of a playlist reads the download status from,
     * with the keys the app gives them when the playlist is saved.
     */
    private static final int DOWNLOAD_STATUS_ENTITY_TYPE = 141;
    private static final int PLAYLIST_DOWNLOAD_STATUS_ENTITY_ID_TYPE = 306;
    private static final int GENERATION_STATUS_ENTITY_TYPE = 147;
    private static final String PLAYLIST_GENERATION_STATUS_ENTITY_ID = "playlist";
    private static final int ENTITY_PERSISTENCE = 1;

    private static final String PLAYLIST_DOWNLOAD_BUTTON_IDENTIFIER = "playlist-download-button";

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
     * Determines whether the given player response proto represents a playable offline video.
     */
    public static boolean isOfflinePlayerResponse(@NonNull String videoId, @Nullable Object playerProto) {
        try {
            if (!(playerProto instanceof MessageLite message)) {
                Logger.printDebug(() -> "isOfflinePlayerResponse: playerProto is not MessageLite: "
                        + (playerProto != null ? playerProto.getClass().getName() : "null"));
                return false;
            }
            OfflinePlayerResponse response = OfflinePlayerResponse.parseFrom(message.toByteArray());
            if (response.hasOfflineState()) {
                int action = response.getOfflineState().getActionValue();
                if (action == OfflineStateAction.OFFLINE_STATE_ACTION_OK_VALUE) {
                    Logger.printDebug(() -> "Detected playable offline video: " + videoId);
                    return true;
                } else {
                    Logger.printDebug(() -> "Offline video state action not OK: " + action + " for: " + videoId);
                }
            } else {
                Logger.printDebug(() -> "OfflinePlayerResponse has no offline state for: " + videoId);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "isOfflinePlayerResponse failure", ex);
        }
        return false;
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

            // Without the ExoPlayer config the app only looks at the progressive formats, which are not savable.
            // The player config of the app's own response fits the device, so it's kept if there is one.
            OfflinePlayerConfig playerConfig = original.hasPlayerConfig()
                    ? original.getPlayerConfig()
                    : builder.getPlayerConfig();
            builder.setPlayerConfig(playerConfig.toBuilder().setExoPlayerConfig(
                    playerConfig.getExoPlayerConfig().toBuilder().setUseExoPlayer(true)));

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
            if (Settings.DOWNLOAD_MODE.get() != DownloadMode.IN_APP || offlineState == null) {
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
     * @return Offline state that keeps a saved video playable, without asking the server again for a long time.
     */
    private static OfflineState playableOfflineState(String token) {
        return OfflineState.newBuilder()
                .setToken(token)
                .setPolicyLifetimeSeconds(OFFLINE_POLICY_LIFETIME_SECONDS)
                .setAction(OfflineStateAction.OFFLINE_STATE_ACTION_OK)
                .build();
    }

    /**
     * Injection point.
     * <p>
     * Called for every element the app parses.
     * The server only gives a Download button to the playlist page headers of Premium accounts,
     * so it's added to the headers of other accounts. The button saves the playlist in-app,
     * or opens the external downloader in External downloader mode.
     *
     * @param bytes Proto of the element.
     * @return The element with a Download button, or the original element.
     */
    public static byte[] addPlaylistDownloadButton(byte[] bytes) {
        try {
            if (Settings.DOWNLOAD_MODE.get() == DownloadMode.DEFAULT
                    || !FeedVideoCardsPatch.contains(bytes, PAGE_HEADER_TEMPLATE)) {
                return bytes;
            }

            List<ProtoNode> element = ProtoNode.parse(bytes);
            if (element == null) {
                return bytes;
            }

            for (ProtoNode actionsNode : ProtoNode.findMessages(element, FLEXIBLE_ACTIONS_VIEW_MODEL_FIELD)) {
                FlexibleActionsViewModel actions = withPlaylistDownloadButton(
                        FlexibleActionsViewModel.parseFrom(actionsNode.getPayload()));
                if (actions != null) {
                    actionsNode.setPayload(actions.toByteArray());
                    return ProtoNode.write(element);
                }
            }
        } catch (Exception ex) {
            Logger.printException(() -> "addPlaylistDownloadButton failure", ex);
        }

        return bytes;
    }

    /**
     * @return The buttons with a Download button in the row of the Play all button of a playlist,
     *         or null if the buttons are not of a playlist or already have a Download button.
     */
    @Nullable
    private static FlexibleActionsViewModel withPlaylistDownloadButton(FlexibleActionsViewModel actions) {
        int playlistRowIndex = -1;
        String playlistId = "";

        List<FlexibleActionsRow> rows = actions.getActionsRowsList();
        for (int i = 0; i < rows.size(); i++) {
            for (FlexibleAction action : rows.get(i).getActionsList()) {
                if (action.hasDownloadButtonViewModel()) {
                    return null;
                }
                String id = action.getButtonViewModel().getOnTap().getInnertubeCommand()
                        .getWatchEndpoint().getPlaylistId();
                if (playlistId.isEmpty() && !id.isEmpty()) {
                    playlistRowIndex = i;
                    playlistId = id;
                }
            }
        }
        if (playlistId.isEmpty()) {
            return null;
        }

        return actions.toBuilder()
                .setActionsRows(playlistRowIndex, rows.get(playlistRowIndex).toBuilder()
                        .addActions(playlistDownloadButton(playlistId)))
                .build();
    }

    /**
     * @return The Download button the server gives to the playlist page header of Premium accounts,
     *         which saves the playlist or removes it if it's saved.
     */
    private static FlexibleAction playlistDownloadButton(String playlistId) {
        Logger.printDebug(() -> "Adding download button to playlist: " + playlistId);

        OfflinePlaylistEndpoint endpoint = OfflinePlaylistEndpoint.newBuilder()
                .setPlaylistId(playlistId)
                .setAction(OfflinePlaylistAction.OFFLINE_PLAYLIST_ACTION_INFER_AUTOMATICALLY)
                .setOfflineability(offlineability(List.of()))
                .build();
        // Texts of the app, which all supported app versions have.
        String downloadableText = ResourceUtils.getString("accessibility_playlist_download");
        String inProgressText = ResourceUtils.getString("offline_button_progress_text");
        String completedText = ResourceUtils.getString("offline_button_complete_text");
        ByteString downloadStatusEntityId = PlaylistDownloadStatusEntityId.newBuilder()
                .setPlaylistId(playlistId)
                .setType(PLAYLIST_DOWNLOAD_STATUS_ENTITY_ID_TYPE)
                .build()
                .toByteString();

        PlaylistDownloadButtonViewModel button = PlaylistDownloadButtonViewModel.newBuilder()
                .setPlaylistId(playlistId)
                .setDownloadableText(downloadableText)
                .setInProgressText(inProgressText)
                .setCompletedText(completedText)
                .setDownloadableA11YText(downloadableText)
                .setInProgressA11YText(inProgressText)
                .setCompletedA11YText(completedText)
                .setDownloadStatusKey(entityKey(DOWNLOAD_STATUS_ENTITY_TYPE, downloadStatusEntityId))
                .setGenerationStatusKey(entityKey(GENERATION_STATUS_ENTITY_TYPE,
                        ByteString.copyFromUtf8(PLAYLIST_GENERATION_STATUS_ENTITY_ID)))
                .setLayout(DownloadButtonLayout.DOWNLOAD_BUTTON_LAYOUT_PLAYLIST_HEADER)
                .setIconColor(DownloadButtonColor.DOWNLOAD_BUTTON_COLOR_PLAYLIST_HEADER)
                .setTextColor(DownloadButtonColor.DOWNLOAD_BUTTON_COLOR_PLAYLIST_HEADER)
                .setButtonSize(DownloadButtonSize.DOWNLOAD_BUTTON_SIZE_DEFAULT)
                .setButtonStyle(DownloadButtonStyle.DOWNLOAD_BUTTON_STYLE_OVERLAY)
                .setOnTapCommand(ButtonCommand.newBuilder().setInnertubeCommand(
                        OfflineCommand.newBuilder().setOfflinePlaylistEndpoint(endpoint)))
                .setIdentifier(PLAYLIST_DOWNLOAD_BUTTON_IDENTIFIER)
                .build();
        return FlexibleAction.newBuilder().setDownloadButtonViewModel(button).build();
    }

    /**
     * @return The key of the entity, as the app encodes it.
     */
    private static String entityKey(int entityType, ByteString entityId) {
        EntityKey key = EntityKey.newBuilder()
                .setEntityId(entityId)
                .setEntityType(entityType)
                .setPersistence(ENTITY_PERSISTENCE)
                .build();
        return Uri.encode(Base64.encodeToString(key.toByteArray(), Base64.URL_SAFE | Base64.NO_WRAP));
    }

    /**
     * Saves a video the same as the Download button of the app, for videos that have no such button.
     * Must be called from the main thread.
     */
    public static void saveVideo(String videoId) {
        Logger.printDebug(() -> "Saving video: " + videoId);
        if (!NativeSnackbarPatch.executeCommand(saveVideoCommand(videoId, List.of()).toByteArray())) {
            Logger.printException(() -> "Could not save the video, no activity is shown: " + videoId);
        }
    }

    /**
     * @return A command of the offline video endpoint that saves the video with the formats.
     */
    private static OfflineCommand saveVideoCommand(String videoId, List<OfflineFormat> formats) {
        OfflineVideoEndpoint endpoint = OfflineVideoEndpoint.newBuilder()
                .setVideoId(videoId)
                .setAction(OfflineVideoAction.OFFLINE_VIDEO_ACTION_ADD)
                .setOfflineability(offlineability(formats))
                .build();
        return OfflineCommand.newBuilder().setOfflineVideoEndpoint(endpoint).build();
    }

    /**
     * @return Offlineability that saves with the formats.
     */
    private static OfflineabilityContainer offlineability(List<OfflineFormat> formats) {
        OfflineabilityRenderer renderer = OfflineabilityRenderer.newBuilder()
                .setOfflineable(true)
                .addAllFormats(unlockedFormats(formats))
                .build();
        return OfflineabilityContainer.newBuilder()
                .setOfflineabilityRenderer(renderer)
                .build();
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
