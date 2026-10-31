/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/3953
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.queue;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.List;

public class PlaylistQueueTest {
    private static final String A = "abcdefghijk";
    private static final String B = "lmnopqrstuv";
    private static final String C = "0123456789_";
    private static final String D = "ZYXWVUTSRQP";

    private static List<String> videos(PlaylistQueue queue) {
        return queue.entries().stream().map(PlaylistQueue.Entry::videoId).toList();
    }

    private static PlaylistQueue populated() {
        return new PlaylistQueue(A, "Playing").playNext(B).playNext(C);
    }

    @Test public void playNextInsertsAfterPlayingAndPreservesEarlierSnapshots() {
        var initial = new PlaylistQueue(A, "Playing");
        var first = initial.playNext(B);
        var second = first.playNext(C);
        assertEquals(List.of(A), videos(initial));
        assertEquals(List.of(A, B), videos(first));
        assertEquals(List.of(A, C, B), videos(second));
    }

    @Test public void insertionFollowsSelectedRowWithoutChangingOrder() {
        var before = populated();
        var selected = before.select(2);
        var updated = selected.playNext(D);
        assertEquals(List.of(A, C, B), videos(selected));
        assertEquals(List.of(A, C, B, D), videos(updated));
        assertEquals(0, before.selectedIndex());
        assertEquals(2, updated.selectedIndex());
        assertThrows(IndexOutOfBoundsException.class, () -> before.select(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> before.select(3));
    }

    @Test public void removalProtectsPlayingRowAndAdjustsSelection() {
        var selected = populated().select(1);
        assertEquals(videos(selected), videos(selected.remove(1)));
        var earlierRemoved = selected.remove(0);
        assertEquals(List.of(C, B), videos(earlierRemoved));
        assertEquals(0, earlierRemoved.selectedIndex());
        assertEquals(List.of(C), videos(earlierRemoved.remove(1)));
        assertEquals(List.of(A, C, B), videos(selected));
    }

    @Test public void movesAcrossPlayingRowPreserveItsIdentity() {
        var before = populated().select(1);
        int[][] moves = {{0, 2}, {2, 0}, {1, 2}, {1, 0}, {1, 1}};
        var expected = List.of(List.of(C, B, A), List.of(B, A, C), List.of(A, B, C),
                List.of(C, A, B), List.of(A, C, B));
        for (int i = 0; i < moves.length; i++) {
            var moved = before.move(moves[i][0], moves[i][1]);
            assertSame(before.currentEntry(), moved.currentEntry());
            assertEquals(expected.get(i), videos(moved));
            assertEquals(expected.get(i).indexOf(C), moved.selectedIndex());
            assertEquals(List.of(A, C, B), videos(before));
        }
        var duplicate = new PlaylistQueue(A, "Playing").playNext(A);
        assertEquals(1, duplicate.move(0, 1).selectedIndex());
        assertThrows(IndexOutOfBoundsException.class, () -> before.move(-1, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> before.move(0, 3));
    }

    @Test public void outsideVideoIsInsertedAndSelectedAfterCurrentRow() {
        var before = new PlaylistQueue(A, "Playing").playNext(B).withRepeatMode(PlaylistQueue.REPEAT_ALL);
        var opened = before.playNextAndSelect(C);
        assertEquals(List.of(A, C, B), videos(opened));
        assertEquals(1, opened.selectedIndex());
        assertEquals(PlaylistQueue.REPEAT_ALL, opened.repeatMode());
        assertEquals(List.of(A, B), videos(before));
        assertEquals(0, before.selectedIndex());
        var next = opened.playNext(D);
        assertEquals(List.of(A, C, D, B), videos(next));
        var another = opened.playNextAndSelect(D);
        assertEquals(videos(next), videos(another));
        assertEquals(2, another.selectedIndex());
        assertThrows(IllegalArgumentException.class, () -> opened.playNextAndSelect("invalid"));
        assertEquals(3, opened.entries().size());
    }

    @Test public void playLastAppendsDuplicatesWithoutChangingSelectionOrRepeat() {
        var before = populated().withRepeatMode(PlaylistQueue.REPEAT_ALL);
        for (int index = 0; index < 3; index++) {
            var selected = before.select(index);
            var updated = selected.playLast(D);
            assertEquals(List.of(A, C, B, D), videos(updated));
            assertEquals(index, updated.selectedIndex());
            assertEquals(PlaylistQueue.REPEAT_ALL, updated.repeatMode());
            assertEquals(List.of(A, C, B), videos(selected));
            assertEquals(List.of(A, C, B, D, A), videos(updated.playLast(A)));
            assertThrows(IllegalArgumentException.class, () -> updated.playLast("invalid"));
            assertEquals(4, updated.entries().size());
        }
    }

    @Test public void invalidInsertionLeavesQueueUnchanged() {
        var queue = new PlaylistQueue(A, "Playing");
        assertThrows(IllegalArgumentException.class, () -> queue.playNext("invalid"));
        assertEquals(List.of(A), videos(queue));
    }

    @Test public void shufflePreservesPlayingOccurrenceRepeatAndEarlierSnapshot() {
        var before = populated().playLast(A).select(3).withRepeatMode(PlaylistQueue.REPEAT_ALL);
        var shuffled = before.shuffle();
        assertEquals(0, shuffled.selectedIndex());
        assertSame(before.currentEntry(), shuffled.currentEntry());
        assertEquals(PlaylistQueue.REPEAT_ALL, shuffled.repeatMode());
        assertEquals(before.entries().size(), shuffled.entries().size());
        // Compare occurrence identity, including equal-valued duplicate videos.
        for (var entry : before.entries()) {
            assertEquals(1, shuffled.entries().stream().filter(candidate -> candidate == entry).count());
        }
        assertEquals(List.of(A, C, B, A), videos(before));
        assertEquals(3, before.selectedIndex());
        assertNotSame(before, shuffled);
        var again = shuffled.shuffle();
        assertSame(before.currentEntry(), again.currentEntry());
        assertEquals(0, again.selectedIndex());
        assertEquals(shuffled.entries().size(), again.entries().size());
    }

    @Test public void shuffledOrderIsUsedBySubsequentQueueEdits() {
        var shuffled = populated().shuffle();
        var next = shuffled.playNext(D);
        assertSame(shuffled.currentEntry(), next.currentEntry());
        assertEquals(D, next.entries().get(1).videoId());
        assertEquals(shuffled.entries().subList(1, 3), next.entries().subList(2, 4));
        var last = shuffled.playLast(D);
        assertEquals(shuffled.entries(), last.entries().subList(0, 3));
        assertEquals(D, last.entries().get(3).videoId());
        var selected = shuffled.select(1);
        assertSame(shuffled.entries().get(1), selected.currentEntry());
        assertEquals(shuffled.entries(), selected.entries());
        assertSame(selected.currentEntry(), selected.move(1, 2).currentEntry());
        assertEquals(shuffled.entries().subList(0, 2), shuffled.remove(2).entries());
        var single = new PlaylistQueue(A, "Playing");
        assertSame(single, single.shuffle());
    }
}
