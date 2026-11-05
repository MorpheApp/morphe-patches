/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;
import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

/** Tests for {@link LyricsFilter}, focusing on the #3518 regression where the credit-line gap tolerance must not filter legitimate third-party lyrics. Credit lines use a colon separator because {@link LyricsFilter#isCreditLine} requires a real separator after the credit label. */
public class LyricsFilterTest {

    private TrackInfo track;

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        track = LyricsFixtures.track("My Song", "My Artist");
    }

    // ── filterCreditLines: null / empty / NOT_FOUND ──

    @Test
    public void filterCreditLinesNullReturnsNull() {
        assertNull(LyricsFilter.filterCreditLines(null, track));
    }

    @Test
    public void filterCreditLinesNotFoundReturnsNotFound() {
        assertSame(Lyrics.NOT_FOUND, LyricsFilter.filterCreditLines(Lyrics.NOT_FOUND, track));
    }

    @Test
    public void filterCreditLinesEmptyReturnsEmpty() {
        Lyrics empty = LyricsFixtures.synced();
        assertSame(empty, LyricsFilter.filterCreditLines(empty, track));
    }

    // ── filterCreditLines: no credit lines → unchanged ──

    @Test
    public void filterCreditLinesNoCreditsReturnsUnchanged() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first line of lyrics"),
                new LyricsLine(1000, "second line of lyrics"),
                new LyricsLine(2000, "third line of lyrics"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        assertSame(lyrics, result);
    }

    // ── filterCreditLines: #3518 regression: gap tolerance ──

    @Test
    public void filterCreditLinesGapToleranceAllows3NonCreditLines() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "Produced: Someone"),
                new LyricsLine(1000, "first real line"),
                new LyricsLine(2000, "second real line"),
                new LyricsLine(3000, "third real line"),
                new LyricsLine(4000, "Produced: Another"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        // Gap of 3 ≤ tolerance of 3: both credit lines are in the same block and filtered.
        assertEquals(3, result.lines().size());
        assertEquals("first real line", result.lines().get(0).text());
        assertEquals("second real line", result.lines().get(1).text());
        assertEquals("third real line", result.lines().get(2).text());
    }

    @Test
    public void filterCreditLinesGapToleranceBreaksAfter4NonCreditLines() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "Produced: Someone"),
                new LyricsLine(1000, "first real line"),
                new LyricsLine(2000, "second real line"),
                new LyricsLine(3000, "third real line"),
                new LyricsLine(4000, "fourth real line"),
                new LyricsLine(5000, "Produced: Another"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        // Gap of 4 > tolerance of 3: the block breaks, so both credit lines are filtered and the 4 lines are kept.
        assertEquals(4, result.lines().size());
        assertEquals("first real line", result.lines().get(0).text());
        assertEquals("second real line", result.lines().get(1).text());
        assertEquals("third real line", result.lines().get(2).text());
        assertEquals("fourth real line", result.lines().get(3).text());
    }

    @Test
    public void filterCreditLinesGapToleranceFiltersLeadingNonCreditBeforeCreditBlock() {
        // Gap tolerance extends the start block past line 0 so the credit line is recognized and filtered.
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "intro text"),
                new LyricsLine(1000, "Produced: Someone"),
                new LyricsLine(2000, "first real line"),
                new LyricsLine(3000, "second real line"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        assertEquals(2, result.lines().size());
        assertEquals("first real line", result.lines().get(0).text());
        assertEquals("second real line", result.lines().get(1).text());
    }

    @Test
    public void filterCreditLinesNoCreditsAmongLyricsUnchanged() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first line of lyrics"),
                new LyricsLine(1000, "second line of lyrics"),
                new LyricsLine(2000, "third line of lyrics"),
                new LyricsLine(3000, "fourth line of lyrics"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        assertSame(lyrics, result);
    }

    // ── filterCreditLines: artist-song pattern ──

    @Test
    public void filterCreditLinesArtistSongPattern() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "My Artist - My Song"),
                new LyricsLine(1000, "first real line"),
                new LyricsLine(2000, "second real line"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        assertEquals(2, result.lines().size());
    }

    @Test
    public void filterCreditLinesArtistSongPatternAtEnd() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "first real line"),
                new LyricsLine(1000, "second real line"),
                new LyricsLine(2000, "My Artist - My Song"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        assertEquals(2, result.lines().size());
    }

    // ── filterCreditLines: slash count ──

    @Test
    public void filterCreditLinesHighSlashCount() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "a/b/c/d/e/f/g"),
                new LyricsLine(1000, "first real line"),
                new LyricsLine(2000, "second real line"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        assertEquals(2, result.lines().size());
    }

    // ── filterCreditLines: moves credits to credits field ──

    @Test
    public void filterCreditLinesMovesCreditsToCredits() {
        Lyrics lyrics = LyricsFixtures.synced(
                new LyricsLine(0, "Produced: Someone"),
                new LyricsLine(1000, "first real line"),
                new LyricsLine(2000, "second real line"));
        Lyrics result = LyricsFilter.filterCreditLines(lyrics, track);
        assertNotNull(result.credits());
        assertTrue(result.credits().contains("Produced: Someone"));
        // Credit lines should NOT go to songwriters
        assertNull(result.songwriters());
        assertEquals(2, result.lines().size());
    }

    // ── filterLyricsText ──

    @Test
    public void filterLyricsTextNullReturnsNull() {
        assertNull(LyricsFilter.filterLyricsText(null));
    }

    @Test
    public void filterLyricsTextNotFoundReturnsNotFound() {
        assertSame(Lyrics.NOT_FOUND, LyricsFilter.filterLyricsText(Lyrics.NOT_FOUND));
    }

    @Test
    public void filterLyricsTextReturnsLyrics() {
        Lyrics lyrics = LyricsFixtures.synced(new LyricsLine(0, "hello world"));
        Lyrics result = LyricsFilter.filterLyricsText(lyrics);
        assertNotNull(result);
    }
}
