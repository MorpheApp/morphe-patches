/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

public class LyricsSectionTagsTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    private static LyricsLine line(long startMs, long endMs, String text, boolean isBG) {
        return new LyricsLine(startMs, endMs, text, List.of(), null, false, isBG, null);
    }

    @Test
    public void indexZeroIsNeverSecondary() {
        List<LyricsLine> lines = List.of(line(0, 5000, "hello", false));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(0, lines));
    }

    @Test
    public void indexOutOfBoundsIsNeverSecondary() {
        List<LyricsLine> lines = List.of(line(0, 5000, "hello", false));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void normalLineNotSecondary() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(1000, 5000, "world", false));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void echoSameTextWithinGapIsSecondary() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(1000, 5000, "hello", false));
        assertTrue(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void echoSameTextBeyondGapNotSecondary() {
        // ECHO_MAX_GAP_MS is 3500; a 5000 ms gap is too far to be an echo.
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(5000, 5000, "hello", false));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void echoDifferentTextNotSecondary() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(1000, 5000, "world", false));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void bracketedVocalIsSecondary() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(100, 5000, "(oh)", false));
        assertTrue(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void backgroundVocalIsSecondary() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(100, 5000, "oh", true));
        assertTrue(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void tagBracketsNotAVocalCandidate() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(100, 5000, "【Chorus】", false));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void backgroundVocalWithoutValidParentNotSecondary() {
        // Both lines are background vocals, so the second has no lead line to attach to.
        List<LyricsLine> lines = List.of(
                line(0, 5000, "oh", true),
                line(100, 5000, "ah", true));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void bracketedVocalWithinWindowIsSecondary() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(100, 5000, "(oh)", false));
        assertTrue(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void bracketedVocalTooFarAfterLeadNotSecondary() {
        // Starts more than BG_MAX_LAG_MS (30000) after the lead line.
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(40000, 5000, "(oh)", false));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void bracketedVocalTooFarAheadOfLeadNotSecondary() {
        // Starts more than BG_MAX_LEAD_MS (2000) ahead of the lead line.
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(-3000, 5000, "(oh)", false));
        assertFalse(LyricsSectionTags.isSecondaryLyricLine(1, lines));
    }

    @Test
    public void secondaryLineFlagsClassifiesEachLine() {
        List<LyricsLine> lines = List.of(
                line(0, 5000, "hello", false),
                line(1000, 5000, "hello", false),
                line(200, 5000, "(oh)", false),
                line(20000, 5000, "again", false));
        boolean[] flags = LyricsSectionTags.secondaryLineFlags(lines);
        assertFalse(flags[0]);
        assertTrue(flags[1]);
        assertTrue(flags[2]);
        assertFalse(flags[3]);
    }

    @Test
    public void bgBeforeMainTrueWhenVocalStartsAhead() {
        List<LyricsLine> lines = List.of(
                line(1000, 5000, "hello", false),
                line(200, 5000, "oh", true));
        assertTrue(LyricsSectionTags.isBgBeforeMain(lines, 0, 1));
    }

    @Test
    public void bgBeforeMainFalseWhenVocalStartsAfter() {
        List<LyricsLine> lines = List.of(
                line(200, 5000, "hello", false),
                line(1000, 5000, "oh", true));
        assertFalse(LyricsSectionTags.isBgBeforeMain(lines, 0, 1));
    }

    @Test
    public void bgBeforeMainFalseWhenVocalStartsAtSameTime() {
        List<LyricsLine> lines = List.of(
                line(1000, 5000, "hello", false),
                line(1000, 5000, "oh", true));
        assertFalse(LyricsSectionTags.isBgBeforeMain(lines, 0, 1));
    }

    @Test
    public void bgBeforeMainFalseForInvalidIndices() {
        List<LyricsLine> lines = List.of(line(1000, 5000, "hello", false));
        assertFalse(LyricsSectionTags.isBgBeforeMain(lines, -1, 0));
        assertFalse(LyricsSectionTags.isBgBeforeMain(lines, 0, 1));
        assertFalse(LyricsSectionTags.isBgBeforeMain(lines, 1, 0));
    }
}
