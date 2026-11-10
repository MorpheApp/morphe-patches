/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.NavigationEndpoint;
import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer.WatchEndpoint;

public class MixPlaylistRequestTest {
    private static JSONObject response(JSONArray rows) throws Exception {
        return new JSONObject().put("contents", new JSONObject().put("singleColumnWatchNextResults",
                new JSONObject().put("playlist", new JSONObject().put("playlist", new JSONObject()
                        .put("playlistId", "RDmix").put("contents", rows)))));
    }

    private static JSONObject row(String id) throws Exception {
        return new JSONObject().put("playlistPanelVideoRenderer", new JSONObject().put("videoId", id)
                .put("title", new JSONObject().put("simpleText", "Title " + id))
                .put("shortBylineText", new JSONObject().put("runs", new JSONArray()
                        .put(new JSONObject().put("text", "Channel")))));
    }

    @Test public void batchPreservesOrderDuplicatesAndThePlayingOccurrence() throws Exception {
        for (int size : new int[] {50, 100}) {
            var rows = new JSONArray();
            for (int index = 0; index < size; index++) rows.put(row(index % 2 == 0 ? "abcdefghijk" : "ZYXWVUTSRQP"));
            var videos = MixPlaylistRequest.parse(response(rows), "RDmix");
            assertNotNull(videos);
            assertEquals(size, videos.size());
            var before = new PlaylistQueue("abcdefghijk", "Playing").playLast("0123456789_")
                    .select(1).withRepeatMode(PlaylistQueue.REPEAT_ONE);
            var after = before.addPlaylist(PlaylistQueue.fromMetadata(videos), true);
            assertEquals(size + before.entries().size(), after.entries().size());
            assertEquals(List.of("abcdefghijk", "ZYXWVUTSRQP"), after.entries().subList(2, 4).stream()
                    .map(PlaylistQueue.Entry::videoId).toList());
            assertSame(before.currentEntry(), after.currentEntry());
            assertEquals(before.repeatMode(), after.repeatMode());
            assertEquals(2, before.entries().size());
            assertEquals("Channel", videos.get(0).channelName());
            assertTrue(videos.stream().allMatch(VideoMetadata::isComplete));
        }
    }

    @Test
    public void mixBatchRetainsBothNativePalettesIncludingUnsignedColors() throws Exception {
        var item = row("abcdefghijk");
        item.getJSONObject("playlistPanelVideoRenderer")
                .put("lightColorPalette", new JSONObject().put("section2Color", 4076402161L).put("section4Color", 4075546333L)
                        .put("primaryTitleColor", 4279834129L).put("secondaryTitleColor", 4285555274L))
                .put("darkColorPalette", new JSONObject().put("section2Color", 4062716699L).put("section4Color", 4060875528L)
                        .put("primaryTitleColor", 4294964709L).put("secondaryTitleColor", 4291607971L));
        var imported = MixPlaylistRequest.parse(response(new JSONArray().put(item)), "RDmix");
        var metadata = PlaylistQueue.fromMetadata(imported).currentEntry().metadata();

        assertEquals((int) 4076402161L, metadata.lightColorPalette().getSection2Color());
        assertEquals((int) 4060875528L, metadata.darkColorPalette().getSection4Color());
        assertEquals((int) 4294964709L, metadata.darkColorPalette().getPrimaryTitleColor());
        assertEquals((int) 4291607971L, metadata.darkColorPalette().getSecondaryTitleColor());
        var missing = MixPlaylistRequest.parse(response(new JSONArray().put(row("abcdefghijk"))), "RDmix");
        assertNull(missing.get(0).lightColorPalette());
        assertNull(missing.get(0).darkColorPalette());
    }

    @Test
    public void invalidOptionalColorsDoNotPreventImportingTheMix() throws Exception {
        for (Object color : List.of("invalid", -1L, 4294967296L, JSONObject.NULL)) {
            var item = row("abcdefghijk");
            item.getJSONObject("playlistPanelVideoRenderer").put("darkColorPalette", new JSONObject()
                    .put("section4Color", color).put("primaryTitleColor", 4294964709L)
                    .put("secondaryTitleColor", 4291607971L)).put("lightColorPalette", new JSONObject());
            var imported = MixPlaylistRequest.parse(response(new JSONArray().put(item)), "RDmix");
            assertNotNull(imported);
            assertEquals("abcdefghijk", imported.get(0).videoId());
            assertNull(imported.get(0).lightColorPalette());
            assertNull(imported.get(0).darkColorPalette());
        }
    }

    @Test public void wrongListOrMalformedBatchNeverReturnsAPartialImport() throws Exception {
        var rows = new JSONArray().put(row("abcdefghijk"));
        assertNull(MixPlaylistRequest.parse(response(rows), "RDdifferent"));
        assertNull(MixPlaylistRequest.parse(response(rows.put(row("invalid"))), "RDmix"));
        assertNull(MixPlaylistRequest.parse(response(new JSONArray()), "RDmix"));
        assertNull(MixPlaylistRequest.parse(new JSONObject(), "RDmix"));
    }

    @Test public void dialogRetainsOpaqueWatchParametersOnBothChoices() throws Exception {
        var output = new java.io.ByteArrayOutputStream();
        var fields = com.google.protobuf.CodedOutputStream.newInstance(output);
        fields.writeString(6, "opaque-player-params"); fields.flush();
        var original = NavigationEndpoint.newBuilder().setWatchEndpoint(WatchEndpoint.newBuilder()
                .setVideoId("abcdefghijk").setPlaylistId("RDmix").mergeFrom(output.toByteArray())).build();
        var dialog = QueueMixActions.dialog(original, "capability", "Choice", "Watch", "Add")
                .getShowConfirmationDialogEndpoint().getDialog().getConfirmDialogRenderer();
        var watch = dialog.getCancelButton().getButtonRenderer().getServiceEndpoint();
        var add = dialog.getConfirmButton().getButtonRenderer().getServiceEndpoint();
        for (var choice : List.of(watch, add)) {
            assertEquals("capability", choice.getQueueMixChoiceToken());
            assertEquals(original, choice.toBuilder().clearQueueMixChoiceToken().clearQueueMixAdd().build());
        }
        assertFalse(watch.getQueueMixAdd());
        assertTrue(add.getQueueMixAdd());
    }
}
