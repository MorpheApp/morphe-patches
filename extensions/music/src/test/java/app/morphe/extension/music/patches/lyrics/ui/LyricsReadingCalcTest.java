/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4709
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
import java.util.Objects;

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
        boolean pendingSpace = false;
        for (WordTiming timing : timings) {
            String romaji = timing.romaji();
            if (romaji == null || romaji.trim().isEmpty()) {
                continue;
            }
            if (!out.isEmpty() && pendingSpace) {
                out.append(' ');
            }
            out.append(romaji.trim());
            pendingSpace = timing.hasTrailingSpace() || romaji.endsWith(" ");
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
        assertTrue(Objects.requireNonNull(reading.spans()).get(0).averaged());
    }

    @Test
    public void perWordReadingMarksNotAveragedWhenAnyTimingIsReal() {
        LyricsLine line = new LyricsLine(0, "あ");
        List<LyricsLine> romanized = List.of(new LyricsLine(0, LyricsLine.NO_TIME, "あ",
                List.of(new Word(0, 10, "あ", "a", false))));
        List<WordTiming> timings = List.of(new WordTiming(0, 1, 0, 10, null, false, null, false));
        LyricsReadingCalc.Reading reading = LyricsReadingCalc.readingFor(
                line, 0, romanized, true, timings);
        assertFalse(Objects.requireNonNull(reading.spans()).get(0).averaged());
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
        assertNotNull(result);
        assertEquals("Toomawari shite demo", joinedRomaji(result));
        assertEquals("Too", result.get(0).romaji());
        assertEquals("mawa", result.get(1).romaji());
        assertEquals("ri ", result.get(2).romaji());
        assertEquals("shi", result.get(3).romaji());
        assertEquals("te ", result.get(4).romaji());
        assertEquals("de", result.get(5).romaji());
        assertEquals("mo", result.get(6).romaji());
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

    @Test
    public void syllableTimingsFillEmbeddedPartialRubySlotsFromWholeReading() {
        String main = "やって来ましたよ真白な";
        LyricsLine line = new LyricsLine(0, 2200, main, List.of(
                new Word(0, 700, "やって", null, false, null),
                new Word(700, 1000, "来", null, false, "き"),
                new Word(1000, 1400, "ました", null, false, null),
                new Word(1400, 1600, "よ", "yo", false, null),
                new Word(1600, 2000, "真白", null, false, "まっしろ"),
                new Word(2000, 2200, "な", null, false, null)
        ));
        List<WordTiming> timings = List.of(
                new WordTiming(0, 3, 0, 700, null, false),
                new WordTiming(3, 4, 700, 1000, null, false, "き", false),
                new WordTiming(4, 7, 1000, 1400, null, false),
                new WordTiming(7, 8, 1400, 1600, "yo", false),
                new WordTiming(8, 10, 1600, 2000, null, false, "まっしろ", false),
                new WordTiming(10, 11, 2000, 2200, null, false)
        );
        List<LyricsLine> romanized = List.of(new LyricsLine(0, "yatte kimashita yo masshiro na"));
        List<WordTiming> result = LyricsReadingCalc.buildSyllableRomanizedTimings(
                line, 0, romanized, true, timings, main, true);
        assertNotNull(result);
        assertEquals(6, result.size());
        assertEquals("yatte kimashita yo masshiro na", joinedRomaji(result));
        assertTrue(result.get(1).romaji() != null && !result.get(1).romaji().isEmpty());
        assertTrue(result.get(4).romaji() != null && !result.get(4).romaji().isEmpty());
    }

    @Test
    public void koreanSyllableAlignmentSplitsMultiSyllableTokensAndKeepsLatinUnread() {
        // Case 1: "다 자연스럽게 알아, give me space" with subword split on 자연스 / 럽 / 게
        String line1Text = "다 자연스럽게 알아, give me space";
        List<WordTiming> line1Timings = List.of(
                new WordTiming(0, 1, 0, 100, null, true),        // 다
                new WordTiming(2, 5, 100, 300, null, false),     // 자연스
                new WordTiming(5, 6, 300, 400, null, false),     // 럽
                new WordTiming(6, 7, 400, 500, null, true),      // 게
                new WordTiming(8, 11, 500, 700, null, true),     // 알아,
                new WordTiming(12, 16, 700, 800, null, true),    // give
                new WordTiming(17, 19, 800, 900, null, true),    // me
                new WordTiming(20, 25, 900, 1000, null, false)   // space
        );
        List<WordTiming> r1 = LyricsReadingCalc.buildSyllableRomanizedTimings(
                new LyricsLine(0, line1Text), 0,
                List.of(new LyricsLine(0, "da jayeonseureopge ara, give me space")),
                false, line1Timings, line1Text, false);
        assertNotNull(r1);
        assertEquals("da", r1.get(0).romaji().trim());
        assertEquals("jayeonseu", r1.get(1).romaji().trim());
        assertEquals("reop", r1.get(2).romaji().trim());
        assertEquals("ge", r1.get(3).romaji().trim());
        assertEquals("ara,", r1.get(4).romaji().trim());
        assertNull(r1.get(5).romaji());
        assertNull(r1.get(6).romaji());
        assertNull(r1.get(7).romaji());

        // Case 2: "언제나 그래 눈에 뛸 my face" with per-syllable split on 언 / 제 / 나
        String line2Text = "언제나 그래 눈에 뛸 my face";
        List<WordTiming> line2Timings = List.of(
                new WordTiming(0, 1, 0, 100, null, false),
                new WordTiming(1, 2, 100, 200, null, false),
                new WordTiming(2, 3, 200, 300, null, true),
                new WordTiming(4, 6, 300, 500, null, true),
                new WordTiming(7, 9, 500, 700, null, true),
                new WordTiming(10, 11, 700, 800, null, true),
                new WordTiming(12, 14, 800, 900, null, true),
                new WordTiming(15, 19, 900, 1000, null, false)
        );
        List<WordTiming> r2 = LyricsReadingCalc.buildSyllableRomanizedTimings(
                new LyricsLine(0, line2Text), 0,
                List.of(new LyricsLine(0, "eonjena geurae nune ttuil my face")),
                false, line2Timings, line2Text, false);
        assertNotNull(r2);
        assertEquals("eon", r2.get(0).romaji().trim());
        assertEquals("je", r2.get(1).romaji().trim());
        assertEquals("na", r2.get(2).romaji().trim());
        assertEquals("geurae", r2.get(3).romaji().trim());
        assertEquals("nune", r2.get(4).romaji().trim());
        assertEquals("ttuil", r2.get(5).romaji().trim());
        assertNull(r2.get(6).romaji());
        assertNull(r2.get(7).romaji());

        // Case 3: "흘겨 보지마, 이건 no race" must never pull Latin "no" / "race" onto Korean "보지마,"
        String line3Text = "흘겨 보지마, 이건 no race";
        List<WordTiming> line3Timings = List.of(
                new WordTiming(0, 1, 0, 100, null, false),       // 흘
                new WordTiming(1, 2, 100, 200, null, true),      // 겨
                new WordTiming(3, 7, 200, 500, null, true),      // 보지마,
                new WordTiming(8, 10, 500, 700, null, true),     // 이건
                new WordTiming(11, 13, 700, 850, null, true),    // no
                new WordTiming(14, 18, 850, 1000, null, false)   // race
        );
        List<WordTiming> r3 = LyricsReadingCalc.buildSyllableRomanizedTimings(
                new LyricsLine(0, line3Text), 0,
                List.of(new LyricsLine(0, "heulgyeobojima, igeon no race")),
                false, line3Timings, line3Text, false);
        assertNotNull(r3);
        assertFalse(r3.get(2).romaji().contains("no"));
        assertEquals("igeon", r3.get(3).romaji().trim());
        assertNull(r3.get(4).romaji());
        assertNull(r3.get(5).romaji());
    }

    @Test
    public void shareRomanizationAvoidsLeakingRawKanaWhenWordRomajiIsIncomplete() {
        // Simulate whole-line romanized text alongside partial first-word romaji metadata
        List<Word> partialWords = List.of(
                new Word(0, 300, "いつも", "Itsumodoori", false, null),
                new Word(300, 600, "通", null, false, null),
                new Word(600, 800, "り", null, false, null)
        );
        String shareOrig = "いつも通り";
        LyricsLine shareOrigLine = new LyricsLine(0, shareOrig);
        List<LyricsLine> romanizedLines = List.of(
                new LyricsLine(0, 800, "Itsumodoori", partialWords)
        );
        List<WordTiming> timings = List.of(
                new WordTiming(0, 3, 0, 300, null, false),
                new WordTiming(3, 4, 300, 600, null, false),
                new WordTiming(4, 5, 600, 800, null, false)
        );
        String shareRom = LyricsReadingCalc.shareRomanization(
                shareOrigLine, 0, romanizedLines, false, timings, shareOrig);
        assertNotNull(shareRom);
        assertFalse("Must never leak raw kana/kanji into share romaji: " + shareRom,
                app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector.contains(
                        shareRom, Character.UnicodeScript.HIRAGANA)
                        || app.morphe.extension.music.patches.lyrics.romanizers.ScriptDetector.contains(
                                shareRom, Character.UnicodeScript.HAN));
        assertTrue(shareRom.toLowerCase(java.util.Locale.ROOT).contains("itsumo"));
        assertTrue(shareRom.toLowerCase(java.util.Locale.ROOT).contains("doo"));
    }

    @Test
    public void buildSyllableRomanizedTimings_keepsJapaneseWordsAndReadsOkuriganaCleanly() {
        String text = "限りの無い時を越えて今";
        LyricsLine origLine = new LyricsLine(0, LyricsLine.NO_TIME, text,
                List.of(
                        new Word(0, 100, "限", "かぎ", false),
                        new Word(100, 200, "りの", null, false),
                        new Word(200, 300, "無い", "な", false),
                        new Word(300, 400, "時を", "とき", false),
                        new Word(400, 500, "越", "こ", false),
                        new Word(500, 600, "えて", null, false),
                        new Word(600, 700, "今", "いま", false)
                ),
                null, false, false, null);
        List<WordTiming> slots = List.of(
                new WordTiming(0, 1, 0, 100, "かぎ", false),
                new WordTiming(1, 3, 100, 200, null, false),
                new WordTiming(3, 5, 200, 300, "な", false),
                new WordTiming(5, 7, 300, 400, "とき", false),
                new WordTiming(7, 8, 400, 500, "こ", false),
                new WordTiming(8, 10, 500, 600, null, false),
                new WordTiming(10, 11, 600, 700, "いま", false)
        );
        List<LyricsLine> romanized = List.of(
                new LyricsLine(0, LyricsLine.NO_TIME, text,
                        List.of(
                                new Word(0, 100, "限", "kagi", false),
                                new Word(100, 200, "りの", "ri no", false),
                                new Word(200, 300, "無い", "nai", false),
                                new Word(300, 400, "時を", "toki o", false),
                                new Word(400, 500, "越", "ko", false),
                                new Word(500, 600, "えて", "ete", false),
                                new Word(600, 700, "今", "ima", false)
                        ),
                        null, false, false, null)
        );

        List<WordTiming> timings = LyricsReadingCalc.buildSyllableRomanizedTimings(
                origLine, 0, romanized, true, slots, text, true);

        assertNotNull(timings);
        assertEquals(slots.size(), timings.size());
        assertEquals("kagi", timings.get(0).romaji().trim());
        assertEquals("ri no", timings.get(1).romaji().trim());
        assertEquals("nai", timings.get(2).romaji().trim());
        assertEquals("toki o", timings.get(3).romaji().trim());
        assertEquals("ko", timings.get(4).romaji().trim());
        assertEquals("ete", timings.get(5).romaji().trim());
        assertEquals("ima", timings.get(6).romaji().trim());
    }

    @Test
    public void buildSyllableRomanizedTimings_consumesPreAlignedRomanizedSlotsDirectly() {
        String text = "黒い感情も君へ繋ぐ";
        LyricsLine origLine = new LyricsLine(0, LyricsLine.NO_TIME, text,
                List.of(
                        new Word(0, 100, "黒い", null, false),
                        new Word(100, 250, "感情も", null, false),
                        new Word(250, 350, "君へ", null, false),
                        new Word(350, 500, "繋ぐ", null, false)
                ),
                null, false, false, null);
        List<WordTiming> slots = List.of(
                new WordTiming(0, 2, 0, 100, null, false),
                new WordTiming(2, 5, 100, 250, null, false),
                new WordTiming(5, 7, 250, 350, null, false),
                new WordTiming(7, 9, 350, 500, null, false)
        );
        List<LyricsLine> romanized = List.of(
                new LyricsLine(0, LyricsLine.NO_TIME, "Kuroi kanjou mo kimi e tsunagu",
                        List.of(
                                new Word(0, 100, "黒い", "Kuroi", false),
                                new Word(100, 250, "感情も", "kanjou mo", false),
                                new Word(250, 350, "君へ", "kimi e", false),
                                new Word(350, 500, "繋ぐ", "tsunagu", false)
                        ),
                        null, false, false, null)
        );

        List<WordTiming> timings = LyricsReadingCalc.buildSyllableRomanizedTimings(
                origLine, 0, romanized, true, slots, text, false);

        assertNotNull(timings);
        assertEquals(4, timings.size());
        assertEquals("Kuroi", timings.get(0).romaji().trim());
        assertEquals("kanjou mo", timings.get(1).romaji().trim());
        assertEquals("kimi e", timings.get(2).romaji().trim());
        assertEquals("tsunagu", timings.get(3).romaji().trim());
    }

    @Test
    public void buildSyllableRomanizedTimings_partialRWordsWithWholeRomanFallsBackToWholeAlignment() {
        String text = "黒い感情も君へ繋ぐ";
        LyricsLine origLine = new LyricsLine(0, LyricsLine.NO_TIME, text,
                List.of(
                        new Word(0, 100, "黒い", null, false),
                        new Word(100, 250, "感情も", null, false),
                        new Word(250, 350, "君へ", null, false),
                        new Word(350, 500, "繋ぐ", null, false)
                ),
                null, false, false, null);
        List<WordTiming> slots = List.of(
                new WordTiming(0, 2, 0, 100, null, false),
                new WordTiming(2, 5, 100, 250, null, false),
                new WordTiming(5, 7, 250, 350, null, false),
                new WordTiming(7, 9, 350, 500, null, false)
        );
        // Partial per-word reading (only slot 0 populated) must not skip wholeRoman alignment.
        List<LyricsLine> romanized = List.of(
                new LyricsLine(0, LyricsLine.NO_TIME, "kuroi kanjou mo kimi e tsunagu",
                        List.of(
                                new Word(0, 100, "黒い", "kuroi", false),
                                new Word(100, 250, "感情も", null, false),
                                new Word(250, 350, "君へ", null, false),
                                new Word(350, 500, "繋ぐ", null, false)
                        ),
                        null, false, false, null)
        );

        List<WordTiming> timings = LyricsReadingCalc.buildSyllableRomanizedTimings(
                origLine, 0, romanized, true, slots, text, true);

        assertNotNull(timings);
        assertEquals("kuroi kanjou mo kimi e tsunagu", joinedRomaji(timings));
    }
}
