/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveAddedEntry;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResult;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueToastRenderer;

public class QueueSaveFeedbackTest {
    private static final List<String> IDS = List.of("abcdefghijk", "lmnopqrstuv", "ZYXWVUTSRQP");
    private static final QueueSaveFeedback.Selection SELECTION =
            new QueueSaveFeedback.Selection("PLdestination", "Test playlist", IDS);

    private static QueueSaveEditResponse result() {
        return QueueSaveEditResponse.newBuilder().setStatus(1)
                .addResults(QueueSaveEditResult.newBuilder().setAddedEntry(QueueSaveAddedEntry.newBuilder()
                        .setVideoId(IDS.get(0)).setSetVideoId("NEW_ENTRY_A")))
                .addResults(QueueSaveEditResult.newBuilder().setAddedEntry(QueueSaveAddedEntry.newBuilder()
                        .setVideoId(IDS.get(1)).setSetVideoId("NEW_ENTRY_B"))).build();
    }

    private static QueueToastRenderer toast(QueueSaveEditResponse result) {
        return QueueSaveFeedback.decorate(result, SELECTION, "Saved to Test playlist", "Undo")
                .getActions(0).getAddToToastAction().getItem().getToastRenderer();
    }

    @Test public void undoRemovesOnlyServerReportedNewPlaylistEntries() {
        var toast = toast(result());
        assertEquals("Saved to Test playlist", toast.getResponseText().getSimpleText());
        var endpoint = toast.getActionButton().getButtonRenderer().getServiceEndpoint();
        var undo = endpoint.getPlaylistEditEndpoint();
        assertEquals("PLdestination", undo.getPlaylistId());
        assertEquals(0, endpoint.getQueueSaveVideoIdsCount());
        assertEquals(2, undo.getActionsCount());
        assertEquals(2, undo.getActions(0).getAction());
        assertEquals("NEW_ENTRY_A", undo.getActions(0).getSetVideoId());
        assertEquals("NEW_ENTRY_B", undo.getActions(1).getSetVideoId());
        for (var action : undo.getActionsList()) assertFalse(action.hasAddedVideoId());
    }

    @Test public void ownedSaveConfirmationUsesFiveSecondsAfterSerialization() throws Exception {
        var renderer = toast(result());
        var parsed = QueueToastRenderer.parseFrom(renderer.toByteArray());
        assertEquals(5_000, QueueSaveFeedback.confirmationDuration(parsed));
        assertEquals(2, parsed.getActionButton().getButtonRenderer().getServiceEndpoint()
                .getPlaylistEditEndpoint().getActionsCount());
    }

    @Test public void ordinaryConfirmationWithTheSameTextAndUndoKeepsNativeDuration() {
        var ordinary = toast(result()).toBuilder().clearQueueSaveConfirmation().build();
        assertEquals(0, QueueSaveFeedback.confirmationDuration(ordinary));
    }

    @Test public void existingFeedbackAndFailedResultsAreUnchanged() {
        var decorated = QueueSaveFeedback.decorate(result(), SELECTION, "Saved", "Undo");
        assertSame(decorated, QueueSaveFeedback.decorate(decorated, SELECTION, "duplicate", "Undo"));
        var failure = result().toBuilder().setStatus(0).build();
        assertSame(failure, QueueSaveFeedback.decorate(failure, SELECTION, "wrong", "Undo"));
    }

    @Test public void missingEntryIdsCannotOfferDestructiveUndo() {
        assertFalse(toast(result().toBuilder().clearResults().build()).hasActionButton());
    }

    @Test public void foreignVideoResultCannotOfferDestructiveUndo() {
        var foreign = result().toBuilder().addResults(QueueSaveEditResult.newBuilder()
                .setAddedEntry(QueueSaveAddedEntry.newBuilder().setVideoId("not-in-queue")
                        .setSetVideoId("OLD_ENTRY"))).build();
        assertFalse(toast(foreign).hasActionButton());
    }
}
