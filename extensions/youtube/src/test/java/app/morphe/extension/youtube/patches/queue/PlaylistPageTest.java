/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;

public class PlaylistPageTest {
    private static PlaylistQueueRenderer.Video video(String playlist, String video, int index) {
        return PlaylistQueueRenderer.Video.newBuilder().setVideoId(video)
                .setTitle(PlaylistQueueRenderer.Text.newBuilder().setSimpleText("Title"))
                .setNavigationEndpoint(PlaylistQueueRenderer.NavigationEndpoint.newBuilder()
                        .setWatchEndpoint(PlaylistQueueRenderer.WatchEndpoint.newBuilder()
                                .setPlaylistId(playlist).setVideoId(video).setIndex(index)
                                .setPlaylistSetVideoId("entry" + index))).build();
    }

    @Test
    public void hiddenUnavailableRowsDoNotSelectAnotherVideoOrLoseDuplicateOccurrences() throws Exception {
        var panel = PlaylistQueueRenderer.Panel.newBuilder().setPlaylistId("WL").setVideoCount(2401)
                .addItems(PlaylistQueueRenderer.Item.newBuilder().setVideo(PlaylistQueueRenderer.Video.newBuilder()
                        .setSelected(true).setUnplayableText(PlaylistQueueRenderer.Text.getDefaultInstance())))
                .addItems(PlaylistQueueRenderer.Item.newBuilder().setVideo(video("WL", "abcdefghijk", 1)))
                .addItems(PlaylistQueueRenderer.Item.newBuilder().setVideo(video("WL", "abcdefghijk", 2))).build();
        var page = PlaylistPage.readPanel(panel, "WL");
        assertEquals(2401, page.totalCount());
        assertEquals(-1, page.selectedIndex());
        assertEquals(2, page.rows().size());
        assertEquals(1, page.find(page.rows().get(1).anchor()));
        var queue = PlaylistQueue.fromPage(page,
                new PlaylistQueue.Source("WL", new PlaylistPage.Account("account", false)));
        assertEquals(0, queue.selectedIndex());
        assertEquals("abcdefghijk", queue.currentEntry().videoId());
    }

    @Test
    public void foreignSourcesAndShiftedDuplicateAnchorsAreRejected() throws Exception {
        var panel = PlaylistQueueRenderer.Panel.newBuilder().setPlaylistId("WL").setVideoCount(10)
                .addItems(PlaylistQueueRenderer.Item.newBuilder().setVideo(video("LL", "abcdefghijk", 1))).build();
        assertThrows(PlaylistPage.InvalidPanel.class, () -> PlaylistPage.readPanel(panel, "WL"));
        var valid = panel.toBuilder().setItems(0, PlaylistQueueRenderer.Item.newBuilder()
                .setVideo(video("WL", "abcdefghijk", 1))).build();
        var page = PlaylistPage.readPanel(valid, "WL");
        var anchor = page.rows().get(0).anchor();
        assertEquals(-1, page.find(new PlaylistPage.Anchor(anchor.playlistId(), anchor.videoId(), 5,
                anchor.entryId(), anchor.params(), anchor.tracking())));
    }
}
