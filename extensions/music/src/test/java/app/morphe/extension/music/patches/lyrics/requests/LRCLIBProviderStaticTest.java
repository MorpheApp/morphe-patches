/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4002
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class LRCLIBProviderStaticTest {
    private static Lyrics lyrics(boolean synced) {
        return new Lyrics(List.of(new LyricsLine(synced ? 1000 : -1, "line")), "LRCLIB", synced,
                null, null, null, null, null, "lrc", null);
    }

    private static Lyrics wordSyncedLyrics() {
        return new Lyrics(List.of(new LyricsLine(1000, "line",
                List.of(new Word(1000, 1200, "li"), new Word(1200, 1400, "ne")))),
                "LRCLIB", true, null, null, null, null, null, "lrc", null);
    }

    @Test
    public void syncedBeatsAHigherScoredPlainResult() {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        scored.add(new Lyrics.ScoredLyrics(100, lyrics(false)));
        scored.add(new Lyrics.ScoredLyrics(40, lyrics(true)));
        scored.add(new Lyrics.ScoredLyrics(60, lyrics(true)));
        List<Lyrics.ScoredLyrics> ordered = LRCLIBProvider.syncedFirst(scored);
        assertTrue(ordered.get(0).lyrics().synced());
        assertEquals(60, ordered.get(0).score());
        assertEquals(40, ordered.get(1).score());
        assertFalse(ordered.get(2).lyrics().synced());
    }

    @Test
    public void wordTimingsBeatLineTimings() {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        scored.add(new Lyrics.ScoredLyrics(100, lyrics(false)));
        scored.add(new Lyrics.ScoredLyrics(90, lyrics(true)));
        scored.add(new Lyrics.ScoredLyrics(10, wordSyncedLyrics()));
        List<Lyrics.ScoredLyrics> ordered = LRCLIBProvider.syncedFirst(scored);
        assertEquals(10, ordered.get(0).score());
        assertEquals(90, ordered.get(1).score());
        assertEquals(100, ordered.get(2).score());
    }

    @Test
    public void allPlainKeepsScoreOrder() {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        scored.add(new Lyrics.ScoredLyrics(30, lyrics(false)));
        scored.add(new Lyrics.ScoredLyrics(100, lyrics(false)));
        scored.add(new Lyrics.ScoredLyrics(60, lyrics(false)));
        List<Lyrics.ScoredLyrics> ordered = LRCLIBProvider.syncedFirst(scored);
        assertEquals(100, ordered.get(0).score());
        assertEquals(60, ordered.get(1).score());
        assertEquals(30, ordered.get(2).score());
    }

    @Test
    public void emptyInputReturnsEmptyList() {
        assertTrue(LRCLIBProvider.syncedFirst(new ArrayList<>()).isEmpty());
    }

    @Test
    public void doesNotMutateInput() {
        List<Lyrics.ScoredLyrics> scored = new ArrayList<>();
        scored.add(new Lyrics.ScoredLyrics(100, lyrics(false)));
        scored.add(new Lyrics.ScoredLyrics(40, lyrics(true)));
        LRCLIBProvider.syncedFirst(scored);
        assertEquals(100, scored.get(0).score());
        assertEquals(40, scored.get(1).score());
    }
}
