/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import androidx.annotation.Nullable;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.MessageLite;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.AddToToastAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueToastItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueToastRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.SwipeAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.SwipeButton;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.Text;

/** Native snackbar feedback and restoration of a cleared local queue. */
public final class QueueUndoActions {
    public interface Command {
        MessageLite patch_parseQueueCommand(byte[] bytes);
    }

    private QueueUndoActions() {
    }

    static NavigationEndpoint feedback(String token, String message, String undoLabel) {
        return NavigationEndpoint.newBuilder().setAddToToastAction(AddToToastAction.newBuilder()
                .setItem(QueueToastItem.newBuilder().setToastRenderer(QueueToastRenderer.newBuilder()
                        .setResponseText(Text.newBuilder().setSimpleText(message))
                        .setActionButton(SwipeAction.newBuilder().setButtonRenderer(SwipeButton.newBuilder()
                                .setText(Text.newBuilder().setSimpleText(undoLabel))
                                .setServiceEndpoint(NavigationEndpoint.newBuilder().setQueueUndoToken(token)))))))
                .build();
    }

    /** A snackbar capability owns its action independently of the enclosing row's context. */
    static boolean dispatchUndo(MessageLite endpoint, String expectedToken, Runnable undo)
            throws InvalidProtocolBufferException {
        if (endpoint == null) return false;
        var command = NavigationEndpoint.parseFrom(endpoint.toByteArray());
        if (!command.hasQueueUndoToken()) return false;
        if (command.getQueueUndoToken().equals(expectedToken)) undo.run();

        // Expired/replaced local capabilities must not reach native service dispatch.
        return true;
    }

    /** A menu Clear leaves playback alive; never overwrite a new queue or watch session. */
    @Nullable
    static PlaylistQueue restore(PlaylistQueue before, PlaylistQueue current, String activeVideoId) {
        return current == null && before.currentEntry().videoId().equals(activeVideoId) ? before : null;
    }
}
