/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4866
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.AlignedSyllable;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.ReconstructedWord;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.VisualCluster;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WipeResult;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.WordTimestamp;

public class WordAnimationUtilsTest {

    @Test
    public void testBidiStrippingAndScriptDetection() {
        String dirty = "\u200BHello\u200F \u202AWorld\uFEFF";
        Assert.assertEquals("Hello World", LyricsTextUtils.stripBidiControls(dirty));
        Assert.assertTrue(WordAnimationUtils.isNeedSplitOnly("東京"));
        Assert.assertTrue(WordAnimationUtils.isNeedSplitOnly("사랑해!"));
        Assert.assertFalse(WordAnimationUtils.isNeedSplitOnly("Hello"));
        Assert.assertFalse(WordAnimationUtils.isNeedSplitOnly("Tokyo東京"));
        Assert.assertTrue(LyricsSpanBuilder.isRtlText("مرحبا"));
        Assert.assertTrue(LyricsTextUtils.containsJoiningScript("مرحبا"));
        Assert.assertFalse(LyricsSpanBuilder.isRtlText("Hello"));
    }

    @Test
    public void testSafeBreakOffsetAvoidsProhibitedStart() {
        String text = "君のことが好きだ。本当に";
        int rawBreak = text.indexOf('。');
        int safe = WordAnimationUtils.findSafeBreakOffset(text, rawBreak, 0);
        Assert.assertNotEquals("Should not break right before prohibited start '。'", rawBreak, safe);
    }

    @Test
    public void testSyllableAlignmentAndReconstructedWords() {
        List<WordTimestamp> words = List.of(
                new WordTimestamp("for", 1.0, 1.4, false, false, null),
                new WordTimestamp("ev", 1.4, 1.8, false, false, null),
                new WordTimestamp("er", 1.8, 2.5, true, false, null),
                new WordTimestamp("more", 2.5, 3.8, false, false, null)
        );
        String mainText = "forever more";
        List<AlignedSyllable> aligned = WordAnimationUtils.alignSyllablesForFormatting(words, mainText);
        Assert.assertEquals(4, aligned.size());
        Assert.assertEquals(0, aligned.get(0).startOffset());
        Assert.assertEquals(3, aligned.get(0).endOffset());
        Assert.assertEquals(3, aligned.get(1).startOffset());
        Assert.assertEquals(5, aligned.get(1).endOffset());
        Assert.assertEquals(5, aligned.get(2).startOffset());
        Assert.assertEquals(7, aligned.get(2).endOffset());
        Assert.assertEquals(8, aligned.get(3).startOffset());
        Assert.assertEquals(12, aligned.get(3).endOffset());

        List<ReconstructedWord> rec = WordAnimationUtils.buildReconstructedWords(aligned, mainText);
        Assert.assertEquals(2, rec.size());
        Assert.assertEquals(3, rec.get(0).syllables().size());
        Assert.assertEquals(1000L, rec.get(0).wordStartTimeMs());
        Assert.assertEquals(2500L, rec.get(0).wordEndTimeMs());
        Assert.assertEquals(1, rec.get(1).syllables().size());
    }

    @Test
    public void testComputeWipeLeadingEdgeGrowAndCrossSyllable() {
        VisualCluster vc0 = new VisualCluster(
                0, 4, 0, 0f, 0f, 50f,
                false, false, false, false,
                0L, 0L, 0f, 0f, -1f, null,
                1f, 0f, 0f, 0L, 0);
        WipeResult wipe = new WipeResult();
        // Leading edge grow ramp when localX < halfGradientWidth (gradientWidth = 20f, half = 10f, localX = 5f -> growWidth = 10.0f)
        WordAnimationUtils.computeWipe(
                vc0, false, 0f, 200f, 0f, 50f,
                0, 0, 5f, false, 0.25f, 20f, wipe);
        Assert.assertEquals(WordAnimationUtils.WIPE_BAND, wipe.mode);
        Assert.assertEquals(0f, wipe.bandStart, 0.001f);
        Assert.assertEquals(10.0f, wipe.bandEnd, 0.001f);
    }

