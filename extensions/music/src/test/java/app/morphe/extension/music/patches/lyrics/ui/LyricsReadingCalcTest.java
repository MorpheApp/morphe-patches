/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class LyricsReadingCalcTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    private static List<WordTiming> perCharacterTimings(String text) {
        List<WordTiming> timings = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            timings.add(new WordTiming(i, i + 1, 0, 1, null, false));
        }
        return timings;
    }

    private static String joinedRomaji(List<WordTiming> timings) {
        StringBuilder out = new StringBuilder();
        for (WordTiming timing : timings) {
            String romaji = timing.romaji();
            if (romaji == null || romaji.trim().isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(romaji.trim());
        }
        return out.toString();
    }

    private static LyricsLine secondaryLine(String text) {
        return new LyricsLine(0, LyricsLine.NO_TIME, text, List.of(), null, false, true, null);
    }

    @Test
    public void purelyLatinLineHasNoReading() {
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                new LyricsLine(0, "hello world"), 0, null, true, null);
        assertNull(reading.text());
        assertNull(reading.spans());
    }

    @Test
    public void perWordReadingJoinsWordsAndSpans() {
        LyricsLine line = new LyricsLine(0, "あ い");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, LyricsLine.NO_TIME, "あ い",
                List.of(new Word(0, 10, "あ", "a", false),
                        new Word(10, 20, "い", "i", false))));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, true, null);
        assertEquals("a i", reading.text());
        assertNotNull(reading.spans());
        assertEquals(2, reading.spans().size());
        assertEquals(0, reading.spans().get(0).start());
        assertEquals(1, reading.spans().get(0).end());
        assertEquals(2, reading.spans().get(1).start());
        assertEquals(3, reading.spans().get(1).end());
    }

    @Test
    public void perWordReadingFallsBackToWrittenWordWhenRomajiMissing() {
        LyricsLine line = new LyricsLine(0, "あ い");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, LyricsLine.NO_TIME, "あ い",
                List.of(new Word(0, 10, "あ", "a", false),
                        new Word(10, 20, "い", null, false))));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, true, null);
        assertEquals("a い", reading.text());
    }

    @Test
    public void perWordReadingWithoutAnyRomajiFallsBackToWholeLine() {
        LyricsLine line = new LyricsLine(0, "日本語");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, "nihongo"));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, true, null);
        assertEquals("nihongo", reading.text());
    }

    @Test
    public void secondaryLineStripsBracketsFromPerWordReading() {
        LyricsLine line = secondaryLine("あ");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, LyricsLine.NO_TIME, "あ",
                List.of(new Word(0, 10, "あ", "(a)", false))));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, true, null);
        assertEquals("a", reading.text());
    }

    @Test
    public void perWordReadingMarksAveragedWhenEveryTimingIsAveraged() {
        LyricsLine line = new LyricsLine(0, "あ");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, LyricsLine.NO_TIME, "あ",
                List.of(new Word(0, 10, "あ", "a", false))));
        List<WordTiming> timings = List.of(new WordTiming(0, 1, 0, 10, null, false, null, true));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, true, timings);
        assertTrue(reading.spans().get(0).averaged());
    }

    @Test
    public void perWordReadingMarksNotAveragedWhenAnyTimingIsReal() {
        LyricsLine line = new LyricsLine(0, "あ");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, LyricsLine.NO_TIME, "あ",
                List.of(new Word(0, 10, "あ", "a", false))));
        List<WordTiming> timings = List.of(new WordTiming(0, 1, 0, 10, null, false, null, false));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, true, timings);
        assertFalse(reading.spans().get(0).averaged());
    }

    @Test
    public void wholeLineReadingIsSpreadOverTimings() {
        LyricsLine line = new LyricsLine(0, "あい");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, "a i"));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, false, perCharacterTimings("あい"));
        assertEquals("a i", reading.text());
        assertNotNull(reading.spans());
        assertEquals(2, reading.spans().size());
    }

    @Test
    public void wholeLineReadingWithoutTimingsHasNoSpans() {
        LyricsLine line = new LyricsLine(0, "日本語");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, "nihongo"));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, false, null);
        assertEquals("nihongo", reading.text());
        assertNull(reading.spans());
    }

    @Test
    public void lineRomanizationAtNullForPurelyLatinLine() {
        assertNull(LyricsReadingCalc.lineRomanizationAt(
                new LyricsLine(0, "abc"), 0, List.of(new LyricsLine(0, "abc"))));
    }

    @Test
    public void lineRomanizationAtNullWhenIndexOutOfRange() {
        assertNull(LyricsReadingCalc.lineRomanizationAt(
                new LyricsLine(0, "日本"), 1, List.of(new LyricsLine(0, "nihon"))));
        assertNull(LyricsReadingCalc.lineRomanizationAt(
                new LyricsLine(0, "日本"), 0, null));
    }

    @Test
    public void lineRomanizationAtStripsBracketsOnSecondaryLine() {
        assertEquals("nihongo", LyricsReadingCalc.lineRomanizationAt(
                secondaryLine("日本語"), 0, List.of(new LyricsLine(0, "(nihongo)"))));
    }

    @Test
    public void lineRomanizationAtDropsALongLeadReadingRepeatedOnBackgroundVocal() {
        assertNull(LyricsReadingCalc.lineRomanizationAt(
                secondaryLine("あ"), 1,
                List.of(new LyricsLine(0, "nihongo"), new LyricsLine(0, "(nihongo)"))));
    }

    @Test
    public void lineRomanizationAtKeepsALeadReadingThatOnlyLooksRepeated() {
        assertEquals("nihongo", LyricsReadingCalc.lineRomanizationAt(
                secondaryLine("日本語日本語"), 1,
                List.of(new LyricsLine(0, "nihongo"), new LyricsLine(0, "(nihongo)"))));
    }

    @Test
    public void lineRomanizationAtTrimsReading() {
        assertEquals("nihongo", LyricsReadingCalc.lineRomanizationAt(
                new LyricsLine(0, "日本語"), 0, List.of(new LyricsLine(0, "  nihongo  "))));
    }

    @Test
    public void lineRomanizationAtDropsReadingEqualToOriginal() {
        assertNull(LyricsReadingCalc.lineRomanizationAt(
                new LyricsLine(0, "日本語"), 0, List.of(new LyricsLine(0, "日本語"))));
    }

    @Test
    public void syllableTimingsNullWithoutTimings() {
        assertNull(LyricsReadingCalc.buildSyllableRomanizedTimings(
                new LyricsLine(0, "日本"), 0, null, false, null, "日本", false));
    }

    @Test
    public void syllableTimingsNullWithEmptyMainText() {
        assertNull(LyricsReadingCalc.buildSyllableRomanizedTimings(
                new LyricsLine(0, "日本"), 0, null, false,
                List.of(new WordTiming(0, 2, 0, 10, null, false)), "", false));
    }

    @Test
    public void syllableTimingsNullWithoutReadingOrRuby() {
        assertNull(LyricsReadingCalc.buildSyllableRomanizedTimings(
                new LyricsLine(0, "あいう"), 0, null, false,
                perCharacterTimings("あいう"), "あいう", false));
    }

    @Test
    public void syllableTimingsSplitWholeReadingAcrossCharacters() {
        LyricsLine line = new LyricsLine(0, "遠回りしてでも");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, "Toomawari shite demo"));
        List<WordTiming> result = LyricsReadingCalc.buildSyllableRomanizedTimings(
                line, 0, romanized, false, perCharacterTimings("遠回りしてでも"),
                "遠回りしてでも", false);
        assertEquals("Toomawari shite demo", joinedRomaji(result));
    }

    @Test
    public void syllableTimingsKeepRubyWithoutAnyReading() {
        LyricsLine line = new LyricsLine(0, "日本",
                List.of(new Word(0, 100, "日本", null, false, "にほん")));
        List<WordTiming> timings =
                List.of(new WordTiming(0, 2, 0, 100, null, false, "にほん", false));
        List<WordTiming> result = LyricsReadingCalc.buildSyllableRomanizedTimings(
                line, 0, null, false, timings, "日本", false);
        assertNotNull(result);
        assertEquals(1, result.size());
        assertEquals("にほん", result.get(0).ruby());
    }

    @Test
    public void syllableTimingsDropRubyEqualToTheWrittenWord() {
        LyricsLine line = new LyricsLine(0, "日本",
                List.of(new Word(0, 100, "日本", null, false, "日本")));
        List<WordTiming> timings =
                List.of(new WordTiming(0, 2, 0, 100, null, false, "日本", false));
        assertNull(LyricsReadingCalc.buildSyllableRomanizedTimings(
                line, 0, null, false, timings, "日本", false));
    }

    @Test
    public void syllableTimingsLeaveLatinWordsUnread() {
        LyricsLine line = new LyricsLine(0, "カナab");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, "kana ab"));
        List<WordTiming> timings = List.of(
                new WordTiming(0, 2, 0, 10, null, false),
                new WordTiming(2, 4, 10, 20, null, false));
        List<WordTiming> result = LyricsReadingCalc.buildSyllableRomanizedTimings(
                line, 0, romanized, false, timings, "カナab", false);
        assertNotNull(result);
        assertNull(result.get(1).romaji());
    }

    @Test
    public void hasAnyRubyOnLine() {
        assertTrue(LyricsReadingCalc.hasAnyRuby(new LyricsLine(0, "日本",
                List.of(new Word(0, 10, "日本", null, false, "にほん")))));
        assertFalse(LyricsReadingCalc.hasAnyRuby(new LyricsLine(0, "日本")));
    }

    @Test
    public void hasAnyRubyOnTimings() {
        assertTrue(LyricsReadingCalc.hasAnyRuby(
                List.of(new WordTiming(0, 1, 0, 1, null, false, "に", false))));
        assertFalse(LyricsReadingCalc.hasAnyRuby(
                List.of(new WordTiming(0, 1, 0, 1, null, false))));
    }

    @Test
    public void wholeLineRubyJoinsWordsAndKeepsSpaces() {
        LyricsLine line = new LyricsLine(0, "日本語", List.of(
                new Word(0, 10, "日本", null, true, "にほん"),
                new Word(10, 20, "語", null, false, "ご")));
        assertEquals("にほん ご", LyricsReadingCalc.wholeLineRuby(line));
    }

    @Test
    public void wholeLineRubySkipsRubyRepeatingTheWord() {
        LyricsLine line = new LyricsLine(0, "あ", List.of(
                new Word(0, 10, "あ", null, false, "あ")));
        assertNull(LyricsReadingCalc.wholeLineRuby(line));
    }

    @Test
    public void readingKeyKeepsOnlyLettersAndDigitsLowercased() {
        assertEquals("abc123", LyricsReadingCalc.readingKey("A-b c!123"));
        assertEquals("", LyricsReadingCalc.readingKey(null));
    }

    @Test
    public void hasReadableTextIgnoresPunctuationAndSpaces() {
        assertFalse(LyricsReadingCalc.hasReadableText(" ... "));
        assertTrue(LyricsReadingCalc.hasReadableText("...a..."));
        assertFalse(LyricsReadingCalc.hasReadableText(null));
    }

    @Test
    public void spellsSyllableChecksBothEnds() {
        assertTrue(LyricsReadingCalc.spellsSyllable("日本"));
        assertTrue(LyricsReadingCalc.spellsSyllable("aあ"));
        assertFalse(LyricsReadingCalc.spellsSyllable("ab"));
        assertFalse(LyricsReadingCalc.spellsSyllable(""));
    }

    @Test
    public void isCjkAtReadsTheCodePoint() {
        assertTrue(LyricsReadingCalc.isCjkAt("あ", 0));
        assertFalse(LyricsReadingCalc.isCjkAt("a", 0));
    }

    @Test
    public void isEntirelyAveragedNeedsEveryTiming() {
        assertTrue(LyricsReadingCalc.isEntirelyAveraged(List.of(
                new WordTiming(0, 1, 0, 1, null, false, null, true))));
        assertFalse(LyricsReadingCalc.isEntirelyAveraged(List.of(
                new WordTiming(0, 1, 0, 1, null, false, null, true),
                new WordTiming(1, 2, 1, 2, null, false, null, false))));
    }

    @Test
    public void countFilledChunksIgnoresBlankAndNull() {
        assertEquals(2, LyricsReadingCalc.countFilledChunks(
                new String[]{"a", " ", null, "b"}));
    }

    @Test
    public void anyReadingNeedsANonBlankRomaji() {
        assertTrue(LyricsReadingCalc.anyReading(
                List.of(new Word(0, 1, "あ", "a", false))));
        assertFalse(LyricsReadingCalc.anyReading(
                List.of(new Word(0, 1, "あ", "  ", false))));
    }

    @Test
    public void sameWrittenWordRequiresSideBySideNonSyllables() {
        assertTrue(LyricsReadingCalc.sameWrittenWord(0, 0, "ab", "cd"));
        assertFalse(LyricsReadingCalc.sameWrittenWord(0, 1, "ab", "cd"));
        assertFalse(LyricsReadingCalc.sameWrittenWord(0, 0, "あ", "い"));
    }

    @Test
    public void windowEndForStopsAtTheNextWordStart() {
        List<Word> words = List.of(
                new Word(0, 1000, "a"),
                new Word(2000, 3000, "b"));
        assertEquals(2000, LyricsReadingCalc.windowEndFor(words, 0, 1000));
        assertEquals(3000, LyricsReadingCalc.windowEndFor(words, 1, 3000));
    }

    @Test
    public void windowEndForKeepsTheWordEndWhenItMeetsTheNextStart() {
        List<Word> words = List.of(
                new Word(0, 2000, "a"),
                new Word(2000, 3000, "b"));
        assertEquals(2000, LyricsReadingCalc.windowEndFor(words, 0, 2000));
    }

    @Test
    public void wordsAtSkipsTimingsOutsideTheText() {
        List<Word> words = LyricsReadingCalc.wordsAt("abc", List.of(
                new WordTiming(0, 3, 0, 10, null, false),
                new WordTiming(5, 9, 10, 20, null, false)));
        assertEquals(1, words.size());
        assertEquals("abc", words.get(0).text());
    }
}
