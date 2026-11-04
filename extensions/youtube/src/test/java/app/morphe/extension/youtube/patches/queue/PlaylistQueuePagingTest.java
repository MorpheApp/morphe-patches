/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4404
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import com.google.protobuf.ByteString;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class PlaylistQueuePagingTest {
    private static String id(int index) {
        return String.format(Locale.ROOT, "%011d", index);
    }

    private static PlaylistPage page(int total, int... positions) {
        var rows = new ArrayList<PlaylistPage.Row>();
        for (int index : positions) {
            var metadata = new VideoMetadata(id(index), "Video " + index, "Channel", "Views", "1:23", false, true);
            var anchor = new PlaylistPage.Anchor("WL", id(index), index, "entry" + index, "", ByteString.EMPTY);
            rows.add(new PlaylistPage.Row(new PlaylistQueue.Entry(id(index), metadata.title()), metadata, anchor));
        }
        return new PlaylistPage(rows, 0, total);
    }

    private static PlaylistQueue queue() {
        return PlaylistQueue.fromPage(page(5, 0, 1, 2),
                new PlaylistQueue.Source("WL", new PlaylistPage.Account("account", false)));
    }

    @Test
    public void firstWindowStartsAtFirstVideoAndRetainsReportedCountAndMetadata() {
        var queue = queue();
        assertEquals(3, queue.entries().size());
        assertEquals(5, queue.totalCount());
        assertEquals(id(0), queue.currentEntry().videoId());
        assertEquals("Channel", queue.currentEntry().metadata().channelName());
        assertNotNull(queue.source());
    }

    @Test
    public void followingWindowPreservesEditsAndKeepsAppendedVideoAfterUnloadedSource() {
        var original = queue().playLast("abcdefghijk").select(3);
        var outside = original.currentEntry();
        var edited = original.remove(0).move(1, 0);
        var extended = edited.appendPage(page(5, 2, 3, 4), true);
        assertEquals(List.of(id(2), id(1), id(3), id(4), "abcdefghijk"),
                extended.entries().stream().map(PlaylistQueue.Entry::videoId).toList());
        assertSame(outside, extended.currentEntry());
        assertEquals(5, extended.totalCount());
        assertNull(extended.source());
    }

    @Test
    public void movingToLastLoadedRowDoesNotMoveTheUnloadedTailBeforeIt() {
        var moved = queue().move(0, 2);
        var extended = moved.appendPage(page(5, 2, 3, 4), true);
        assertEquals(List.of(id(1), id(2), id(0), id(3), id(4)),
                extended.entries().stream().map(PlaylistQueue.Entry::videoId).toList());
        assertSame(moved.currentEntry(), extended.currentEntry());
    }

    @Test
    public void removingTheSourceAnchorDoesNotReinsertItOrAcceptAShiftedOccurrence() {
        var removed = queue().remove(2);
        var extended = removed.appendPage(page(5, 2, 3, 4), true);
        assertEquals(List.of(id(0), id(1), id(3), id(4)),
                extended.entries().stream().map(PlaylistQueue.Entry::videoId).toList());
        assertThrows(IllegalArgumentException.class, () -> removed.appendPage(page(5, 3, 4), true));
        assertEquals(2, removed.entries().size());
    }

    @Test
    public void generatedMixRetainsItsFiniteBatchAndDuplicateVideos() {
        var metadata = new VideoMetadata(id(0), "Video", "Channel", "", "1:23", false, true);
        var mix = PlaylistQueue.fromMetadata(List.of(metadata, metadata));
        assertNull(mix.source());
        assertEquals(2, mix.totalCount());
        assertEquals(2, mix.entries().size());
        assertSame(metadata, mix.entries().get(1).metadata());
    }
}
