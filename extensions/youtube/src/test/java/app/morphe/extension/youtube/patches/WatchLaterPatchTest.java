/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.MessageLite;

import org.junit.Test;

import java.util.List;
import java.util.Arrays;

import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditActionType;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.AddToToastAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.patches.WatchLaterPatch.RemovalFeedback;

public class WatchLaterPatchTest {
    private static PlaylistEditAction action(PlaylistEditActionType type) {
        return PlaylistEditAction.newBuilder().setAction(type).build();
    }

    @Test
    public void onlyWatchLaterRemovalBatchesQualify() {
        var remove = action(PlaylistEditActionType.ACTION_REMOVE_VIDEO);
        var removeById = action(PlaylistEditActionType.ACTION_REMOVE_VIDEO_BY_VIDEO_ID);
        var add = action(PlaylistEditActionType.ACTION_ADD_VIDEO);
        assertTrue(RemovalFeedback.isWatchLaterRemoval("WL", List.of(remove)));
        assertTrue(RemovalFeedback.isWatchLaterRemoval("WL", List.of(removeById)));
        assertTrue(RemovalFeedback.isWatchLaterRemoval("WL", List.of(remove, removeById)));
        assertFalse(RemovalFeedback.isWatchLaterRemoval("other", List.of(remove)));
        assertFalse(RemovalFeedback.isWatchLaterRemoval(null, List.of(remove)));
        assertFalse(RemovalFeedback.isWatchLaterRemoval("WL", List.of()));
        assertFalse(RemovalFeedback.isWatchLaterRemoval("WL", List.of(add)));
        assertFalse(RemovalFeedback.isWatchLaterRemoval("WL", List.of(remove, add)));
        assertFalse(RemovalFeedback.isWatchLaterRemoval("WL", List.of(
                PlaylistEditAction.newBuilder().setActionValue(3).build())));
        assertFalse(RemovalFeedback.isWatchLaterRemoval("WL", List.of(PlaylistEditAction.getDefaultInstance())));
    }

    @Test
    public void malformedActionsDoNotQualify() throws Exception {
        assertFalse(RemovalFeedback.isWatchLaterRemoval("WL", Arrays.asList((MessageLite) null)));
        // Unknown endpoint bytes become an invalid UTF-8 string in PlaylistEditAction.
        var malformed = NavigationEndpoint.parseFrom(new byte[]{0x12, 0x01, (byte) 0xFF});
        assertFalse(RemovalFeedback.isWatchLaterRemoval("WL", List.of(malformed)));
    }

    private static QueueSaveEditResponse response(int status) {
        return QueueSaveEditResponse.newBuilder().setStatus(status)
                .addActions(NavigationEndpoint.newBuilder()
                        .setAddToToastAction(AddToToastAction.getDefaultInstance()))
                .addActions(NavigationEndpoint.getDefaultInstance()).build();
    }

    @Test
    public void successfulResponsesLoseOnlyToastActions() {
        var original = response(1);
        var filtered = RemovalFeedback.filterRemovalSnackbar(original);
        assertEquals(1, filtered.getStatus());
        assertEquals(2, filtered.getActionsCount());
        assertFalse(filtered.getActions(0).hasAddToToastAction());
        assertEquals(original.getActions(1), filtered.getActions(1));
        assertTrue(original.getActions(0).hasAddToToastAction());
        assertSame(filtered, RemovalFeedback.filterRemovalSnackbar(filtered));
    }

    @Test
    public void unsuccessfulAndToastFreeResponsesKeepTheirIdentity() {
        for (int status : new int[]{0, 2, 3}) {
            var original = response(status);
            assertSame(original, RemovalFeedback.filterRemovalSnackbar(original));
        }
        var empty = QueueSaveEditResponse.newBuilder().setStatus(1).build();
        assertSame(empty, RemovalFeedback.filterRemovalSnackbar(empty));
    }

    @Test
    public void unknownResponseAndActionFieldsSurvive() throws Exception {
        byte[] unknown = {(byte) 0xA0, 0x06, 0x07}; // Field 100, varint 7.
        var action = NavigationEndpoint.newBuilder().mergeFrom(unknown)
                .setAddToToastAction(AddToToastAction.getDefaultInstance()).build();
        var original = QueueSaveEditResponse.newBuilder().mergeFrom(unknown)
                .setStatus(1).addActions(action).build();
        var expected = QueueSaveEditResponse.newBuilder().mergeFrom(unknown)
                .setStatus(1).addActions(NavigationEndpoint.parseFrom(unknown)).build();
        assertEquals(expected, RemovalFeedback.filterRemovalSnackbar(original));
    }
}
