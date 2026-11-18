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
        Assert.assertEquals(0, aligned.get(0).startOffset);
        Assert.assertEquals(3, aligned.get(0).endOffset);
        Assert.assertEquals(3, aligned.get(1).startOffset);
        Assert.assertEquals(5, aligned.get(1).endOffset);
        Assert.assertEquals(5, aligned.get(2).startOffset);
        Assert.assertEquals(7, aligned.get(2).endOffset);
        Assert.assertEquals(8, aligned.get(3).startOffset);
        Assert.assertEquals(12, aligned.get(3).endOffset);

        List<ReconstructedWord> rec = WordAnimationUtils.buildReconstructedWords(aligned, mainText);
        Assert.assertEquals(2, rec.size());
        Assert.assertEquals(3, rec.get(0).syllables.size());
        Assert.assertEquals(1000L, rec.get(0).wordStartTimeMs);
        Assert.assertEquals(2500L, rec.get(0).wordEndTimeMs);
        Assert.assertEquals(1, rec.get(1).syllables.size());
    }

    @Test
    public void testComputeWipeLeadingEdgeGrowAndCrossSyllable() {
        VisualCluster vc0 = new VisualCluster(
                0, 4, 0, 0f, 0f, 50f,
                false, false, false, false,
                0L, 0L, 0f, 0f, -1f, null,
                1f, 0f, 0f, 0L, 0);
        WipeResult wipe = new WipeResult();
        // Leading edge grow ramp when localX < halfGradientWidth (gradientWidth = 20f, half = 10f, localX = 5f + brushShift 0.25f = 5.25f -> growWidth = 10.5f)
        WordAnimationUtils.computeWipe(
                vc0, false, 0f, 200f, 0f, 50f,
                0, 0, 5f, false, 0.25f, 20f, wipe);
        Assert.assertEquals(WordAnimationUtils.WIPE_BAND, wipe.mode);
        Assert.assertEquals(0f, wipe.bandStart, 0.001f);
        Assert.assertEquals(10.5f, wipe.bandEnd, 0.001f);
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
}