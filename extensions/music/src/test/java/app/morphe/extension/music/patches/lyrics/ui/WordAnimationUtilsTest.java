/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4866
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.AlignedSyllable;
import app.morphe.extension.music.patches.lyrics.ui.WordAnimationUtils.MergedRomanCluster;
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
    public void testRomajiClusterKeepsOneToOneSyllableReadings() {
        Assert.assertFalse(WordAnimationUtils.canMergeRomanSyllable(
                "限", "り", 1, 1, false, "kagi", "ri", 1, false, false));
        Assert.assertFalse(WordAnimationUtils.canMergeRomanSyllable(
                "黒", "い", 1, 1, false, "kuro", "i", 1, false, false));
        Assert.assertFalse(WordAnimationUtils.canMergeRomanSyllable(
                "爱", "し", 1, 1, false, "ai", "shi", 1, false, false));

        Assert.assertTrue(WordAnimationUtils.canMergeRomanSyllable(
                "限", "りの", 1, 1, false, "kagi", null, 1, false, false));

        Assert.assertFalse(WordAnimationUtils.canMergeRomanSyllable(
                "限", "り", 1, 1, true, "kagi ", null, 1, false, false));
        Assert.assertFalse(WordAnimationUtils.canMergeRomanSyllable(
                "限", "り", 1, 1, false, "kagi", null, 3, false, false));
    }

    @Test
    public void testRomajiClusterMergesOnlyWithinOneWord() {
        Assert.assertTrue(WordAnimationUtils.canMergeRomanSyllable(
                "一", "个", 1, 1, false, "yí", "gè", 1, true, true));
        Assert.assertTrue(WordAnimationUtils.canMergeRomanSyllable(
                "限", "り", 1, 1, false, "kagi", "ri", 1, true, true));

        Assert.assertFalse(WordAnimationUtils.canMergeRomanSyllable(
                "个", "人", 1, 1, false, "gè", "rén", 1, false, true));

        // Same word ignores the cap.
        Assert.assertTrue(WordAnimationUtils.canMergeRomanSyllable(
                "一", "个", 1, 1, false, "yí", "gè", 3, true, true));
    }

    @Test
    public void testSameWordMergesEvenWithAnEmptyReading() {
        Assert.assertTrue(WordAnimationUtils.canMergeRomanSyllable(
                "一", "个", 1, 1, false, "yí", null, 3, true, true));
        Assert.assertFalse(WordAnimationUtils.canMergeRomanSyllable(
                "个", "人", 1, 1, false, "gè", null, 1, false, true));
    }

    @Test
    public void testRepeatedSyllablesKeepTheirOwnOffsets() {
        List<WordTimestamp> words = List.of(
                new WordTimestamp("人", 0, 0.1, false, false, "rén"),
                new WordTimestamp("人", 0.1, 0.2, false, false, "rén"),
                new WordTimestamp("人", 0.2, 0.3, false, false, "rén"));
        List<AlignedSyllable> aligned = WordAnimationUtils.alignSyllablesForFormatting(
                words, "人 人 人");
        Assert.assertEquals(0, aligned.get(0).startOffset());
        Assert.assertEquals(2, aligned.get(1).startOffset());
        Assert.assertEquals(4, aligned.get(2).startOffset());
    }

    @Test
    public void testMergedClusterKeepsEverySyllableReading() {
        String mainText = "一个人";
        List<AlignedSyllable> aligned = syllableLine(mainText, new int[]{0, 0, 0});
        MergedRomanCluster merged = WordAnimationUtils.mergeRomanCluster(aligned,
                new String[]{"yí", "gè", "rén"}, new long[]{0, 100, 200},
                new long[]{100, 200, 300}, mainText, new int[]{0, 0, 0}, 0, true);
        Assert.assertEquals("yígèrén", merged.text());
        Assert.assertEquals(3, merged.endOffset());
        Assert.assertEquals(300L, merged.endMs());
        Assert.assertEquals(2, merged.lastIndex());
        Assert.assertEquals(3, merged.segments().size());
        Assert.assertEquals(200L, merged.segments().get(2).startMs());
    }

    @Test
    public void testMergedClusterStopsAtAWordBoundary() {
        String mainText = "一个人";
        List<AlignedSyllable> aligned = syllableLine(mainText, new int[]{0, 0, 1});
        MergedRomanCluster merged = WordAnimationUtils.mergeRomanCluster(aligned,
                new String[]{"yí", "gè", "rén"}, new long[]{0, 100, 200},
                new long[]{100, 200, 300}, mainText, new int[]{0, 0, 0}, 0, true);
        Assert.assertEquals("yígè", merged.text());
        Assert.assertEquals(2, merged.endOffset());
        Assert.assertEquals(1, merged.lastIndex());
        Assert.assertEquals(2, merged.segments().size());
    }

    @Test
    public void testMergedClusterFillsAnEmptyReadingWithoutLosingTheNext() {
        String mainText = "一个人";
        List<AlignedSyllable> aligned = syllableLine(mainText, new int[]{0, 0, 0});
        MergedRomanCluster merged = WordAnimationUtils.mergeRomanCluster(aligned,
                new String[]{"yí", "", "rén"}, new long[]{0, 100, 200},
                new long[]{100, 200, 300}, mainText, new int[]{0, 0, 0}, 0, true);
        Assert.assertEquals("yírén", merged.text());
        Assert.assertEquals(2, merged.segments().size());
        Assert.assertEquals(200L, merged.segments().get(0).endMs());
        Assert.assertEquals(200L, merged.segments().get(1).startMs());
    }

    private static List<AlignedSyllable> syllableLine(String text, int[] groups) {
        List<AlignedSyllable> out = new ArrayList<>(groups.length);
        for (int i = 0; i < groups.length; i++) {
            WordTimestamp syllable = new WordTimestamp(
                    text.substring(i, i + 1), i / 100.0, (i + 1) / 100.0,
                    false, false, "yí", null, groups[i]);
            out.add(new AlignedSyllable(syllable, i, i + 1));
        }
        return out;
    }

    @Test
    public void testWordGroupSurvivesSpanConversion() {
        List<WordTiming> timings = List.of(
                new WordTiming(0, 1, 0, 100, "yí", false, null, false, 4),
                new WordTiming(1, 2, 100, 200, "gè", false, null, false, 4),
                new WordTiming(2, 3, 200, 300, "rén", false, null, false, 5));

        List<WordTimestamp> words = WordTimestamp.fromSpanTimings("一个人", timings, false);
        Assert.assertEquals(3, words.size());
        Assert.assertEquals(4, words.get(0).wordGroup());
        Assert.assertEquals(4, words.get(1).wordGroup());
        Assert.assertEquals(5, words.get(2).wordGroup());
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
    public void testResolvedRomanSyllableTextsKeepOneToOneMapping() {
        String mainText = "限りの";
        List<WordTimestamp> words = List.of(
                new WordTimestamp("限", 0.0, 0.4, false, false, null, null),
                new WordTimestamp("り", 0.4, 0.7, false, false, null, null),
                new WordTimestamp("の", 0.7, 1.0, false, false, null, null));
        List<AlignedSyllable> aligned = WordAnimationUtils.alignSyllablesForFormatting(words, mainText);

        // Per-word readings.
        List<WordTimestamp> romanized = List.of(
                new WordTimestamp("kagi", 0.0, 0.4, false, false, "kagi", null),
                new WordTimestamp("ri", 0.4, 0.7, false, false, "ri", null),
                new WordTimestamp("no", 0.7, 1.0, false, false, "no", null));
        String[] fromWords = WordAnimationUtils.resolvedRomanSyllableTexts(
                aligned, romanized, null, mainText);
        Assert.assertNotNull(fromWords);
        Assert.assertEquals(3, fromWords.length);
        Assert.assertEquals("kagi", fromWords[0].trim());
        Assert.assertEquals("ri", fromWords[1].trim());
        Assert.assertEquals("no", fromWords[2].trim());

        // Whole-line reading split into the same number of slots.
        String[] fromLine = WordAnimationUtils.resolvedRomanSyllableTexts(
                aligned, null, "kagi ri no", mainText);
        Assert.assertNotNull(fromLine);
        Assert.assertEquals(3, fromLine.length);
        Assert.assertEquals("kagi", fromLine[0].trim());
        Assert.assertEquals("ri", fromLine[1].trim());
        Assert.assertEquals("no", fromLine[2].trim());
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

    @Test
    public void testIndicJoiningDetectionAndGraphemeRanges() {
        Assert.assertTrue(LyricsTextUtils.containsJoiningScript("रंग"));
        Assert.assertTrue(LyricsTextUtils.containsJoiningScript("चाँद"));
        Assert.assertFalse(LyricsTextUtils.containsJoiningScript("तरह"));

        // Combining marks and matras stay grouped with their base character instead of splitting.
        List<int[]> rang = WordAnimationUtils.graphemeRanges("रंग");
        Assert.assertEquals(2, rang.size());
        Assert.assertEquals("रं", "रंग".substring(rang.get(0)[0], rang.get(0)[1]));
        Assert.assertEquals("ग", "रंग".substring(rang.get(1)[0], rang.get(1)[1]));

        List<int[]> chaand = WordAnimationUtils.graphemeRanges("चाँद\nसा");
        Assert.assertEquals(3, chaand.size());
        Assert.assertEquals("चाँ", "चाँद\nसा".substring(chaand.get(0)[0], chaand.get(0)[1]));
        Assert.assertEquals("द", "चाँद\nसा".substring(chaand.get(1)[0], chaand.get(1)[1]));
        Assert.assertEquals("सा", "चाँद\nसा".substring(chaand.get(2)[0], chaand.get(2)[1]));
    }

    @Test
    public void testRubyElevationTracksHeavyMainClusterTopDelta() {
        float[] baseY = new float[]{100f, 100f, 100f};
        float[] drawY = new float[]{95f, 92f, 80f};
        float[] scaleX = new float[]{1.0f, 1.1f, 1.2f};
        float[] pivotY = new float[]{60f, 60f, 60f};

        // Regular syllable: no scaling, top moves purely with drawY - baseY (-5px).
        Assert.assertEquals(-5f, WordSweepAnimator.clusterTopDelta(drawY[0], baseY[0], scaleX[0], pivotY[0]), 0.001f);

        // Heavy glowing cluster at idx 1: -8px translation - (0.1 * 60) = -14px upward top expansion.
        Assert.assertEquals(-14f, WordSweepAnimator.clusterTopDelta(drawY[1], baseY[1], scaleX[1], pivotY[1]), 0.001f);

        // Ruby overlapping [0, 1] lifts cleanly above the heavy kanji even if rubyElev itself is only -2.5f.
        // Cluster 2 does not overlap [0, 1] and must be ignored.
        float resolved = WordSweepAnimator.resolveRubyClusterElevY(
                -2.5f, new int[]{0, 1}, drawY, baseY, scaleX, pivotY);
        Assert.assertEquals(-14f, resolved, 0.001f);

        // Unrelated ruby falling back to its own ElevTracks value when no main cluster moved higher.
        float solo = WordSweepAnimator.resolveRubyClusterElevY(
                -2.5f, new int[0], drawY, baseY, scaleX, pivotY);
        Assert.assertEquals(-2.5f, solo, 0.001f);

        // Reveal/collapse shift: drawY matches restY when shiftProgress < 1f even though restY < static baseY.
        float[] restYDuringShift = new float[]{70f, 70f, 70f};
        float[] drawYDuringShift = new float[]{65f, 70f, 70f};
        float duringShift = WordSweepAnimator.resolveRubyClusterElevY(
                -5f, new int[]{0}, drawYDuringShift, restYDuringShift, new float[]{1f, 1f, 1f}, pivotY);
        Assert.assertEquals(-5f, duringShift, 0.001f);
    }
}