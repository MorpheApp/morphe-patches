/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;

public class QueueUndoActionsTest {
    private static final String A = "abcdefghijk";
    private static final String B = "ZYXWVUTSRQP";
    private static final String C = "12345678901";

    private static PlaylistQueue original() {
        return new PlaylistQueue(A, "A").playLast(B).playLast(A).playLast(C);
    }

    @Test public void clearUndoRequiresSameVideoAndNoNewQueue() {
        var before = original().select(2);
        assertSame(before, QueueUndoActions.restore(before, null, A));
        assertNull(QueueUndoActions.restore(before, null, B));
        assertNull(QueueUndoActions.restore(before, new PlaylistQueue(A, "new"), A));
    }

    @Test public void feedbackCarriesIndependentCapability() throws Exception {
        var feedback = NavigationEndpoint.parseFrom(
                QueueUndoActions.feedback("capability", "Queue cleared", "Undo").toByteArray());
        var toast = feedback.getAddToToastAction().getItem().getToastRenderer();
        assertEquals("Queue cleared", toast.getResponseText().getSimpleText());
        var undo = toast.getActionButton().getButtonRenderer().getServiceEndpoint();
        assertEquals("capability", undo.getQueueUndoToken());
        assertFalse(undo.hasHideEnclosingAction());
        assertFalse(undo.hasPlaylistEditEndpoint());
    }

    @Test public void expiredCapabilitiesAreConsumedWithoutRunningUndo() throws Exception {
        var undo = NavigationEndpoint.newBuilder().setQueueUndoToken("capability").build();
        int[] actions = {0};
        assertTrue(QueueUndoActions.dispatchUndo(undo, "capability", () -> actions[0]++));
        assertEquals(1, actions[0]);
        assertTrue(QueueUndoActions.dispatchUndo(undo, null, () -> actions[0]++));
        assertTrue(QueueUndoActions.dispatchUndo(undo, "newer", () -> actions[0]++));
        assertFalse(QueueUndoActions.dispatchUndo(NavigationEndpoint.getDefaultInstance(),
                "capability", () -> actions[0]++));
        assertFalse(QueueUndoActions.dispatchUndo(null, "capability", () -> actions[0]++));
        assertEquals(1, actions[0]);
    }
}
