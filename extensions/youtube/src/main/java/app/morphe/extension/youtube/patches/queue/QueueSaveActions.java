/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static app.morphe.extension.shared.StringRef.str;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.AddToPlaylistEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.CreatePlaylistDialogEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveResponse;

/** An owned command transfers its immutable selection to the native picker's saved arguments. */
public final class QueueSaveActions {
    public interface Picker {
        List<String> patch_getQueueSaveVideoIds();
        MessageLite patch_parseQueueSaveResponse(byte[] bytes);
    }

    private QueueSaveActions() {
    }

    static NavigationEndpoint openPicker(List<String> videoIds) {
        if (videoIds.isEmpty()) throw new IllegalArgumentException("Empty queue save selection");
        return NavigationEndpoint.newBuilder().setAddToPlaylistEndpoint(AddToPlaylistEndpoint.newBuilder()
                .setVideoId(videoIds.get(0))).addAllQueueSaveVideoIds(videoIds).build();
    }

    /** Read our local command field after native protobuf copies, without global selection state. */
    @Nullable
    public static ArrayList<String> copyVideoIds(MessageLite endpoint) throws InvalidProtocolBufferException {
        var command = NavigationEndpoint.parseFrom(endpoint.toByteArray());
        return command.getQueueSaveVideoIdsCount() == 0 ? null
                : new ArrayList<>(command.getQueueSaveVideoIdsList());
    }

    /** Bind the actual mutation commands, rather than assuming the query response is a batch. */
    public static MessageLite onPickerResponse(Picker picker, MessageLite response) {
        try {
            var ids = picker.patch_getQueueSaveVideoIds();
            if (ids == null) return response;
            return picker.patch_parseQueueSaveResponse(bindResponse(
                    QueueSaveResponse.parseFrom(response.toByteArray()), ids).toByteArray());
        } catch (Exception ex) {
            Logger.printException(() -> "onPickerResponse failure", ex);

            // An empty native response follows the original close/error path. Never
            // silently fall back to saving one video after a failed batch binding.
            return picker.patch_parseQueueSaveResponse(QueueSaveResponse.getDefaultInstance().toByteArray());
        }
    }

    static QueueSaveResponse bindResponse(QueueSaveResponse response, List<String> ids) {
        var result = response.toBuilder();
        for (int index = 0; index < response.getContentsCount(); index++) {
            var item = response.getContents(index);
            if (!item.hasAddToPlaylistRenderer()) continue;
            var renderer = item.getAddToPlaylistRenderer();
            var updated = renderer.toBuilder();
            for (int row = 0; row < renderer.getPlaylistsCount(); row++) {
                var destination = renderer.getPlaylists(row);
                if (!destination.hasPlaylistAddToOptionRenderer()) continue;
                var option = destination.getPlaylistAddToOptionRenderer();
                if (!option.hasAddEndpoint()) continue;
                String title = option.getTitle().getSimpleText();
                if (title.isEmpty()) {
                    var text = new StringBuilder();
                    for (var run : option.getTitle().getRunsList()) text.append(run.getText());
                    title = text.toString();
                }

                var add = bindCommand(option.getAddEndpoint(), ids).toBuilder()
                        .clearQueueSaveVideoIds().addAllQueueSaveVideoIds(ids).setQueueSavePlaylistTitle(title);
                var updatedOption = option.toBuilder().setAddEndpoint(add);
                if (ids.size() > 1) {
                    // The native query describes only its seed video. UNKNOWN uses
                    // YouTube's add-and-close row, avoiding a false whole-queue
                    // checkbox state and its single-video remove action.
                    updatedOption.setSelectionState(0);
                }

                updated.setPlaylists(row, destination.toBuilder().setPlaylistAddToOptionRenderer(updatedOption));
            }

            for (int row = 0; row < renderer.getHeadersCount(); row++) {
                var header = renderer.getHeaders(row);
                if (!header.hasPlaylistAddToHeaderRenderer()) continue;
                var content = header.getPlaylistAddToHeaderRenderer();
                if (!content.getButton().hasButtonRenderer()) continue;
                var button = content.getButton().getButtonRenderer();

                // This native picker header is its New playlist action. Its server
                // command need not be a direct create endpoint. Use YouTube's own
                // list-capable dialog instead, without rejecting the whole picker.
                // Clear all three native click slots so creation dispatches once.
                var updatedButton = button.toBuilder().clearServiceEndpoint().clearCommand()
                        .setNavigationEndpoint(NavigationEndpoint.newBuilder().setCreatePlaylistDialogEndpoint(
                                CreatePlaylistDialogEndpoint.newBuilder().addAllVideoIds(ids)));
                updated.setHeaders(row, header.toBuilder().setPlaylistAddToHeaderRenderer(content.toBuilder()
                        .setButton(content.getButton().toBuilder().setButtonRenderer(updatedButton))));
            }

            result.setContents(index, item.toBuilder().setAddToPlaylistRenderer(updated));
        }

        return result.build();
    }

