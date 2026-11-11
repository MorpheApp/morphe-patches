/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3905
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches;

import android.net.Uri;
import android.util.Base64;

import androidx.annotation.Nullable;

import com.google.protobuf.ByteString;
import com.google.protobuf.MessageLite;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.patches.BaseInAppDownloadsPatch;
import app.morphe.extension.shared.patches.DownloadMode;
import app.morphe.extension.shared.innertube.Offline.ButtonCommand;
import app.morphe.extension.shared.innertube.Offline.DownloadButtonColor;
import app.morphe.extension.shared.innertube.Offline.DownloadButtonLayout;
import app.morphe.extension.shared.innertube.Offline.DownloadButtonSize;
import app.morphe.extension.shared.innertube.Offline.DownloadButtonStyle;
import app.morphe.extension.shared.innertube.Offline.EntityKey;
import app.morphe.extension.shared.innertube.Offline.FlexibleAction;
import app.morphe.extension.shared.innertube.Offline.FlexibleActionsRow;
import app.morphe.extension.shared.innertube.Offline.FlexibleActionsViewModel;
import app.morphe.extension.shared.innertube.Offline.OfflineCommand;
import app.morphe.extension.shared.innertube.Offline.OfflineDownloadAction;
import app.morphe.extension.shared.innertube.Offline.OfflineDownloadActionCommand;
import app.morphe.extension.shared.innertube.Offline.OfflinePlaylistAction;
import app.morphe.extension.shared.innertube.Offline.OfflinePlaylistEndpoint;
import app.morphe.extension.shared.innertube.Offline.PlaylistDownloadButtonViewModel;
import app.morphe.extension.shared.innertube.Offline.PlaylistDownloadStatusEntityId;
import app.morphe.extension.youtube.patches.utils.FeedVideoCardsPatch;
import app.morphe.extension.youtube.patches.utils.ProtoNode;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings("unused")
public final class InAppDownloadsPatch extends BaseInAppDownloadsPatch {

    /**
     * Key of the interaction logger, in the context a command is run with.
     */
    private static final String INTERACTION_LOGGER_KEY = "com.google.android.libraries.youtube.logging.interaction_logger";

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
            if (!isInAppDownloadMode() || command == null) {
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
     * Implemented by the app class that loads the player response of saved videos.
     */
    public interface OfflinePlaybackWorker {
        /**
         * Sets the start time of a saved video played from Downloads
         * to the position saved in its Downloads entry.
         *
         * @param descriptor     Playback start descriptor.
         * @param playerResponse Player response model of the saved video.
         */
        void patch_setDownloadStartTime(Object descriptor, @Nullable Object playerResponse);
    }

    /**
     * Injection point.
     * <p>
     * Called when the player response of a saved video is loaded. The play command of a Downloads card
     * keeps the position from when the card was built, and the app can use an older position than the
     * one saved in Downloads, so a saved video can start at 0:00 or at an older position.
     */
    public static void setDownloadStartTime(OfflinePlaybackWorker worker,
                                            Object descriptor,
                                            @Nullable Object playerResponse) {
        try {
            worker.patch_setDownloadStartTime(descriptor, playerResponse);
        } catch (Exception ex) {
            Logger.printException(() -> "setDownloadStartTime failure", ex);
        }
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
        // If the formats of the video are loaded first, the command logs to the interaction logger
        // without checking it is set.
        Map<String, Object> context = new HashMap<>();
        context.put(INTERACTION_LOGGER_KEY, getNoOpInteractionLogger());
        if (!CommandRouterPatch.executeCommand(saveVideoCommand(videoId, List.of()).toByteArray(), context)) {
            Logger.printException(() -> "Could not save the video, no activity is shown: " + videoId);
        }
    }

    /**
     * @return The interaction logger of the app that logs nothing.
     */
    @Nullable
    private static Object getNoOpInteractionLogger() {
        return null; // Modified during patching.
    }
}
