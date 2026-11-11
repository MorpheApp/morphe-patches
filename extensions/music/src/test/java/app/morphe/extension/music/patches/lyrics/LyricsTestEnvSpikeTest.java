/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.BeforeClass;
import org.junit.Test;

import java.io.File;

import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;
import app.morphe.extension.music.patches.lyrics.storage.TrackOffsetStore;
import app.morphe.extension.music.settings.Settings;

/** Spike: verifies the fake context bootstrap lets Settings/Utils/storage work under JVM tests. */
public class LyricsTestEnvSpikeTest {

    @BeforeClass
    public static void setUpClass() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void settingsLoadWithoutContextNpe() {
        assertTrue(Settings.LYRICS_ENABLED.get());
        assertEquals("gpt-4o-mini", Settings.LYRICS_AI_MODEL.get());
    }

    @Test
    public void normalizeForMatchWorks() {
        assertEquals("hello", LyricsRequests.normalizeForMatch("  HELLO  "));
    }

    @Test
    public void trackOffsetStoreReadsAndWrites() {
        TrackInfo track = new TrackInfo("Title", "Artist", "Album", 120);
        assertEquals(0, TrackOffsetStore.get(track));
        TrackOffsetStore.put(track, 500);
        assertEquals(500, TrackOffsetStore.get(track));
        TrackOffsetStore.put(track, 0);
        assertEquals(0, TrackOffsetStore.get(track));
    }

    @Test
    public void lyricsDirectoriesCreatesTempDir() {
        File dir = LyricsDirectories.directory(LyricsDirectories.OFFLINE);
        assertNotNull(dir);
        assertTrue(dir.exists());
        assertTrue(dir.isDirectory());
    }

    @Test
    public void calculateDriftSpeedFactorWorks() {
        assertEquals(1.0f, LyricsManager.calculateDriftSpeedFactor(0L), 0.001f);
        assertEquals(1.0f, LyricsManager.calculateDriftSpeedFactor(-15L), 0.001f);
        assertEquals(0.94f, LyricsManager.calculateDriftSpeedFactor(-60L), 0.001f);
        assertEquals(0.90f, LyricsManager.calculateDriftSpeedFactor(-100L), 0.001f);
        assertEquals(0.90f, LyricsManager.calculateDriftSpeedFactor(-300L), 0.001f);
    }
}
