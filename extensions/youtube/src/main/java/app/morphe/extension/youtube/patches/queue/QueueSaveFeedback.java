/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import com.google.protobuf.MessageLite;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.AddToToastAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.PlaylistEditEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueToastItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueToastRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.SwipeAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.SwipeButton;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.Text;

/** Native success feedback; Undo targets only entry IDs returned by this Add request. */
public final class QueueSaveFeedback {
    private static final int CONFIRMATION_DURATION_MILLISECONDS = 5_000;

    public interface Callback {
        Selection patch_getQueueSaveSelection();
        void patch_setQueueSaveSelection(Selection selection);
        MessageLite patch_parseQueueSaveResult(byte[] bytes);
    }

    public record Selection(String playlistId, String title, List<String> videoIds) {
        public Selection {
            videoIds = List.copyOf(videoIds);
        }
    }

    private QueueSaveFeedback() {
    }

    /** Zero retains native timing, including its accessible queue-clear timeout. */
    public static int confirmationDuration(MessageLite renderer) {
        try {
            var toast = QueueToastRenderer.parseFrom(renderer.toByteArray());
            if (toast.getQueueSaveConfirmation()) {
                return CONFIRMATION_DURATION_MILLISECONDS;
            }
            String undoToken = toast.getActionButton().getButtonRenderer().getServiceEndpoint().getQueueUndoToken();
            return undoToken.isEmpty() ? 0 : NativePlaylistQueuePatch.queueUndoDuration(undoToken);
        } catch (Exception ex) {
            Logger.printException(() -> "confirmationDuration failure", ex);
            return 0;
        }
    }

    static QueueSaveEditResponse decorate(QueueSaveEditResponse response, Selection selection,
                                         String message, String undoLabel) {
        // Native PlaylistEditResponse status: 0 unknown, 1 succeeded.
        if (response.getStatus() != 1) {
            return response;
        }
        for (var action : response.getActionsList()) {
            if (action.hasAddToToastAction()) {
                return response;
            }
        }

        var toast = QueueToastRenderer.newBuilder().setQueueSaveConfirmation(true)
                .setResponseText(Text.newBuilder().setSimpleText(message));
        var requestedIds = new HashSet<>(selection.videoIds());
        var entryIds = new LinkedHashSet<String>();
        boolean validUndo = !selection.playlistId().isEmpty();
        for (var result : response.getResultsList()) {
            if (!result.hasAddedEntry()) {
                validUndo = false;
                break;
            }

            var entry = result.getAddedEntry();
            if (entry.getSetVideoId().isEmpty() || !requestedIds.contains(entry.getVideoId())) {
                validUndo = false;
                break;
            }

            entryIds.add(entry.getSetVideoId());
        }

        if (validUndo && !entryIds.isEmpty()) {
            var undo = PlaylistEditEndpoint.newBuilder().setPlaylistId(selection.playlistId());
            for (String id : entryIds) {
                // ACTION_REMOVE_VIDEO uses the exact playlist entry, not video ID.
                undo.addActions(PlaylistEditAction.newBuilder().setAction(2).setSetVideoId(id));
            }

            toast.setActionButton(SwipeAction.newBuilder().setButtonRenderer(SwipeButton.newBuilder()
                    .setText(Text.newBuilder().setSimpleText(undoLabel))
                    .setServiceEndpoint(NavigationEndpoint.newBuilder().setPlaylistEditEndpoint(undo))));
        }

        return response.toBuilder().addActions(NavigationEndpoint.newBuilder().setAddToToastAction(
                AddToToastAction.newBuilder().setItem(QueueToastItem.newBuilder().setToastRenderer(toast)))).build();
    }
}
