/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsFixtures;
import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.TrackInfo;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests.Evidence;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests.MatchVerdict;
import app.morphe.extension.music.patches.lyrics.requests.LyricsRequests.PreparedTrack;

/** Unit tests for the match-scoring engine of {@link LyricsRequests}: rankScore, isHighMatch, custom match mode, evaluate, scoreTrackCandidate, scoreLyricsCandidate, prepare, PreparedTrack.evaluate, MatchVerdict.videoIdTrust. */
public class LyricsRequestsMatchTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
        LyricsRequests.setCustomMatchMode(false);
    }

    @After
    public void tearDown() {
        LyricsRequests.setCustomMatchMode(false);
    }

    // ── rankScore ──

    @Test
    public void rankScoreArtistMismatchReturnsTwo() {
        MatchVerdict v = new MatchVerdict(8, Evidence.EQUAL, Evidence.MISMATCH,
                Evidence.MATCH, Evidence.MISSING);
        assertEquals(2, LyricsRequests.rankScore(v));
    }

    @Test
    public void rankScoreArtistMatchScoreBelowFiveReturnsFive() {
        MatchVerdict v = new MatchVerdict(3, Evidence.MATCH, Evidence.MATCH,
                Evidence.MISSING, Evidence.MISSING);
        assertEquals(5, LyricsRequests.rankScore(v));
    }

    @Test
    public void rankScoreArtistMatchScoreAtLeastFiveReturnsScore() {
        MatchVerdict v = new MatchVerdict(8, Evidence.EQUAL, Evidence.MATCH,
                Evidence.MATCH, Evidence.MATCH);
        assertEquals(8, LyricsRequests.rankScore(v));
    }

    @Test
    public void rankScoreArtistPartialNotMismatch() {
        // PARTIAL is not MISMATCH, so it should use max(score, 5)
        MatchVerdict v = new MatchVerdict(4, Evidence.EQUAL, Evidence.PARTIAL,
                Evidence.MATCH, Evidence.MISSING);
        assertEquals(5, LyricsRequests.rankScore(v));
    }

    // ── isHighMatch ──

    @Test
    public void isHighMatchScoreAtLeastSeven() {
        MatchVerdict v = new MatchVerdict(8, Evidence.EQUAL, Evidence.MATCH,
                Evidence.MATCH, Evidence.MISSING);
        assertTrue(LyricsRequests.isHighMatch(v));
    }

    @Test
    public void isHighMatchScoreBelowSevenTitleEqual() {
        MatchVerdict v = new MatchVerdict(5, Evidence.EQUAL, Evidence.MATCH,
                Evidence.MATCH, Evidence.MISSING);
        assertTrue(LyricsRequests.isHighMatch(v));
    }

    @Test
    public void isHighMatchScoreBelowSevenTitleNotEqual() {
        MatchVerdict v = new MatchVerdict(5, Evidence.MATCH, Evidence.MATCH,
                Evidence.MISSING, Evidence.MISSING);
        assertFalse(LyricsRequests.isHighMatch(v));
    }

    @Test
    public void isHighMatchArtistMismatchVeto() {
        MatchVerdict v = new MatchVerdict(10, Evidence.EQUAL, Evidence.MISMATCH,
                Evidence.MATCH, Evidence.MATCH);
        assertFalse(LyricsRequests.isHighMatch(v));
    }

    @Test
    public void isHighMatchTitleNone() {
        MatchVerdict v = new MatchVerdict(0, Evidence.NONE, Evidence.MISSING,
                Evidence.MISSING, Evidence.MISSING);
        assertFalse(LyricsRequests.isHighMatch(v));
    }

    @Test
    public void isHighMatchTitleMissing() {
        MatchVerdict v = new MatchVerdict(0, Evidence.MISSING, Evidence.MISSING,
                Evidence.MISSING, Evidence.MISSING);
        assertFalse(LyricsRequests.isHighMatch(v));
    }

    @Test
    public void isHighMatchScoreAtLeastSevenDurationMismatchArtistNotMatch() {
        // Score >= 7 but duration MISMATCH and artist != MATCH → false
        MatchVerdict v = new MatchVerdict(8, Evidence.EQUAL, Evidence.PARTIAL,
                Evidence.MISMATCH, Evidence.MISSING);
        assertFalse(LyricsRequests.isHighMatch(v));
    }

    @Test
    public void isHighMatchCustomModeBypass() {
        LyricsRequests.setCustomMatchMode(true);
        MatchVerdict v = new MatchVerdict(0, Evidence.NONE, Evidence.MISSING,
                Evidence.MISSING, Evidence.MISSING);
        assertTrue(LyricsRequests.isHighMatch(v));
    }

    // ── isCustomMatchMode / setCustomMatchMode ──

    @Test
    public void isCustomMatchModeInitiallyFalse() {
        assertFalse(LyricsRequests.isCustomMatchMode());
    }

    @Test
    public void isCustomMatchModeAfterSetTrue() {
        LyricsRequests.setCustomMatchMode(true);
        assertTrue(LyricsRequests.isCustomMatchMode());
    }

    @Test
    public void isCustomMatchModeAfterSetFalse() {
        LyricsRequests.setCustomMatchMode(true);
        LyricsRequests.setCustomMatchMode(false);
        assertFalse(LyricsRequests.isCustomMatchMode());
    }

    // ── evaluate ──

    @Test
    public void evaluateMatchingTitleArtistDuration() {
        TrackInfo track = new TrackInfo("Hello", "World", "Album", 180);
        MatchVerdict v = LyricsRequests.evaluate("Hello", "World", 180, track);
        assertEquals(Evidence.EQUAL, v.title());
        assertEquals(Evidence.MATCH, v.artist());
        assertEquals(Evidence.MATCH, v.duration());
        assertTrue(v.score() >= 10);
    }

    @Test
    public void evaluateNonMatchingTitle() {
        TrackInfo track = new TrackInfo("Hello", "World", "Album", 180);
        MatchVerdict v = LyricsRequests.evaluate("Goodbye", "World", 180, track);
        assertEquals(Evidence.NONE, v.title());
        assertEquals(Evidence.MATCH, v.artist());
        assertEquals(Evidence.MATCH, v.duration());
    }

    @Test
    public void evaluateNonMatchingArtist() {
        TrackInfo track = new TrackInfo("Hello", "World", "Album", 180);
        MatchVerdict v = LyricsRequests.evaluate("Hello", "Evil", 180, track);
        assertEquals(Evidence.EQUAL, v.title());
        assertEquals(Evidence.MISMATCH, v.artist());
        assertEquals(Evidence.MATCH, v.duration());
    }

    // ── scoreTrackCandidate ──

    @Test
    public void scoreTrackCandidateGoodMatch() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        int score = LyricsRequests.scoreTrackCandidate("Hello", "World", 180, track);
        assertTrue(score > 0);
    }

    @Test
    public void scoreTrackCandidateBadMatchReturnsNegativeOne() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        int score = LyricsRequests.scoreTrackCandidate("Goodbye", "Evil", 999, track);
        assertEquals(-1, score);
    }

    @Test
    public void scoreTrackCandidateWithAlbum() {
        TrackInfo track = new TrackInfo("Hello", "World", "Album", 180);
        int score = LyricsRequests.scoreTrackCandidate("Hello", "World", 180, "Album", track);
        assertTrue(score > 0);
    }

    @Test
    public void scoreTrackCandidateCustomModeReturnsRankScore() {
        LyricsRequests.setCustomMatchMode(true);
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        // Bad match but custom mode → rankScore instead of -1
        int score = LyricsRequests.scoreTrackCandidate("Goodbye", "Evil", 999, track);
        assertEquals(2, score); // artist MISMATCH → rankScore returns 2
    }

    // ── scoreLyricsCandidate ──

    @Test
    public void scoreLyricsCandidateSyncedWithWordTimings() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        Lyrics lyrics = LyricsFixtures.synced(
                LyricsFixtures.wordTimedLine(1000, 2000, "Hello World"));
        int score = LyricsRequests.scoreLyricsCandidate("Hello", "World", 180, lyrics, track);
        assertTrue(score > 10); // match score (10) + syncRank (2) = 12
    }

    @Test
    public void scoreLyricsCandidateSyncedNoWordTimings() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        Lyrics lyrics = LyricsFixtures.synced(
                LyricsFixtures.line(1000, "Hello"));
        int score = LyricsRequests.scoreLyricsCandidate("Hello", "World", 180, lyrics, track);
        assertEquals(11, score); // match score (10) + syncRank (1) = 11
    }

    @Test
    public void scoreLyricsCandidateUnsynced() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        Lyrics lyrics = LyricsFixtures.plain("Hello");
        int score = LyricsRequests.scoreLyricsCandidate("Hello", "World", 180, lyrics, track);
        assertEquals(10, score); // match score (10) + syncRank (0) = 10
    }

    @Test
    public void scoreLyricsCandidateBadMatchReturnsNegativeOne() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        Lyrics lyrics = LyricsFixtures.synced(
                LyricsFixtures.line(1000, "Hello"));
        int score = LyricsRequests.scoreLyricsCandidate("Goodbye", "Evil", 999, lyrics, track);
        assertEquals(-1, score);
    }

    // ── prepare ──

    @Test
    public void prepareReturnsNormalizedTitleAndArtist() {
        TrackInfo track = new TrackInfo("Hello", "World", "Album", 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        assertEquals("hello", prepared.title);
        assertEquals("world", prepared.artist);
        assertEquals("album", prepared.album);
        assertEquals(180, prepared.durationSec);
        assertTrue(prepared.titleValid);
    }

    @Test
    public void prepareEmptyTitleIsInvalid() {
        TrackInfo track = new TrackInfo("", "World", "Album", 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        assertTrue(prepared.title.isEmpty());
        assertFalse(prepared.titleValid);
    }

    // ── PreparedTrack.evaluate ──

    @Test
    public void preparedTrackEvaluateExactMatch() {
        TrackInfo track = new TrackInfo("Hello", "World", "Album", 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        MatchVerdict v = prepared.evaluate("Hello", "World", 180, "Album");
        assertEquals(Evidence.EQUAL, v.title());
        assertEquals(Evidence.MATCH, v.artist());
        assertEquals(Evidence.MATCH, v.duration());
        assertEquals(Evidence.MATCH, v.album());
        assertEquals(11, v.score()); // 5 + 3 + 2 + 1
    }

    @Test
    public void preparedTrackEvaluatePartialTitleMatch() {
        TrackInfo track = new TrackInfo("Hello World", "Band", null, 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        MatchVerdict v = prepared.evaluate("Hello Worlds", "Band", 180, null);
        assertEquals(Evidence.MATCH, v.title());
        assertEquals(Evidence.MATCH, v.artist());
    }

    @Test
    public void preparedTrackEvaluateNoMatch() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        MatchVerdict v = prepared.evaluate("Goodbye", "Evil", 999, null);
        assertEquals(Evidence.NONE, v.title());
        assertEquals(Evidence.MISMATCH, v.artist());
        assertEquals(Evidence.MISMATCH, v.duration());
        assertEquals(0, v.score());
    }

    @Test
    public void preparedTrackEvaluateAlbumMismatch() {
        TrackInfo track = new TrackInfo("Hello", "World", "Album", 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        MatchVerdict v = prepared.evaluate("Hello", "World", 180, "Other");
        assertEquals(Evidence.MISMATCH, v.album());
        assertEquals(10, v.score()); // 5 + 3 + 2 + 0
    }

    @Test
    public void preparedTrackEvaluateMissingDuration() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        MatchVerdict v = prepared.evaluate("Hello", "World", 0, null);
        assertEquals(Evidence.MISSING, v.duration());
    }

    @Test
    public void preparedTrackEvaluateDurationDeltaWithinTwoSeconds() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        MatchVerdict v = prepared.evaluate("Hello", "World", 181, null);
        assertEquals(Evidence.MATCH, v.duration());
    }

    @Test
    public void preparedTrackEvaluateDurationDeltaOverFifteenSeconds() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        MatchVerdict v = prepared.evaluate("Hello", "World", 200, null);
        assertEquals(Evidence.MISMATCH, v.duration());
    }

    @Test
    public void preparedTrackEvaluateEmptyCandidateTitle() {
        TrackInfo track = new TrackInfo("Hello", "World", null, 180);
        PreparedTrack prepared = LyricsRequests.prepare(track);
        MatchVerdict v = prepared.evaluate("", "World", 180, null);
        assertEquals(Evidence.NONE, v.title());
        assertEquals(5, v.score()); // title 0 + artist 3 + duration 2 + album 0
    }

    // ── MatchVerdict.videoIdTrust ──

    @Test
    public void matchVerdictVideoIdTrust() {
        MatchVerdict v = MatchVerdict.videoIdTrust();
        assertEquals(LyricsRequests.VIDEO_ID_TRUST, v.score());
        assertEquals(Evidence.MATCH, v.title());
        assertEquals(Evidence.MISSING, v.artist());
        assertEquals(Evidence.MISSING, v.duration());
        assertEquals(Evidence.MISSING, v.album());
    }
}
