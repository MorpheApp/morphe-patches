/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/issues/4863
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.playback;

import org.json.JSONException;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

public class PlaybackSessionSnapshotTest {
    private static final String FIRST = "abcdefghijk";
    private static final String SECOND = "lmnopqrstuv";
    private static final String THIRD = "wxyz1234567";

    @Test
    public void roundTripKeepsMillisecondsAndDuplicateQueueOccurrences() throws JSONException {
        var queue = new PlaybackSessionSnapshot.Queue(List.of(FIRST, SECOND, FIRST, SECOND, THIRD), 2, 1);
        var snapshot = new PlaybackSessionSnapshot("account", FIRST, 12_345,
                PlaybackSessionSnapshot.Kind.VIDEO, "RD" + FIRST, false, queue);

        var restored = PlaybackSessionSnapshot.fromJson(snapshot.toJson());

        assertEquals(snapshot, restored);
        assertEquals(List.of(FIRST, SECOND, THIRD), restored.queue().upcoming());
    }

    @Test
    public void roundTripKeepsLiveAndShortKinds() throws JSONException {
        for (var kind : List.of(PlaybackSessionSnapshot.Kind.LIVE, PlaybackSessionSnapshot.Kind.SHORT)) {
            var snapshot = new PlaybackSessionSnapshot("account", FIRST, 0, kind, "", false, null);
            assertEquals(snapshot, PlaybackSessionSnapshot.fromJson(snapshot.toJson()));
        }
    }

    @Test
    public void roundTripKeepsReferenceToExistingQueue() throws JSONException {
        var snapshot = new PlaybackSessionSnapshot(
                "account", FIRST, 1000, PlaybackSessionSnapshot.Kind.VIDEO, "", true, null);
        assertEquals(snapshot, PlaybackSessionSnapshot.fromJson(snapshot.toJson()));
    }

    @Test
    public void rejectsMismatchedQueueSelectionAndUnknownVersions() {
        var queue = new PlaybackSessionSnapshot.Queue(List.of(FIRST, SECOND), 1, null);
        assertThrows(IllegalArgumentException.class, () -> new PlaybackSessionSnapshot(
                "account", FIRST, 0, PlaybackSessionSnapshot.Kind.VIDEO, "", false, queue));
        assertThrows(JSONException.class, () -> PlaybackSessionSnapshot.fromJson("{\"version\":2}"));
    }
}
