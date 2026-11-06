/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.storage;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;
import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;

public class TrackOffsetStoreTest {

    private TrackInfo track;

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        track = LyricsFixtures.track("Test Song", "Test Artist");
    }

    @Test
    public void getReturnsZeroWhenNotSet() {
        assertEquals(0, TrackOffsetStore.get(track));
    }

    @Test
    public void putThenGetReturnsSameValue() {
        TrackOffsetStore.put(track, 500);
        assertEquals(500, TrackOffsetStore.get(track));
    }

    @Test
    public void putZeroRemovesEntry() {
        TrackOffsetStore.put(track, 500);
        TrackOffsetStore.put(track, 0);
        assertEquals(0, TrackOffsetStore.get(track));
    }

    @Test
    public void putDifferentTracksSeparate() {
        TrackInfo track2 = LyricsFixtures.track("Another Song", "Another Artist");
        TrackOffsetStore.put(track, 500);
        TrackOffsetStore.put(track2, 300);
        assertEquals(500, TrackOffsetStore.get(track));
        assertEquals(300, TrackOffsetStore.get(track2));
    }

    @Test
    public void putOverwritesSameTrack() {
        TrackOffsetStore.put(track, 500);
        TrackOffsetStore.put(track, 800);
        assertEquals(800, TrackOffsetStore.get(track));
    }
}
