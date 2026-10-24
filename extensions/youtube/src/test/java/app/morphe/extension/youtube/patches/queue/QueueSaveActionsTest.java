/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.AddToPlaylistEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.CreatePlaylistDialogEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.PlaylistEditAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.PlaylistEditEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveDestination;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveDestinationItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveEditResponse;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveHeader;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveHeaderItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSavePickerRenderer;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveResponse;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.QueueSaveResponseItem;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.RatingEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.SwipeAction;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.SwipeButton;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.Text;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.TextRun;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.WatchEndpoint;

public class QueueSaveActionsTest {
    private static final List<String> IDS = List.of("abcdefghijk", "lmnopqrstuv", "ZYXWVUTSRQP");
    // Stock beoz schema: button renderer is field 2; field 3 is text.
    // Write the native wire shape independently of our generated header
    // builder, which previously hid an incorrect field declaration.
    static QueueSaveHeader nativeHeader(SwipeAction button) throws Exception {
        var bytes = new java.io.ByteArrayOutputStream();
        var output = com.google.protobuf.CodedOutputStream.newInstance(bytes);
        output.writeMessage(2, button);
        output.writeMessage(3, Text.newBuilder().setSimpleText("New playlist").build());
        output.flush();
        return QueueSaveHeader.parseFrom(bytes.toByteArray());
    }
    static class Picker implements QueueSaveActions.Picker {
        List<String> videoIds;
        Picker(List<String> ids) { videoIds = ids; }
        public List<String> patch_getQueueSaveVideoIds() { return videoIds; }
        public com.google.protobuf.MessageLite patch_parseQueueSaveResponse(byte[] bytes) {
            try { return QueueSaveResponse.parseFrom(bytes); }
            catch (Exception error) { throw new AssertionError(error); }
        }
    }
    static class FeedbackCallback implements QueueSaveFeedback.Callback {
        QueueSaveFeedback.Selection selection;
        public QueueSaveFeedback.Selection patch_getQueueSaveSelection() { return selection; }
        public void patch_setQueueSaveSelection(QueueSaveFeedback.Selection value) { selection = value; }
        public com.google.protobuf.MessageLite patch_parseQueueSaveResult(byte[] bytes) {
            try { return QueueSaveEditResponse.parseFrom(bytes); }
            catch (Exception error) { throw new AssertionError(error); }
        }
    }

    private static NavigationEndpoint ordinaryAdd() {
        return NavigationEndpoint.newBuilder().setPlaylistEditEndpoint(
                PlaylistEditEndpoint.newBuilder().setPlaylistId("PLdestination")
                        .addActions(PlaylistEditAction.newBuilder().setAction(1)
                                .setAddedVideoId(IDS.get(0)))).build();
    }

    private static QueueSaveResponse response(SwipeButton.Builder button) throws Exception {
        return QueueSaveResponse.newBuilder().addContents(QueueSaveResponseItem.newBuilder()
                .setAddToPlaylistRenderer(QueueSavePickerRenderer.newBuilder().addPlaylists(
                        QueueSaveDestinationItem.newBuilder().setPlaylistAddToOptionRenderer(
                                QueueSaveDestination.newBuilder().setSelectionState(3).setAddEndpoint(ordinaryAdd())
                                        .setTitle(Text.newBuilder().addRuns(TextRun.newBuilder().setText("Test playlist")))))
                        .addHeaders(QueueSaveHeaderItem.newBuilder().setPlaylistAddToHeaderRenderer(
                                nativeHeader(SwipeAction.newBuilder().setButtonRenderer(button).build()))))).build();
    }

    private static SwipeButton.Builder createButton() {
        return SwipeButton.newBuilder().setNavigationEndpoint(NavigationEndpoint.newBuilder()
                .setCreatePlaylistDialogEndpoint(CreatePlaylistDialogEndpoint.newBuilder()
                        .addVideoIds(IDS.get(0)).setVideoSelectionEntityKey("single-video-selection")));
    }

    @Test public void selectionSurvivesUnknownNativeCopyAndCallerMutations() throws Exception {
        var ids = new ArrayList<>(IDS);
        var open = QueueSaveActions.openPicker(ids);
        assertEquals(IDS.get(0), open.getAddToPlaylistEndpoint().getVideoId());
        var copy = RatingEndpoint.parseFrom(open.toByteArray());
        var saved = QueueSaveActions.copyVideoIds(copy);
        ids.clear();
        assertEquals(IDS, saved);
        saved.add(IDS.get(2));
        assertEquals(IDS, QueueSaveActions.copyVideoIds(copy));
        assertEquals(List.of(IDS.get(0), IDS.get(0)),
                QueueSaveActions.openPicker(List.of(IDS.get(0), IDS.get(0))).getQueueSaveVideoIdsList());
        assertThrows(IllegalArgumentException.class, () -> QueueSaveActions.openPicker(List.of()));
    }