    @Test
    public void testEmphasisTableAndGlowCurve() {
        float[] table = WordSweepAnimator.buildEmphasisTable(1500L, 600L);
        Assert.assertEquals(WordSweepAnimator.EMPHASIS_TABLE_FRAMES * 2, table.length);

        float[] sample = new float[2];
        WordSweepAnimator.sampleEmphasisCached(table, 3600f, 0f, sample);
        Assert.assertEquals(0f, sample[0], 0.0001f);
        Assert.assertEquals(0f, sample[1], 0.0001f);

        WordSweepAnimator.sampleEmphasisCached(table, 3600f, 3600f, sample);
        Assert.assertEquals(1f, sample[0], 0.0001f);
        Assert.assertEquals(0f, sample[1], 0.0001f);

        Assert.assertEquals(0f, WordSweepAnimator.emphasisGlowOpacity(0f), 0.001f);
        Assert.assertEquals(0.8f, WordSweepAnimator.emphasisGlowOpacity(0.15f), 0.001f);
        Assert.assertEquals(1.0f, WordSweepAnimator.emphasisGlowOpacity(0.30f), 0.001f);
        Assert.assertEquals(0.3f, WordSweepAnimator.emphasisGlowOpacity(0.60f), 0.001f);
        Assert.assertEquals(0f, WordSweepAnimator.emphasisGlowOpacity(1.0f), 0.001f);
    }

    @Test
    public void testMapFormattedToMainOffsetsSpaceAndDirectInsert() {
        String mainKo = "평범한 건 지루해";
        String fmtKo = "평범한 건\n지루해";
        int[] mapKo = WordAnimationUtils.mapFormattedToMainOffsets(fmtKo, mainKo);
        Assert.assertEquals(mainKo.indexOf("평"), mapKo[fmtKo.indexOf("평")]);
        Assert.assertEquals(mainKo.indexOf("건"), mapKo[fmtKo.indexOf("건")]);
        Assert.assertEquals(mainKo.indexOf("지"), mapKo[fmtKo.indexOf("지")]);
        Assert.assertEquals(mainKo.indexOf("해"), mapKo[fmtKo.indexOf("해")]);

        String mainJa = "こんにちは世界";
        String fmtJa = "こんにちは\n世界";
        int[] mapJa = WordAnimationUtils.mapFormattedToMainOffsets(fmtJa, mainJa);
        Assert.assertEquals(mainJa.indexOf("は"), mapJa[fmtJa.indexOf("は")]);
        Assert.assertEquals(mainJa.indexOf("世"), mapJa[fmtJa.indexOf("世")]);
        Assert.assertEquals(mainJa.indexOf("界"), mapJa[fmtJa.indexOf("界")]);
    }

    @Test
    public void testHasAnyRomanizedAndRubySyllables() {
        List<WordTimestamp> words = List.of(
                new WordTimestamp("평", 0.0, 0.5, false, false, null, "pyeong"),
                new WordTimestamp("범", 0.5, 1.0, false, false, null, null)
        );
        String mainText = "평범";
        List<AlignedSyllable> aligned = WordAnimationUtils.alignSyllablesForFormatting(words, mainText);
        Assert.assertTrue(WordAnimationUtils.hasAnyRomanizedSyllables(
                aligned, null, "pyeong beom", mainText));
        Assert.assertFalse(WordAnimationUtils.hasAnyRomanizedSyllables(
                aligned, null, mainText, mainText));
        Assert.assertTrue(WordAnimationUtils.hasAnyRubySyllables(true, aligned, mainText));
        Assert.assertFalse(WordAnimationUtils.hasAnyRubySyllables(false, aligned, mainText));
    }

