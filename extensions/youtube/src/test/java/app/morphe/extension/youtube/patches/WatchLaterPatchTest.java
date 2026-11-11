/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4324
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.google.protobuf.InvalidProtocolBufferException;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.PlaylistEdit.PlaylistEditActionType;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.AddToToastAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.patches.WatchLaterPatch.RemovalSnackbar;

public class WatchLaterPatchTest {
    private static PlaylistEditAction action(PlaylistEditActionType type) {
        return PlaylistEditAction.newBuilder().setAction(type).build();
    }

    @Test
    public void onlyWatchLaterRemovalsQualify() throws Exception {
        var remove = action(PlaylistEditActionType.ACTION_REMOVE_VIDEO);
        var removeById = action(PlaylistEditActionType.ACTION_REMOVE_VIDEO_BY_VIDEO_ID);
        var add = action(PlaylistEditActionType.ACTION_ADD_VIDEO);
        assertTrue(RemovalSnackbar.isWatchLaterRemoval("WL", List.of(remove)));
        assertTrue(RemovalSnackbar.isWatchLaterRemoval("WL", List.of(removeById)));
        assertTrue(RemovalSnackbar.isWatchLaterRemoval("WL", List.of(remove, removeById)));
        assertFalse(RemovalSnackbar.isWatchLaterRemoval("other", List.of(remove)));
        assertFalse(RemovalSnackbar.isWatchLaterRemoval(null, List.of(remove)));
        assertFalse(RemovalSnackbar.isWatchLaterRemoval("WL", List.of()));
        assertFalse(RemovalSnackbar.isWatchLaterRemoval("WL", List.of(add)));
        assertFalse(RemovalSnackbar.isWatchLaterRemoval("WL", List.of(remove, add)));
        assertFalse(RemovalSnackbar.isWatchLaterRemoval("WL", List.of(
                PlaylistEditAction.newBuilder().setActionValue(3).build())));
        assertFalse(RemovalSnackbar.isWatchLaterRemoval("WL", List.of(PlaylistEditAction.getDefaultInstance())));
    }

    @Test
    public void malformedActionThrows() throws Exception {
        // Unknown endpoint bytes become an invalid UTF-8 string in PlaylistEditAction.
        var malformed = NavigationEndpoint.parseFrom(new byte[]{0x12, 0x01, (byte) 0xFF});
        assertThrows(InvalidProtocolBufferException.class,
                () -> RemovalSnackbar.isWatchLaterRemoval("WL", List.of(malformed)));
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
        var filtered = RemovalSnackbar.removeToasts(original);
        assertEquals(1, filtered.getStatus());
        assertEquals(2, filtered.getActionsCount());
        assertFalse(filtered.getActions(0).hasAddToToastAction());
        assertEquals(original.getActions(1), filtered.getActions(1));
        assertTrue(original.getActions(0).hasAddToToastAction());
        assertSame(filtered, RemovalSnackbar.removeToasts(filtered));
    }

    @Test
    public void unsuccessfulAndToastFreeResponsesAreUnchanged() {
        for (int status : new int[]{0, 2, 3}) {
            var original = response(status);
            assertSame(original, RemovalSnackbar.removeToasts(original));
        }
        var empty = QueueSaveEditResponse.newBuilder().setStatus(1).build();
        assertSame(empty, RemovalSnackbar.removeToasts(empty));
    }

    @Test
    public void unknownResponseAndActionFieldsAreKept() throws Exception {
        byte[] unknown = {(byte) 0xA0, 0x06, 0x07}; // Field 100, varint 7.
        var action = NavigationEndpoint.newBuilder().mergeFrom(unknown)
                .setAddToToastAction(AddToToastAction.getDefaultInstance()).build();
        var original = QueueSaveEditResponse.newBuilder().mergeFrom(unknown)
                .setStatus(1).addActions(action).build();
        var expected = QueueSaveEditResponse.newBuilder().mergeFrom(unknown)
                .setStatus(1).addActions(NavigationEndpoint.parseFrom(unknown)).build();
        assertEquals(expected, RemovalSnackbar.removeToasts(original));
    }
}