    @Test public void ordinarySavesAndResponsesPassThrough() throws Exception {
        var ordinary = NavigationEndpoint.newBuilder().setAddToPlaylistEndpoint(
                AddToPlaylistEndpoint.newBuilder().setVideoId(IDS.get(0))).build();
        assertNull(QueueSaveActions.copyVideoIds(ordinary));
        var response = response(createButton());
        assertSame(response, QueueSaveActions.onPickerResponse(new Picker(null), response));
        var callback = new FeedbackCallback();
        QueueSaveActions.onEditCreated(callback, ordinaryAdd());
        assertNull(callback.selection);
        var result = QueueSaveEditResponse.newBuilder().setStatus(1).build();
        assertSame(result, QueueSaveActions.onEditResult(callback, result));
    }

    @Test public void existingPlaylistReceivesAllIdsAndOwnedFeedbackSelection() throws Exception {
        var bound = (QueueSaveResponse) QueueSaveActions.onPickerResponse(new Picker(IDS), response(createButton()));
        var destination = bound.getContents(0).getAddToPlaylistRenderer().getPlaylists(0).getPlaylistAddToOptionRenderer();
        assertEquals(0, destination.getSelectionState());
        var edit = destination.getAddEndpoint().getPlaylistEditEndpoint();
        assertEquals("PLdestination", edit.getPlaylistId());
        assertEquals(IDS, edit.getActionsList().stream().map(PlaylistEditAction::getAddedVideoId).toList());
        var callback = new FeedbackCallback();
        QueueSaveActions.onEditCreated(callback, destination.getAddEndpoint());
        assertEquals(IDS, callback.selection.videoIds());
        assertEquals("PLdestination", callback.selection.playlistId());
        assertEquals("Test playlist", callback.selection.title());
        var single = QueueSaveActions.bindResponse(response(createButton()), List.of(IDS.get(0)));
        assertEquals(3, single.getContents(0).getAddToPlaylistRenderer().getPlaylists(0)
                .getPlaylistAddToOptionRenderer().getSelectionState());
    }

    @Test public void newPlaylistReceivesFullSelectionWithoutSingleVideoEntityKey() throws Exception {
        var bound = QueueSaveActions.bindResponse(response(createButton()), IDS);
        var create = bound.getContents(0).getAddToPlaylistRenderer().getHeaders(0)
                .getPlaylistAddToHeaderRenderer().getButton().getButtonRenderer()
                .getNavigationEndpoint().getCreatePlaylistDialogEndpoint();
        assertEquals(IDS, create.getVideoIdsList());
        assertFalse(create.hasVideoSelectionEntityKey());
        assertEquals(bound, QueueSaveActions.bindResponse(bound, IDS));
    }

    @Test public void allNativeHeaderClickSlotsBecomeOneListCapableCreateAction() throws Exception {
        var other = NavigationEndpoint.newBuilder().setWatchEndpoint(
                WatchEndpoint.newBuilder().setVideoId(IDS.get(0))).build();
        var create = createButton().getNavigationEndpoint();
        for (var button : List.of(
                SwipeButton.newBuilder().setServiceEndpoint(other),
                SwipeButton.newBuilder().setNavigationEndpoint(other),
                SwipeButton.newBuilder().setCommand(other),
                SwipeButton.newBuilder().setServiceEndpoint(other).setNavigationEndpoint(create).setCommand(other))) {
            button.setText(Text.newBuilder().setSimpleText("New playlist"));
            var response = response(button);
            var bound = (QueueSaveResponse) QueueSaveActions.onPickerResponse(new Picker(IDS), response);
            assertEquals(1, bound.getContentsCount());
            var nativeButton = bound.getContents(0).getAddToPlaylistRenderer().getHeaders(0)
                    .getPlaylistAddToHeaderRenderer().getButton().getButtonRenderer();
            assertFalse(nativeButton.hasServiceEndpoint());
            assertFalse(nativeButton.hasCommand());
            assertEquals("New playlist", nativeButton.getText().getSimpleText());
            assertEquals(IDS, nativeButton.getNavigationEndpoint().getCreatePlaylistDialogEndpoint().getVideoIdsList());
            assertSame(response, QueueSaveActions.onPickerResponse(new Picker(null), response));
        }
    }

    @Test public void bindingPreservesUnknownNativeResponseFields() throws Exception {
        var wire = new java.io.ByteArrayOutputStream();
        wire.write(response(createButton()).toByteArray());
        var output = com.google.protobuf.CodedOutputStream.newInstance(wire);
        output.writeInt32(127, 99);
        output.flush();
        var bound = QueueSaveActions.bindResponse(QueueSaveResponse.parseFrom(wire.toByteArray()), IDS);
        var input = com.google.protobuf.CodedInputStream.newInstance(bound.toByteArray());
        boolean keptUnknown = false;
        for (int tag; (tag = input.readTag()) != 0;) {
            if ((tag >>> 3) == 127) keptUnknown = input.readInt32() == 99;
            else input.skipField(tag);
        }
        assertTrue("Native fields outside our schema must survive", keptUnknown);
    }
}