    @Test
    public void testFormatMainTextWithResultPreservesUnchangedText() {
        List<WordTimestamp> words = List.of(
                new WordTimestamp("hel", 0.0, 0.5, false, false, null, null),
                new WordTimestamp("lo", 0.5, 1.0, false, false, null, null)
        );
        WordAnimationUtils.FormattedMainText disabled = WordAnimationUtils.formatMainTextWithResult(
                "hello", words, null, null, false, false, new android.text.TextPaint(), 500);
        Assert.assertEquals("hello", disabled.text);
        Assert.assertNull(disabled.alignedIfUnchanged);

        WordAnimationUtils.FormattedMainText enabled = WordAnimationUtils.formatMainTextWithResult(
                "hello", words, null, null, true, false, new android.text.TextPaint(), 500);
        Assert.assertEquals("hello", enabled.text);
        Assert.assertNotNull(enabled.alignedIfUnchanged);
        Assert.assertEquals(2, enabled.alignedIfUnchanged.size());
    }

    @Test
    public void testJapaneseClusterJoinMergesOkuriganaAndRespectsWordSpaces() {
        // Kanji stem + okurigana merge when unspaced ("限"+"りの", "黒"+"い", "越"+"えて", "愛"+"し"+"て")
        Assert.assertTrue(WordAnimationUtils.canJoinUnspacedJapaneseSyllables(
                "限", "りの", 1, 1, false, "kagi"));
        Assert.assertTrue(WordAnimationUtils.canJoinUnspacedJapaneseSyllables(
                "黒", "い", 1, 1, false, "kuro"));
        Assert.assertTrue(WordAnimationUtils.canJoinUnspacedJapaneseSyllables(
                "愛", "し", 1, 1, false, "ai"));

        // Particle/word boundaries with trailing space or explicit gap stop cluster merging
        Assert.assertFalse(WordAnimationUtils.canJoinUnspacedJapaneseSyllables(
                "りの", "無い", 3, 3, true, "ri no "));
        Assert.assertFalse(WordAnimationUtils.canJoinUnspacedJapaneseSyllables(
                "りの", "無い", 3, 3, false, "ri no "));
        Assert.assertFalse(WordAnimationUtils.canJoinUnspacedJapaneseSyllables(
                "すべて", "愛", 3, 4, false, "subete"));

        // Latin syllables remain separate unless explicitly continuous in Latin logic
        Assert.assertFalse(WordAnimationUtils.canJoinUnspacedJapaneseSyllables(
                "for", "ev", 3, 3, false, "for"));
    }

    @Test
    public void testIndependentRomanWipeFollowsRomajiSegmentTimings() {
        // Simulates merged cluster "kagiri no" [0..40px="kagi" 100..300ms, 40..90px="ri no" 300..700ms]
        // followed by a gap/space [90..110px], then "nai" [110..150px 800..1200ms]
        float[] segStartX = {0f, 40f, 110f};
        float[] segEndX = {40f, 90f, 150f};
        long[] segStartMs = {100L, 300L, 800L};
        long[] segEndMs = {300L, 700L, 1200L};
        float lastRomEnd = 150f;
        float subGrad = 20f;
        float[] out = new float[2];

        // Before first segment -> unlit
        WordAnimationUtils.computeIndependentRomanWipe(
                segStartX, segEndX, segStartMs, segEndMs, lastRomEnd, 50L, subGrad, false, out);
        Assert.assertTrue(out[0] < 0f);
        Assert.assertEquals(0f, out[1], 0.01f);

        // Midpoint of first sub-segment "kagi" (200ms -> halfway across [0..40px])
        WordAnimationUtils.computeIndependentRomanWipe(
                segStartX, segEndX, segStartMs, segEndMs, lastRomEnd, 200L, subGrad, false, out);
        Assert.assertEquals(20f, out[0], 0.05f);

        // Midpoint of second sub-segment "ri no" (500ms -> halfway across [40..90px])
        WordAnimationUtils.computeIndependentRomanWipe(
                segStartX, segEndX, segStartMs, segEndMs, lastRomEnd, 500L, subGrad, false, out);
        Assert.assertEquals(65f, out[0], 0.05f);

        // Inside pause between words (750ms -> halfway across space [90..110px])
        WordAnimationUtils.computeIndependentRomanWipe(
                segStartX, segEndX, segStartMs, segEndMs, lastRomEnd, 750L, subGrad, false, out);
        Assert.assertEquals(100f, out[0], 0.05f);

        // Midpoint of final word "nai" (1000ms -> halfway across [110..150px] + half feather shift)
        WordAnimationUtils.computeIndependentRomanWipe(
                segStartX, segEndX, segStartMs, segEndMs, lastRomEnd, 1000L, subGrad, false, out);
        Assert.assertEquals(130f, out[0], 0.05f);
        Assert.assertEquals(5f, out[1], 0.05f);

        // Past end of line -> full pastColor
        WordAnimationUtils.computeIndependentRomanWipe(
                segStartX, segEndX, segStartMs, segEndMs, lastRomEnd, 1250L, subGrad, false, out);
        Assert.assertEquals(Float.MAX_VALUE, out[0], 0.01f);

        // Forced past (even before endMs) -> full pastColor
        WordAnimationUtils.computeIndependentRomanWipe(
                segStartX, segEndX, segStartMs, segEndMs, lastRomEnd, 500L, subGrad, true, out);
        Assert.assertEquals(Float.MAX_VALUE, out[0], 0.01f);
    }

