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

import app.morphe.extension.youtube.innertube.queue.PlaylistQueueRenderer;

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
        return new PlaylistPage(rows, 0, total, null);
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
    public void playbackWindowUsesTheOriginalOccurrenceAfterQueueEdits() {
        var original = queue().select(2);
        var edited = original.playNext("abcdefghijk").move(2, 0).remove(2);
        assertEquals(0, edited.selectedIndex());
        assertEquals(2, edited.playbackAnchor().index());
        assertEquals("entry2", edited.playbackAnchor().entryId());
        var extended = edited.appendPage(page(5, 2, 3, 4), false);
        assertSame(original.currentEntry(), extended.currentEntry());
        assertEquals(2, extended.playbackAnchor().index());
        assertEquals(3, extended.select(3).playbackAnchor().index());
        assertNull(extended.select(2).playbackAnchor());
    }

    @Test
    public void anotherCopyOfTheSamePlaylistCannotRefreshTheUnfinishedSource() {
        var incoming = queue().appendPage(page(5, 2, 3, 4), true);
        var next = queue().addPlaylist(incoming, false);
        assertNull(next.select(1).playbackAnchor());
        assertEquals(1, next.select(6).playbackAnchor().index());

        var last = incoming.addPlaylist(queue(), true);
        assertNull(last.playbackAnchor());
        assertEquals(0, last.select(5).playbackAnchor().index());
        assertNull(last.appendPage(page(5, 2, 3, 4), true).playbackAnchor());
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
    public void hiddenVideosKeepTheNativeTotalAndShowTheirFooterOnlyAfterTheSourceEnds() {
        var footer = PlaylistQueueRenderer.Item.newBuilder()
                .setMessageRenderer(ByteString.copyFromUtf8("native localized message")).build();
        var window = page(4, 0, 1, 2);
        var first = new PlaylistPage(window.rows(), 0, 4, footer);
        var before = PlaylistQueue.fromPage(first,
                new PlaylistQueue.Source("WL", new PlaylistPage.Account("account", false)));
        assertNull(before.unavailableVideosMessage());

        var completed = before.playLast("abcdefghijk").appendPage(page(4, 2), true);
        assertNull(completed.source());
        assertEquals(5, completed.totalCount());
        assertEquals(4, completed.entries().size());
        assertSame(footer, completed.unavailableVideosMessage());
        assertEquals("abcdefghijk", completed.entries().get(3).videoId());

        var edited = completed.remove(1).move(1, 0).select(1).withRepeatMode(PlaylistQueue.REPEAT_ALL).shuffle();
        assertEquals(4, edited.totalCount());
        assertEquals(3, edited.entries().size());
        assertSame(footer, edited.unavailableVideosMessage());
        assertNull(new PlaylistQueue("abcdefghijk", "Fresh queue").unavailableVideosMessage());
    }

    @Test
    public void appendingAnotherSourceRetainsTheFooterUntilItsNewTailFinishes() {
        var footer = PlaylistQueueRenderer.Item.newBuilder()
                .setMessageRenderer(ByteString.copyFromUtf8("native localized message")).build();
        var window = page(4, 0, 1, 2);
        var completed = PlaylistQueue.fromPage(new PlaylistPage(window.rows(), 0, 4, footer), null);
        var pending = completed.addPlaylist(queue(), true);
        assertNull(pending.unavailableVideosMessage());
        var finished = pending.appendPage(page(5, 2, 3, 4), true);
        assertEquals(9, finished.totalCount());
        assertEquals(8, finished.entries().size());
        assertSame(footer, finished.unavailableVideosMessage());
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

    @Test
    public void incomingNextPreservesSelectionMetadataAndTheExistingSourcesUnloadedTail() {
        var before = queue().playLast("abcdefghijk").select(1).withRepeatMode(PlaylistQueue.REPEAT_ALL);
        var metadata = new VideoMetadata("ZYXWVUTSRQP", "Incoming", "Channel", "", "2:34", false, true);
        var incoming = PlaylistQueue.fromMetadata(List.of(metadata, metadata));
        var inserted = before.addPlaylist(incoming, false);
        var completed = inserted.appendPage(page(5, 2, 3, 4), true);

        assertEquals(List.of(id(0), id(1), "ZYXWVUTSRQP", "ZYXWVUTSRQP", id(2), id(3), id(4), "abcdefghijk"),
                completed.entries().stream().map(PlaylistQueue.Entry::videoId).toList());
        assertSame(before.currentEntry(), completed.currentEntry());
        assertSame(metadata, completed.entries().get(2).metadata());
        assertEquals(PlaylistQueue.REPEAT_ALL, completed.repeatMode());
        assertEquals(8, completed.totalCount());
    }

    @Test
    public void incomingLastTransfersPaginationAfterTheEntireExistingQueue() {
        var before = new PlaylistQueue("abcdefghijk", "Playing").playLast("ZYXWVUTSRQP");
        var incoming = queue();
        var appended = before.addPlaylist(incoming, true);
        assertEquals(7, appended.totalCount());
        assertSame(incoming.source().origin(), appended.source().origin());
        var completed = appended.appendPage(page(5, 2, 3, 4), true);

        assertEquals(List.of("abcdefghijk", "ZYXWVUTSRQP", id(0), id(1), id(2), id(3), id(4)),
                completed.entries().stream().map(PlaylistQueue.Entry::videoId).toList());
        assertSame(before.currentEntry(), completed.currentEntry());
        assertNull(completed.source());
    }

    @Test
    public void finiteMixAppendsAfterTheExistingSourcesUnloadedTail() {
        var before = queue();
        var mix = PlaylistQueue.fromMetadata(List.of(
                new VideoMetadata("abcdefghijk", "Mix", "Channel", "", "1:23", false, true)));
        var appended = before.addPlaylist(mix, true);
        var completed = appended.appendPage(page(5, 2, 3, 4), true);
        assertEquals(List.of(id(0), id(1), id(2), id(3), id(4), "abcdefghijk"),
                completed.entries().stream().map(PlaylistQueue.Entry::videoId).toList());
        assertSame(before.source(), appended.source());
    }

    @Test
    public void unfinishedIncomingSourceCannotReplaceAnExistingCursorOrInsertAsNext() {
        var incoming = queue();
        assertThrows(IllegalStateException.class, () -> queue().addPlaylist(incoming, true));
        assertThrows(IllegalStateException.class,
                () -> new PlaylistQueue("abcdefghijk", "Playing").addPlaylist(incoming, false));
    }
}
