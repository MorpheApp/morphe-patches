/* Copyright 2026 Morphe. See the included NOTICE file for GPLv3 Section 7 terms that apply to this code. */

package app.morphe.extension.music.patches.lyrics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsManager.ProviderFetch;
import app.morphe.extension.music.patches.lyrics.LyricsManager.ScoredCandidate;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests;

/**
 * Tests for {@link DisplayState}: the accept path that filters, scores and ranks provider
 * fetches, updating the best fallback and display candidates. Custom match mode toggles a
 * static flag on {@link LyricsRequests}, so it is reset in {@code @Before} and {@code @After}
 * to avoid leaking state into other test classes.
 */
public class DisplayStateTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        LyricsRequests.setCustomMatchMode(false);
    }

    @After
    public void tearDown() {
        LyricsRequests.setCustomMatchMode(false);
    }

    // ── helpers ──

    /** A synced lyrics object with word timings (syncRank 2). */
    private static Lyrics wordTimedLyrics() {
        return LyricsFixtures.synced(LyricsFixtures.wordTimedLine(0, 5000, "hello world"));
    }

    /** A synced lyrics object without word timings (syncRank 1). */
    private static Lyrics syncedLyrics() {
        return LyricsFixtures.synced(LyricsFixtures.line(0, "hello world"));
    }

    private static ProviderFetch highMatch(Lyrics lyrics) {
        return new ProviderFetch(lyrics, 80, true, false, 0);
    }

    private static ProviderFetch blindFetch(Lyrics lyrics) {
        return new ProviderFetch(lyrics, 70, false, true, 0);
    }

    private static ProviderFetch neither(Lyrics lyrics) {
        return new ProviderFetch(lyrics, 80, false, false, 0);
    }

    // ── accept: gating ──

    @Test
    public void nullPfReturnsFalse() {
        DisplayState state = new DisplayState(false, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        assertFalse(state.accept(null, candidates, true));
        assertTrue(candidates.isEmpty());
    }

    @Test
    public void nonHighMatchNonBlindReturnsFalse() {
        DisplayState state = new DisplayState(false, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        assertFalse(state.accept(neither(syncedLyrics()), candidates, true));
        assertTrue(candidates.isEmpty());
    }

    @Test
    public void invalidLyricsReturnsFalse() {
        DisplayState state = new DisplayState(false, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        ProviderFetch pf = new ProviderFetch(Lyrics.NOT_FOUND, 80, true, false, 0);
        assertFalse(state.accept(pf, candidates, true));
        assertTrue(candidates.isEmpty());
    }

    // ── accept: candidate appending ──

    @Test
    public void validHighMatchAppendsScoredCandidate() {
        DisplayState state = new DisplayState(false, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = syncedLyrics();
        // Non-wordSync, non-exhaustive, rank 1: returns true only if stage12 is true.
        assertTrue(state.accept(highMatch(lyrics), candidates, true));
        assertEquals(1, candidates.size());
        ScoredCandidate sc = candidates.get(0);
        // composite = 80*10 + 1 - 0 = 801, rank = 1
        assertEquals(801, sc.score());
        assertEquals(1, sc.rank());
        assertSame(lyrics, sc.lyrics());
    }

    // ── accept: custom match mode ──

    @Test
    public void customMatchModeUpdatesBestByCompositeAndReturnsFalse() {
        LyricsRequests.setCustomMatchMode(true);
        DisplayState state = new DisplayState(false, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = syncedLyrics();
        assertFalse(state.accept(highMatch(lyrics), candidates, true));
        assertEquals(1, candidates.size());
        // composite = 80*10 + 1 - 0 = 801; both bestFallback and bestResult update by composite
        assertSame(lyrics, state.bestFallback);
        assertEquals(801, state.bestFallbackRank);
        assertSame(lyrics, state.bestResult);
        assertEquals(801, state.bestDisplayRank);
    }

    @Test
    public void customMatchModeReturnsFalseEvenForRank2() {
        LyricsRequests.setCustomMatchMode(true);
        DisplayState state = new DisplayState(true, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = wordTimedLyrics();
        assertFalse(state.accept(highMatch(lyrics), candidates, true));
        assertSame(lyrics, state.bestResult);
    }

    // ── accept: blind fetch ──

    @Test
    public void blindFetchAppendsCandidateButReturnsFalse() {
        DisplayState state = new DisplayState(false, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = syncedLyrics();
        // Blind fetch passes the gate (blind=true) but returns false before updating bestFallback.
        assertFalse(state.accept(blindFetch(lyrics), candidates, true));
        assertEquals(1, candidates.size());
        assertNull(state.bestFallback);
        assertNull(state.bestResult);
    }

    // ── accept: wordSync branch ──

    @Test
    public void wordSyncRank2SetsBestResultReturnsTrueWhenNotExhaustive() {
        DisplayState state = new DisplayState(true, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = wordTimedLyrics();
        assertTrue(state.accept(highMatch(lyrics), candidates, true));
        assertSame(lyrics, state.bestResult);
        assertSame(lyrics, state.bestFallback);
        assertEquals(2, state.bestFallbackRank);
    }

    @Test
    public void wordSyncRank2ReturnsFalseWhenExhaustive() {
        DisplayState state = new DisplayState(true, true);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = wordTimedLyrics();
        assertFalse(state.accept(highMatch(lyrics), candidates, true));
        assertSame(lyrics, state.bestResult);
    }

    @Test
    public void wordSyncNonRank2ReturnsFalse() {
        DisplayState state = new DisplayState(true, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = syncedLyrics();
        assertFalse(state.accept(highMatch(lyrics), candidates, true));
        assertNull(state.bestResult);
        assertSame(lyrics, state.bestFallback);
        assertEquals(1, state.bestFallbackRank);
    }

    // ── accept: non-wordSync branch ──

    @Test
    public void nonWordSyncRank1ReturnsTrueWhenStage12AndNotExhaustive() {
        DisplayState state = new DisplayState(false, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = syncedLyrics();
        assertTrue(state.accept(highMatch(lyrics), candidates, true));
        assertSame(lyrics, state.bestResult);
        assertEquals(1, state.bestDisplayRank);
        assertSame(lyrics, state.bestFallback);
        assertEquals(1, state.bestFallbackRank);
    }

    @Test
    public void nonWordSyncExhaustiveReturnsFalse() {
        DisplayState state = new DisplayState(false, true);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = syncedLyrics();
        assertFalse(state.accept(highMatch(lyrics), candidates, true));
        // bestResult is still set even though the return is false.
        assertSame(lyrics, state.bestResult);
    }

    @Test
    public void nonWordSyncRank0ReturnsFalse() {
        DisplayState state = new DisplayState(false, false);
        List<ScoredCandidate> candidates = new ArrayList<>();
        Lyrics lyrics = LyricsFixtures.plain("hello world");
        assertFalse(state.accept(highMatch(lyrics), candidates, true));
        // rank 0 sets bestResult (0 > -2) but returns false because rank != 1.
        assertSame(lyrics, state.bestResult);
        assertEquals(0, state.bestDisplayRank);
    }
}