    @Test
    public void testShouldForceRomanPastWaitsForActiveFinishingSweep() {
        // Deactivating while still finishing the final word sweep must NOT force romaji past.
        Assert.assertFalse(WordSweepAnimator.shouldForceRomanPast(false, false, true, true, true));
        // Once finishing completes while deactivating fade is still running, allow forcing past.
        Assert.assertTrue(WordSweepAnimator.shouldForceRomanPast(false, false, true, false, true));
        // Explicit allSung always forces past.
        Assert.assertTrue(WordSweepAnimator.shouldForceRomanPast(true, true, true, true, false));
        // Active line never forces past unless allSung.
        Assert.assertFalse(WordSweepAnimator.shouldForceRomanPast(false, true, true, false, true));
    }

    @Test
    public void testEmphasisRampsSmoothlyFromOneSecondThreshold() {
        Assert.assertEquals(0f, WordSweepAnimator.emphasisAmplitude(1.0), 0.0001f);
        Assert.assertEquals(0f, WordSweepAnimator.emphasisGlowMax(1.0), 0.0001f);
        Assert.assertEquals(0.25f, WordSweepAnimator.emphasisAmplitude(1.25), 0.0001f);
        Assert.assertEquals(0.225f, WordSweepAnimator.emphasisGlowMax(1.25), 0.0001f);
        Assert.assertEquals(1.0f, WordSweepAnimator.emphasisAmplitude(2.5), 0.0001f);
        Assert.assertEquals(0.45f, WordSweepAnimator.emphasisGlowMax(2.5), 0.0001f);
    }

    @Test
    public void testComputeWipeDoesNotShiftBeforeFinalSyllable() {
        WordAnimationUtils.VisualCluster midCluster = new WordAnimationUtils.VisualCluster(
                0, 4, 0, 100f, 0f, 40f,
                false, false, false, false,
                0L, 400L, 0f, 0f, 0f,
                null, 1f, 0f, 0f, 0L, 1, 1);
        WordAnimationUtils.WipeResult out = new WordAnimationUtils.WipeResult();

        // Mid-line active syllable (not last of line): gradient band stays centered on curGX (no lineProg advance).
        WordAnimationUtils.computeWipe(
                midCluster, false, 0f, 200f, 100f, 140f,
                1, 0, 120f, false, 0.5f, 40f, out);
        Assert.assertEquals(WordAnimationUtils.WIPE_BAND, out.mode);
        Assert.assertEquals(0f, out.bandStart, 0.01f);
        Assert.assertEquals(40f, out.bandEnd, 0.01f);

        // Final syllable on line advances by activeSylProgress * halfGradientWidth (0.5 * 20 = 10px).
        WordAnimationUtils.computeWipe(
                midCluster, false, 0f, 200f, 100f, 140f,
                1, 0, 120f, true, 0.5f, 40f, out);
        Assert.assertEquals(WordAnimationUtils.WIPE_BAND, out.mode);
        Assert.assertEquals(10f, out.bandStart, 0.01f);
        Assert.assertEquals(50f, out.bandEnd, 0.01f);
    }
}