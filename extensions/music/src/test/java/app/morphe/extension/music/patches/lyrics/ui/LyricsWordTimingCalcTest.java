/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4709
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class LyricsWordTimingCalcTest {

    private static LyricsLine line(long startMs, long endMs, String text, List<Word> words) {
        return new LyricsLine(startMs, endMs, text, words);
    }

    @Test
    public void singleLoneWordWithRubyStaysWhole() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(line(0, 2000, "日本",
                        List.of(new Word(0, 1000, "日本", null, false, "にほん")))), 0, false);
        assertEquals(1, timings.size());
        assertEquals(0, timings.get(0).start());
        assertEquals(2, timings.get(0).end());
        assertEquals("にほん", timings.get(0).ruby());
    }

    @Test
    public void wordlessLineIsSplitWhenAsked() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(line(0, 1000, "ab cd", List.of())), 0, true);
        assertEquals(2, timings.size());
        assertEquals(0, timings.get(0).start());
        assertEquals(2, timings.get(0).end());
        assertEquals(3, timings.get(1).start());
        assertEquals(5, timings.get(1).end());
    }

    @Test
    public void wordlessPlainTextLineGetsADummyRange() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(line(LyricsLine.NO_TIME, LyricsLine.NO_TIME, "ab cd", List.of())), 0, true);
        assertEquals(2, timings.size());
        assertEquals(0, timings.get(0).startMs());
        assertEquals(1, timings.get(1).endMs());
    }

    @Test
    public void wordlessLineIsNotSplitWhenNotAsked() {
        assertTrue(LyricsWordTimingCalc.computeWordTimings(
                List.of(line(0, 1000, "ab cd", List.of())), 0, false).isEmpty());
    }

    @Test
    public void emptyWhenThereAreNoTimingsAndNoRuby() {
        assertTrue(LyricsWordTimingCalc.computeWordTimingsFromWords(
                line(0, 1000, "ab", List.of(new Word(0, 0, "ab")))).isEmpty());
    }

    @Test
    public void emptyWhenTheLineHasNoText() {
        assertTrue(LyricsWordTimingCalc.computeWordTimingsFromWords(
                line(0, 1000, "", List.of(new Word(0, 1000, "")))).isEmpty());
    }

    @Test
    public void keepsRealWordTimingsWithoutClamping() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimingsFromWords(
                line(0, 2000, "ab cd", List.of(
                        new Word(0, 1000, "ab", null, true),
                        new Word(500, 1500, "cd"))));
        assertEquals(2, timings.size());
        assertEquals(1000, timings.get(0).endMs());
        assertEquals(500, timings.get(1).startMs());
        assertEquals(1500, timings.get(1).endMs());
    }

    @Test
    public void splitsATimingCoveringSeveralWordsByLength() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimingsFromWords(
                line(0, 2000, "ab cd", List.of(new Word(0, 1000, "ab cd"))));
        assertEquals(2, timings.size());
        assertEquals(0, timings.get(0).start());
        assertEquals(2, timings.get(0).end());
        assertEquals(0, timings.get(0).startMs());
        assertEquals(500, timings.get(0).endMs());
        assertEquals(3, timings.get(1).start());
        assertEquals(5, timings.get(1).end());
        assertEquals(500, timings.get(1).startMs());
        assertEquals(1000, timings.get(1).endMs());
    }

    @Test
    public void fillsTextLeftAfterTheLastTimedWord() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimingsFromWords(
                line(0, 1000, "ab cd", List.of(new Word(0, 1000, "ab"))));
        assertEquals(2, timings.size());
        assertEquals(3, timings.get(1).start());
        assertEquals(5, timings.get(1).end());
        assertEquals(1000, timings.get(1).startMs());
    }

    @Test
    public void stripsBracketsFromBackgroundVocalWords() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimingsFromWords(
                new LyricsLine(0, LyricsLine.NO_TIME, "(ab)",
                        List.of(new Word(0, 1000, "(ab)")), null, false, true, null));
        assertEquals(1, timings.size());
        assertEquals(0, timings.get(0).start());
        assertEquals(2, timings.get(0).end());
    }

    @Test
    public void marksAveragedWhenTheLineHasNoRealTimings() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimingsFromWords(
                line(0, 2000, "日本",
                        List.of(new Word(0, 0, "日本", null, false, "にほん"))));
        assertEquals(1, timings.size());
        assertTrue(timings.get(0).averaged());
    }

    @Test
    public void wholeWordSkipsALongerWordPrefix() {
        String text = "Everything every day";
        assertEquals(11, LyricsWordTimingCalc.usableHit(text, text.length(), 0, "every", true));
    }

    @Test
    public void syllableTokensMatchSequentially() {
        String text = "Everything every day";
        assertEquals(0, LyricsWordTimingCalc.usableHit(text, text.length(), 0, "Every", false));
        assertEquals(5, LyricsWordTimingCalc.usableHit(text, text.length(), 5, "thing", true));
    }

    @Test
    public void usableHitReturnsMinusOneWhenMissing() {
        assertEquals(-1, LyricsWordTimingCalc.usableHit("abc", 3, 0, "zz", true));
    }

    @Test
    public void usableHitReadsThroughSurroundingMarks() {
        String text = "ab (cd)";
        assertEquals(3, LyricsWordTimingCalc.usableHit(text, text.length(), 0, "(cd)", true));
    }

    @Test
    public void usableHitFallsBackToTheWordStartWhenTheTokenEndsMidWord() {
        String text = "abcde";
        assertEquals(0, LyricsWordTimingCalc.usableHit(text, text.length(), 0, "abc", false));
        assertEquals(-1, LyricsWordTimingCalc.usableHit(text, text.length(), 0, "abc", true));
    }

    @Test
    public void wordTokenTrimsSurroundingMarks() {
        assertEquals("ab", LyricsWordTimingCalc.wordToken(" \"ab\" "));
        assertEquals("ab", LyricsWordTimingCalc.wordToken("（ab）"));
        assertEquals("ab", LyricsWordTimingCalc.wordToken("ab"));
    }

    @Test
    public void isSurroundingMarkRecognizesQuotesBracketsAndWhitespace() {
        assertTrue(LyricsWordTimingCalc.isSurroundingMark(' '));
        assertTrue(LyricsWordTimingCalc.isSurroundingMark('"'));
        assertTrue(LyricsWordTimingCalc.isSurroundingMark('“'));
        assertTrue(LyricsWordTimingCalc.isSurroundingMark('（'));
        assertTrue(LyricsWordTimingCalc.isSurroundingMark('】'));
        assertFalse(LyricsWordTimingCalc.isSurroundingMark('a'));
    }

    @Test
    public void findTokenFallsBackToCaseInsensitiveSearch() {
        assertEquals(0, LyricsWordTimingCalc.findToken("Abc", 0, "abc"));
        assertEquals(2, LyricsWordTimingCalc.findToken("abab", 1, "ab"));
        assertEquals(-1, LyricsWordTimingCalc.findToken("abc", 0, "zz"));
    }

    @Test
    public void wordHitReportsTheWordInsideItsMarks() {
        assertArrayEquals(new int[]{4, 7, 6},
                LyricsWordTimingCalc.wordHit("ab (cd)", 7, 3, "(cd)"));
    }

    @Test
    public void unmatchedWordLeavesTheOffsetAlone() {
        List<WordTiming> timings = new ArrayList<>();
        int offset = LyricsWordTimingCalc.appendMatchedWordTiming(
                timings, "ab", 2, 0, "zz", "zz", 0, 100, null, false, null, false);
        assertEquals(0, offset);
        assertTrue(timings.isEmpty());
    }

    @Test
    public void matchedWordGetsItsSpanAndTime() {
        List<WordTiming> timings = new ArrayList<>();
        int offset = LyricsWordTimingCalc.appendMatchedWordTiming(
                timings, "ab cd", 5, 0, "ab", "ab", 0, 100, "a", false, null, false);
        assertEquals(2, offset);
        assertEquals(1, timings.size());
        assertEquals(0, timings.get(0).start());
        assertEquals(2, timings.get(0).end());
        assertEquals(0, timings.get(0).startMs());
        assertEquals(100, timings.get(0).endMs());
        assertEquals("a", timings.get(0).romaji());
    }

    @Test
    public void marksBeforeAWordAreTimedWithIt() {
        List<WordTiming> timings = new ArrayList<>();
        LyricsWordTimingCalc.appendMarksTiming(timings, "ab", 2, 0, 2, 50, false);
        assertEquals(1, timings.size());
        assertEquals(0, timings.get(0).start());
        assertEquals(2, timings.get(0).end());
        assertEquals(50, timings.get(0).startMs());
        assertEquals(51, timings.get(0).endMs());
    }

    @Test
    public void whitespaceGapGivesThePreviousWordItsTrailingSpace() {
        List<WordTiming> timings = new ArrayList<>();
        timings.add(new WordTiming(0, 2, 0, 100, null, false));
        LyricsWordTimingCalc.fillUntimedGap(timings, "ab cd", 5, 2, 3, 100, 200);
        assertEquals(1, timings.size());
        assertTrue(timings.get(0).hasTrailingSpace());
    }

    @Test
    public void wordsInAGapShareItsTime() {
        List<WordTiming> timings = new ArrayList<>();
        LyricsWordTimingCalc.fillUntimedGap(timings, " cd", 3, 0, 3, 100, 200);
        assertEquals(1, timings.size());
        assertEquals(1, timings.get(0).start());
        assertEquals(3, timings.get(0).end());
        assertEquals(100, timings.get(0).startMs());
        assertEquals(200, timings.get(0).endMs());
    }

    @Test
    public void punctuationInAGapJoinsThePreviousWord() {
        List<WordTiming> timings = new ArrayList<>();
        timings.add(new WordTiming(0, 2, 0, 100, null, false));
        LyricsWordTimingCalc.fillUntimedGap(timings, "ab.", 3, 2, 3, 100, 200);
        assertEquals(1, timings.size());
        assertEquals(3, timings.get(0).end());
        assertEquals(100, timings.get(0).endMs());
    }

    @Test
    public void singleEnglishWordLineGetsCharacterSweepSpansInsteadOfLineSync() {
        // Pretender ("Goodbye") when Unison provides a single whole-word span on the line.
        LyricsLine pretenderChorus = line(1000, 2400, "Goodbye",
                List.of(new Word(1000, 2400, "Goodbye")));
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(pretenderChorus), 0, false);
        assertEquals(7, timings.size());
        assertEquals(0, timings.get(0).start());
        assertEquals(7, timings.get(6).end());
        assertEquals(1000, timings.get(0).startMs());
        assertEquals(2400, timings.get(6).endMs());
        for (WordTiming wt : timings) {
            assertFalse(wt.averaged());
        }
    }

    @Test
    public void singleKatakanaWordLineGetsCharacterSweepSpansInsteadOfLineSync() {
        LyricsLine gubbai = line(1000, 2200, "グッバイ",
                List.of(new Word(1000, 2200, "グッバイ")));
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(gubbai), 0, false);
        assertEquals(4, timings.size());
        assertEquals(1000, timings.get(0).startMs());
        assertEquals(2200, timings.get(3).endMs());
        for (WordTiming wt : timings) {
            assertFalse(wt.averaged());
        }
    }

    @Test
    public void wordlessLineInsideWordSyncedSongGetsSynthesizedNonAveragedSweepSpans() {
        // Pretender when one line in a word-synced song arrives with empty words[] from provider.
        LyricsLine wordSyncedLine = line(0, 1000, "君の運命のヒトは僕じゃない",
                List.of(
                        new Word(0, 400, "君の"),
                        new Word(400, 1000, "運命のヒトは僕じゃない")));
        LyricsLine loneLineSynced = line(1000, 2400, "Goodbye", List.of());
        List<LyricsLine> songLines = List.of(wordSyncedLine, loneLineSynced);

        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(songLines, 1, false);
        assertFalse(timings.isEmpty());
        assertEquals(0, timings.get(0).start());
        assertEquals(7, timings.get(timings.size() - 1).end());
        assertEquals(1000, timings.get(0).startMs());
        assertEquals(2400, timings.get(timings.size() - 1).endMs());
        for (WordTiming wt : timings) {
            assertFalse(wt.averaged());
        }
    }

    @Test
    public void plainChineseLineIsGroupedByPinyinWord() {
        LyricsLine wordless = line(0, 900, "一个人", List.of());
        LyricsLine wordSynced = line(1000, 2000, "ab cd", List.of(
                new Word(1100, 1400, "ab"),
                new Word(1500, 1800, "cd")));
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(wordless, wordSynced), 0, false);
        assertEquals(3, timings.size());
        for (WordTiming timing : timings) {
            assertEquals(0, timing.wordGroup());
        }
    }

    @Test
    public void koreanLineIsGroupedByHangulRun() {
        LyricsLine wordless = line(0, 900, "안녕 세계", List.of());
        LyricsLine wordSynced = line(1000, 2000, "ab cd", List.of(
                new Word(1100, 1400, "ab"),
                new Word(1500, 1800, "cd")));
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(wordless, wordSynced), 0, false);
        assertEquals(4, timings.size());
        assertArrayEquals(new int[]{0, 0, 1, 1},
                timings.stream().mapToInt(WordTiming::wordGroup).toArray());
    }

    @Test
    public void sourceSyllableWordsAreRegroupedByPinyin() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(line(0, 1000, "一个人",
                        List.of(new Word(0, 400, "一个"), new Word(400, 1000, "人")))), 0, false);
        assertEquals(2, timings.size());
        assertEquals(0, timings.get(0).wordGroup());
        assertEquals(0, timings.get(1).wordGroup());
    }

    @Test
    public void krcSyllableWordsFollowPinyinWordBoundaries() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(line(0, 1000, "重新开始", List.of(
                        new Word(0, 200, "重"), new Word(200, 400, "新"),
                        new Word(400, 700, "开"), new Word(700, 1000, "始")))), 0, false);
        assertEquals(4, timings.size());
        assertArrayEquals(new int[]{0, 0, 1, 1},
                timings.stream().mapToInt(WordTiming::wordGroup).toArray());
    }

    @Test
    public void latinWordsInAChineseLineHaveNoGroup() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(line(0, 1000, "重新ok开始", List.of(
                        new Word(0, 300, "重新"), new Word(300, 600, "ok"),
                        new Word(600, 1000, "开始")))), 0, false);
        assertArrayEquals(new int[]{0, -1, 1},
                timings.stream().mapToInt(WordTiming::wordGroup).toArray());
    }

    @Test
    public void japaneseWithoutATokenizerHasNoGroups() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(line(0, 1000, "今日は", List.of(
                        new Word(0, 300, "今"), new Word(300, 600, "日"),
                        new Word(600, 1000, "は")))), 0, false);
        assertArrayEquals(new int[]{-1, -1, -1},
                timings.stream().mapToInt(WordTiming::wordGroup).toArray());
    }

    @Test
    public void aTimingAcrossTwoWordsHasNoGroup() {
        List<WordTiming> timings = LyricsWordTimingCalc.computeWordTimings(
                List.of(line(0, 1000, "重新开始", List.of(
                        new Word(0, 500, "重新开"), new Word(500, 1000, "始")))), 0, false);
        assertEquals(2, timings.size());
        assertEquals(-1, timings.get(0).wordGroup());
        assertEquals(1, timings.get(1).wordGroup());
    }
}