    static NavigationEndpoint bindCommand(NavigationEndpoint command, List<String> ids) {
        if (command.hasPlaylistEditEndpoint()) {
            var edit = command.getPlaylistEditEndpoint();
            var additions = new ArrayList<PlaylistEditAction>();
            for (var action : edit.getActionsList()) {
                if (action.getAction() == 1) additions.add(action);
            }

            if (additions.size() == 1 && additions.get(0).getAddedVideoId().equals(ids.get(0))) {
                var updated = edit.toBuilder().clearActions();
                for (var action : edit.getActionsList()) {
                    if (action.getAction() == 1) {
                        for (String id : ids) updated.addActions(action.toBuilder().setAddedVideoId(id));
                    } else {
                        updated.addActions(action);
                    }
                }

                return command.toBuilder().setPlaylistEditEndpoint(updated).build();
            }

            var addedIds = new ArrayList<String>();
            for (var action : additions) addedIds.add(action.getAddedVideoId());
            if (addedIds.equals(ids)) return command;
            throw new IllegalStateException("Native queue save did not supply the complete add selection");
        }

        throw new IllegalStateException("Unrecognized native queue save destination command");
    }

    /** The native edit callback owns its selection; no process-wide pending save. */
    public static void onEditCreated(QueueSaveFeedback.Callback callback, MessageLite endpoint) {
        try {
            var command = NavigationEndpoint.parseFrom(endpoint.toByteArray());
            if (command.getQueueSaveVideoIdsCount() == 0 || !command.hasPlaylistEditEndpoint()
                    || !command.hasQueueSavePlaylistTitle()) return;
            var edit = command.getPlaylistEditEndpoint();
            if (edit.getActionsCount() == 0) return;
            for (var action : edit.getActionsList()) if (action.getAction() != 1) return;
            callback.patch_setQueueSaveSelection(new QueueSaveFeedback.Selection(edit.getPlaylistId(),
                    command.getQueueSavePlaylistTitle(), command.getQueueSaveVideoIdsList()));
        } catch (Exception ex) {
            Logger.printException(() -> "onEditCreated failure", ex);
        }
    }

    /** Runs only on the native success response, before its original action dispatch. */
    public static MessageLite onEditResult(QueueSaveFeedback.Callback callback, MessageLite response) {
        try {
            var selection = callback.patch_getQueueSaveSelection();
            if (selection == null) return response;
            var result = QueueSaveEditResponse.parseFrom(response.toByteArray());
            String message = selection.title().isEmpty() ? str("morphe_native_playlist_queue_saved")
                    : str("morphe_native_playlist_queue_saved_to", selection.title());
            var decorated = QueueSaveFeedback.decorate(result, selection, message,
                    str("offline_undo_snackbar_button_text"));
            return decorated == result ? response : callback.patch_parseQueueSaveResult(decorated.toByteArray());
        } catch (Exception ex) {
            // The save already completed; never replace its result with a UI failure.
            Logger.printException(() -> "onEditResult failure", ex);
            return response;
        }
    }
}
