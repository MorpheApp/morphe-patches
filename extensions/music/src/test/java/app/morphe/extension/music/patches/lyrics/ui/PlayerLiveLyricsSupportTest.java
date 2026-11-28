/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/5527
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.ui.GapLineView.GapInterval;
import app.morphe.extension.music.patches.lyrics.ui.PlayerLiveLyricsSupport.DotPhase;
import app.morphe.extension.music.patches.lyrics.ui.PlayerLiveLyricsSupport.GapKind;
import app.morphe.extension.music.patches.lyrics.ui.PlayerLiveLyricsSupport.StateSnapshot;

public class PlayerLiveLyricsSupportTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void resolveStateHiddenWhenNullOrUnsynced() {
        assertFalse(PlayerLiveLyricsSupport.resolveState(null, null, 1000L, -1).visible());

        Lyrics unsynced = new Lyrics(
                List.of(new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME, "Plain line",
                        List.of(), null, false, false, null)),
                "LRCLIB", false, null, null, null, null, null, null, null);
        assertFalse(PlayerLiveLyricsSupport.resolveState(unsynced, null, 1000L, -1).visible());
        assertFalse(PlayerLiveLyricsSupport.resolveState(Lyrics.NOT_FOUND, null, 1000L, -1).visible());
    }

    @Test
    public void resolveStateShowsGapBeforeFirstLineAndDuringIntervalAndOutro() {
        Lyrics synced = new Lyrics(
                List.of(
                        new LyricsLine(6000L, 8500L, "First line", List.of(), null, false, false, null),
                        new LyricsLine(16000L, 19000L, "Last line", List.of(), null, false, false, null)
                ),
                "LRCLIB", true, null, null, null, null, null, null, null);
        List<GapInterval> gaps = List.of(
                new GapInterval(0L, 5220L, 6000L, 0, false, false, false),
                new GapInterval(9000L, 15500L, 16000L, 1, false, false, false));

        StateSnapshot intro = PlayerLiveLyricsSupport.resolveState(synced, gaps, 2000L, -1);
        assertTrue(intro.visible());
        assertTrue(intro.isGap());
        assertEquals(GapKind.INTRO, intro.gapKind());
        assertFalse(intro.gapRightAligned());
        assertEquals(0L, intro.gapStartMs());
        assertEquals(6000L, intro.gapNextStartMs());

        StateSnapshot first = PlayerLiveLyricsSupport.resolveState(synced, gaps, 6500L, -1);
        assertTrue(first.visible());
        assertFalse(first.isGap());
        assertEquals(GapKind.NONE, first.gapKind());
        assertEquals(0, first.lineIndex());

        StateSnapshot midGap = PlayerLiveLyricsSupport.resolveState(synced, gaps, 11250L, 0);
        assertTrue(midGap.visible());
        assertTrue(midGap.isGap());
        assertEquals(GapKind.INTERLUDE, midGap.gapKind());
        assertEquals(9000L, midGap.gapStartMs());
        assertEquals(15500L, midGap.gapEndMs());

        StateSnapshot last = PlayerLiveLyricsSupport.resolveState(synced, gaps, 17500L, 0);
        assertTrue(last.visible());
        assertFalse(last.isGap());
        assertEquals(GapKind.NONE, last.gapKind());
        assertEquals(1, last.lineIndex());

        StateSnapshot outro = PlayerLiveLyricsSupport.resolveState(synced, gaps, 22000L, 1);
        assertTrue(outro.visible());
        assertTrue(outro.isGap());
        assertEquals(GapKind.OUTRO, outro.gapKind());
    }

    @Test
    public void resolveStateShowsFirstLineDimmedOnShortPreRollAndRightAlignsRtlGap() {
        Lyrics shortIntroRtl = new Lyrics(
                List.of(new LyricsLine(900L, 3500L, "عليك يا رسول الله", List.of(), null, false, false, null)),
                "LRCLIB", true, null, null, null, null, null, null, null);
        List<GapInterval> noIntroGap = List.of();

        StateSnapshot preRoll = PlayerLiveLyricsSupport.resolveState(shortIntroRtl, noIntroGap, 200L, -1);
        assertTrue(preRoll.visible());
        assertFalse(preRoll.isGap());
        assertEquals(0, preRoll.lineIndex());

        StateSnapshot outro = PlayerLiveLyricsSupport.resolveState(shortIntroRtl, noIntroGap, 6000L, 0);
        assertTrue(outro.visible());
        assertTrue(outro.isGap());
        assertTrue(outro.gapRightAligned());
    }

    @Test
    public void resolveStateOmitsGapsAndOutroWhenGapIndicatorsDisabled() {
        Lyrics synced = new Lyrics(
                List.of(
                        new LyricsLine(6000L, 8500L, "First line", List.of(), null, false, false, null),
                        new LyricsLine(16000L, 19000L, "Last line", List.of(), null, false, false, null)
                ),
                "LRCLIB", true, null, null, null, null, null, null, null);
        List<GapInterval> gaps = List.of(
                new GapInterval(0L, 5220L, 6000L, 0, false, false, false),
                new GapInterval(9000L, 15500L, 16000L, 1, false, false, false));

        StateSnapshot intro = PlayerLiveLyricsSupport.resolveState(synced, gaps, 2000L, -1, false);
        assertTrue(intro.visible());
        assertFalse(intro.isGap());
        assertEquals(0, intro.lineIndex());

        StateSnapshot interlude = PlayerLiveLyricsSupport.resolveState(synced, gaps, 11250L, 0, false);
        assertTrue(interlude.visible());
        assertFalse(interlude.isGap());
        assertEquals(0, interlude.lineIndex());

        StateSnapshot outro = PlayerLiveLyricsSupport.resolveState(synced, gaps, 22000L, 1, false);
        assertTrue(outro.visible());
        assertFalse(outro.isGap());
        assertEquals(1, outro.lineIndex());
    }

    @Test
    public void computeDotPhaseCoversBloomBreatheSequentialFillAndExit() {
        DotPhase enter = PlayerLiveLyricsSupport.computeDotPhase(1000L, 1000L, 10000L, 10780L, 160L, 320L);
        assertEquals(0.5f, enter.enterFraction(), 0.01f);
        assertFalse(enter.exiting());
        assertEquals(0f, enter.dot0Fraction(), 0.01f);

        DotPhase mid = PlayerLiveLyricsSupport.computeDotPhase(5500L, 1000L, 10000L, 10780L, 1000L, 320L);
        assertEquals(1.0f, mid.enterFraction(), 0.01f);
        assertEquals(1.0f, mid.dot0Fraction(), 0.01f);
        assertTrue(mid.dot1Fraction() > 0f && mid.dot1Fraction() < 1f);
        assertTrue(mid.breatheScale() > 0.9f && mid.breatheScale() <= 1.1f);
        assertFalse(mid.exiting());

        DotPhase exit = PlayerLiveLyricsSupport.computeDotPhase(10390L, 1000L, 10000L, 10780L, 4000L, 320L);
        assertTrue(exit.exiting());
        assertEquals(0.5f, exit.exitFraction(), 0.01f);
    }

    @Test
    public void heavyWordGateMatchesWordSweepAnimatorRules() {
        assertFalse(PlayerLiveLyricsSupport.isHeavyWord("short", 1000L, 1800L, false, false));
        assertFalse(PlayerLiveLyricsSupport.isHeavyWord("averaged", 1000L, 3000L, true, false));
        assertFalse(PlayerLiveLyricsSupport.isHeavyWord("مرحبا", 1000L, 3000L, false, true));
        assertFalse(PlayerLiveLyricsSupport.isHeavyWord("longphrase", 1000L, 3000L, false, false));
        assertTrue(PlayerLiveLyricsSupport.isHeavyWord("loose", 1000L, 2500L, false, false));
        assertEquals(6, PlayerLiveLyricsSupport.countNonWhitespaceChars("h e l l o !"));
    }

    @Test
    public void computeSweepXLtrAndRtlInterpolatesCleanly() {
        float[] ltrStartX = {0f, 50f, 120f};
        float[] ltrEndX = {45f, 110f, 200f};
        long[] startMs = {1000L, 2000L, 3500L};
        long[] endMs = {1800L, 3000L, 4500L};

        assertEquals(0f, PlayerLiveLyricsSupport.computeSweepX(
                ltrStartX, ltrEndX, startMs, endMs, 3, 200f, 500L, false), 0.01f);
        assertEquals(22.5f, PlayerLiveLyricsSupport.computeSweepX(
                ltrStartX, ltrEndX, startMs, endMs, 3, 200f, 1400L, false), 0.01f);
        assertEquals(45f, PlayerLiveLyricsSupport.computeSweepX(
                ltrStartX, ltrEndX, startMs, endMs, 3, 200f, 1900L, false), 0.01f);
        assertEquals(200f, PlayerLiveLyricsSupport.computeSweepX(
                ltrStartX, ltrEndX, startMs, endMs, 3, 200f, 5000L, false), 0.01f);

        float[] rtlStartX = {200f, 110f};
        float[] rtlEndX = {120f, 0f};
        long[] rtlStartMs = {1000L, 2000L};
        long[] rtlEndMs = {2000L, 3000L};
        assertEquals(160f, PlayerLiveLyricsSupport.computeSweepX(
                rtlStartX, rtlEndX, rtlStartMs, rtlEndMs, 2, 200f, 1500L, true), 0.01f);
    }

    @Test
    public void targetScrollClampsAndTracksFocusInLtrAndRtl() {
        assertEquals(0f, PlayerLiveLyricsSupport.computeTargetScrollX(100f, 180f, 200f, false), 0.01f);
        assertEquals(0f, PlayerLiveLyricsSupport.computeTargetScrollX(40f, 400f, 200f, false), 0.01f);
        assertEquals(132f, PlayerLiveLyricsSupport.computeTargetScrollX(200f, 400f, 200f, false), 0.01f);
        assertEquals(200f, PlayerLiveLyricsSupport.computeTargetScrollX(390f, 400f, 200f, false), 0.01f);

        assertEquals(200f, PlayerLiveLyricsSupport.computeTargetScrollX(390f, 400f, 200f, true), 0.01f);
        assertEquals(0f, PlayerLiveLyricsSupport.computeTargetScrollX(10f, 400f, 200f, true), 0.01f);
    }

    @Test
    public void lineOnlyHoldPanHoldTracksLineDurationInLtrAndRtl() {
        assertEquals(0f, PlayerLiveLyricsSupport.computeLineOnlyTargetScrollX(
                1500L, 1000L, 5000L, 180f, 200f, false), 0.01f);

        // Start hold (0..15% of [1000..5000] = <= 1600ms) stays at start.
        assertEquals(0f, PlayerLiveLyricsSupport.computeLineOnlyTargetScrollX(
                1500L, 1000L, 5000L, 400f, 200f, false), 0.01f);
        // Midpoint (50% = 3000ms) hits 50% of maxScroll (200f -> 100f).
        assertEquals(100f, PlayerLiveLyricsSupport.computeLineOnlyTargetScrollX(
                3000L, 1000L, 5000L, 400f, 200f, false), 0.01f);
        // End hold (>= 85% = >= 4400ms) stays at end (200f).
        assertEquals(200f, PlayerLiveLyricsSupport.computeLineOnlyTargetScrollX(
                4500L, 1000L, 5000L, 400f, 200f, false), 0.01f);

        // RTL mirrors start (maxScroll) -> 0.
        assertEquals(200f, PlayerLiveLyricsSupport.computeLineOnlyTargetScrollX(
                1500L, 1000L, 5000L, 400f, 200f, true), 0.01f);
        assertEquals(100f, PlayerLiveLyricsSupport.computeLineOnlyTargetScrollX(
                3000L, 1000L, 5000L, 400f, 200f, true), 0.01f);
        assertEquals(0f, PlayerLiveLyricsSupport.computeLineOnlyTargetScrollX(
                4500L, 1000L, 5000L, 400f, 200f, true), 0.01f);

        List<LyricsLine> lines = List.of(
                new LyricsLine(1000L, LyricsLine.NO_TIME, "Line 1", List.of(), null, false, false, null),
                new LyricsLine(5000L, 7200L, "Line 2", List.of(), null, false, false, null)
        );
        assertEquals(4880L, PlayerLiveLyricsSupport.resolveLineEndMs(lines, 0));
        assertEquals(7200L, PlayerLiveLyricsSupport.resolveLineEndMs(lines, 1));
    }

    @Test
    public void toSingleLinePreservesLength() {
        String source = "Line one\nLine two\r\nLine three";
        String flattened = PlayerLiveLyricsSupport.toSingleLine(source);
        assertEquals(source.length(), flattened.length());
        assertFalse(flattened.contains("\n"));
        assertFalse(flattened.contains("\r"));
    }

    @Test
    public void fillUncoveredTextSpansPreservesLeadingInterWordAndTrailingPunctuation() {
        String text = "( kimi - boku )";
        // Word timings covering only "kimi" (2..6) and "boku" (9..13), leaving "(", "-", ")" uncovered.
        List<WordTiming> base = List.of(
                new WordTiming(2, 6, 1000L, 1400L, null, true),
                new WordTiming(9, 13, 1800L, 2200L, null, true)
        );
        List<WordTiming> resolved = PlayerLiveLyricsSupport.fillUncoveredTextSpans(text, base);
        assertEquals(5, resolved.size());

        assertEquals("(", text.substring(resolved.get(0).start(), resolved.get(0).end()));
        assertEquals(1000L, resolved.get(0).startMs());
        assertEquals(1000L, resolved.get(0).endMs());
        assertTrue(resolved.get(0).averaged());

        assertEquals("kimi", text.substring(resolved.get(1).start(), resolved.get(1).end()));
        assertEquals("-", text.substring(resolved.get(2).start(), resolved.get(2).end()));
        assertEquals(1400L, resolved.get(2).startMs());
        assertEquals(1800L, resolved.get(2).endMs());
        assertTrue(resolved.get(2).averaged());

        assertEquals("boku", text.substring(resolved.get(3).start(), resolved.get(3).end()));
        assertEquals(")", text.substring(resolved.get(4).start(), resolved.get(4).end()));
        assertEquals(2200L, resolved.get(4).startMs());
        assertEquals(2200L, resolved.get(4).endMs());
        assertTrue(resolved.get(4).averaged());
    }

    @Test
    public void revealStepAndContentAlphaHandleReversibleEnterAndCollapse() {
        assertEquals(0f, PlayerLiveLyricsSupport.computeRevealStep(0f, 1f, 0f), 0.001f);
        assertEquals(0.5f, PlayerLiveLyricsSupport.computeRevealStep(0f, 1f, 0.5f), 0.001f);
        assertEquals(1f, PlayerLiveLyricsSupport.computeRevealStep(0f, 1f, 1f), 0.001f);
        // Reversing mid-collapse from 0.4 back to 1.0 interpolates cleanly without jumping.
        assertEquals(0.7f, PlayerLiveLyricsSupport.computeRevealStep(0.4f, 1f, 0.5f), 0.001f);

        // Expand holds alpha at 0 until height clears padding, then fades in smoothly.
        assertEquals(0f, PlayerLiveLyricsSupport.computeContentRevealAlpha(0.10f, false), 0.001f);
        assertTrue(PlayerLiveLyricsSupport.computeContentRevealAlpha(0.50f, false) > 0.3f);
        assertEquals(1f, PlayerLiveLyricsSupport.computeContentRevealAlpha(1f, false), 0.001f);

        // Collapse fades content out before height reaches zero.
        assertEquals(1f, PlayerLiveLyricsSupport.computeContentRevealAlpha(1f, true), 0.001f);
        assertEquals(0f, PlayerLiveLyricsSupport.computeContentRevealAlpha(0.20f, true), 0.001f);
    }

    @Test
    public void gapLabelSelectionAndWaveOffsetBehaveDeterministically() {
        assertEquals(-1, PlayerLiveLyricsSupport.selectGapLabelIndex(123, GapKind.NONE, 0L, 4));
        assertEquals(-1, PlayerLiveLyricsSupport.selectGapLabelIndex(123, GapKind.INTRO, 0L, 0));

        int idx1 = PlayerLiveLyricsSupport.selectGapLabelIndex(42, GapKind.INTRO, 0L, 4);
        int idx2 = PlayerLiveLyricsSupport.selectGapLabelIndex(42, GapKind.INTRO, 0L, 4);
        assertTrue(idx1 >= 0 && idx1 < 4);
        assertEquals(idx1, idx2);

        // Phase-staggered wave puts notes at distinct vertical positions mid-gap and damps cleanly at exit.
        float w0 = PlayerLiveLyricsSupport.computeNoteWaveOffset(0, 450L, 4f, 1f, false, 0f);
        float w1 = PlayerLiveLyricsSupport.computeNoteWaveOffset(1, 450L, 4f, 1f, false, 0f);
        float w2 = PlayerLiveLyricsSupport.computeNoteWaveOffset(2, 450L, 4f, 1f, false, 0f);
        assertTrue(Math.abs(w0 - w1) > 0.5f || Math.abs(w1 - w2) > 0.5f);
        assertEquals(0f, PlayerLiveLyricsSupport.computeNoteWaveOffset(0, 450L, 4f, 0f, false, 0f), 0.001f);
        assertEquals(0f, PlayerLiveLyricsSupport.computeNoteWaveOffset(0, 450L, 4f, 1f, true, 1f), 0.001f);
    }

    @Test
    public void computeSweepXAdvancesMonotonicallyAcrossOverlappingWords() {
        float[] startX = {0f, 100f, 200f};
        float[] endX = {100f, 200f, 300f};
        long[] startMs = {1000L, 1500L, 1900L};
        // Word 0 has a held/overlapping tail past word 1's start.
        long[] endMs = {2200L, 1900L, 2300L};

        assertEquals(0f, PlayerLiveLyricsSupport.computeSweepX(
                startX, endX, startMs, endMs, 3, 300f, 1000L, false), 0.01f);

        float prev = 0f;
        for (long t = 1100L; t <= 2300L; t += 100L) {
            float x = PlayerLiveLyricsSupport.computeSweepX(
                    startX, endX, startMs, endMs, 3, 300f, t, false);
            assertTrue("Sweep moved backward at t=" + t + ": " + x + " < " + prev, x >= prev);
            prev = x;
        }
        // At t=1700ms (midpoint of word 1 [1500..1900]), sweep tracks word 1 at 150px instead of stalling in word 0.
        assertEquals(150f, PlayerLiveLyricsSupport.computeSweepX(
                startX, endX, startMs, endMs, 3, 300f, 1700L, false), 0.01f);
        assertEquals(300f, PlayerLiveLyricsSupport.computeSweepX(
                startX, endX, startMs, endMs, 3, 300f, 2300L, false), 0.01f);

        // RTL mirrors monotonic progression downward from totalWidth to 0.
        float[] rtlStartX = {300f, 200f, 100f};
        float[] rtlEndX = {200f, 100f, 0f};
        assertEquals(150f, PlayerLiveLyricsSupport.computeSweepX(
                rtlStartX, rtlEndX, startMs, endMs, 3, 300f, 1700L, true), 0.01f);
        assertEquals(0f, PlayerLiveLyricsSupport.computeSweepX(
                rtlStartX, rtlEndX, startMs, endMs, 3, 300f, 2300L, true), 0.01f);
    }
}
