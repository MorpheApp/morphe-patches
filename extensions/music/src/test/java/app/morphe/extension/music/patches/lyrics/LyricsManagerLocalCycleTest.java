/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.requests.LocalLyricsFetcher;

public class LyricsManagerLocalCycleTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    private static Lyrics lyrics(String provider, String text) {
        return new Lyrics(List.of(new LyricsLine(1000, text)), provider, true,
                null, null, null, null, null, "lrc", null);
    }

    @Test
    public void wrapsWhenNothingIsShown() {
        assertTrue(LyricsManager.shouldWrapToLocal(
                null, lyrics(LocalLyricsFetcher.PROVIDER_NAME, "local line")));
    }

    @Test
    public void wrapsFromAProviderResult() {
        assertTrue(LyricsManager.shouldWrapToLocal(
                lyrics("LRCLIB", "provider line"),
                lyrics(LocalLyricsFetcher.PROVIDER_NAME, "local line")));
    }

    @Test
    public void doesNotWrapWhenLocalIsAlreadyShown() {
        Lyrics local = lyrics(LocalLyricsFetcher.PROVIDER_NAME, "local line");
        assertFalse(LyricsManager.shouldWrapToLocal(local, local));
    }

    @Test
    public void doesNotWrapWithoutLocalLyrics() {
        assertFalse(LyricsManager.shouldWrapToLocal(lyrics("LRCLIB", "provider line"), null));
    }

    @Test
    public void doesNotWrapToEmptyLocalLyrics() {
        assertFalse(LyricsManager.shouldWrapToLocal(
                lyrics("LRCLIB", "provider line"), Lyrics.NOT_FOUND));
    }
}
