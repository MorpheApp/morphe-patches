/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class LocalRomanizerTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    /** Compares two int[][] token arrays element by element. */
    private static void assertTokens(int[][] expected, int[][] actual) {
        assertEquals("token count", expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) {
            assertArrayEquals("token " + i, expected[i], actual[i]);
        }
    }

    // ── readingTokens ──

    @Test
    public void readingTokensMultipleTokens() {
        assertTokens(new int[][]{{0, 1}, {2, 3}, {4, 5}}, LocalRomanizer.readingTokens("a b c"));
    }

    @Test
    public void readingTokensSingleToken() {
        assertTokens(new int[][]{{0, 3}}, LocalRomanizer.readingTokens("abc"));
    }

    @Test
    public void readingTokensSkipsMarkRuns() {
        // The "..." has no letter or digit, so it is not a spoken token.
        assertTokens(new int[][]{{0, 1}, {6, 7}}, LocalRomanizer.readingTokens("a ... b"));
    }

    @Test
    public void readingTokensNullEmptyWhitespace() {
        assertEquals(0, LocalRomanizer.readingTokens(null).length);
        assertEquals(0, LocalRomanizer.readingTokens("").length);
        assertEquals(0, LocalRomanizer.readingTokens("   ").length);
    }

    // ── spreadReading ──

    @Test
    public void spreadReadingOneSyllablePerCharacter() {
        // Two words of one Han each, reading "ni hao": each word gets one token.
        List<Word> words = List.of(new Word(0, 1000, "你"), new Word(1000, 2000, "好"));
        //noinspection DataFlowIssue
        assertTokens(new int[][]{{0, 1}, {1, 2}}, LocalRomanizer.spreadReading(words, "ni hao"));
    }

    @Test
    public void spreadReadingOnePerWord() {
        // One word of two Han, reading with one token: the whole token goes to the word.
        List<Word> words = List.of(new Word(0, 2000, "你好"));
        //noinspection DataFlowIssue
        assertTokens(new int[][]{{0, 1}}, LocalRomanizer.spreadReading(words, "nihao"));
    }

    @Test
    public void spreadReadingReturnsNullOnMismatch() {
        // Two single-char words (2 syllables) but three tokens: fits neither shape.
        List<Word> words = List.of(new Word(0, 1000, "你"), new Word(1000, 2000, "好"));
        assertNull(LocalRomanizer.spreadReading(words, "a b c"));
    }

    @Test
    public void spreadReadingNullOrEmptyInputs() {
        List<Word> words = List.of(new Word(0, 1000, "你"));
        assertNull(LocalRomanizer.spreadReading(words, null));
        assertNull(LocalRomanizer.spreadReading(new ArrayList<>(), "a b"));
    }

    // ── allocateReading ──

    @Test
    public void allocateReadingSpreadsWithGuides() {
        // The trailing space after "a" in the source is preserved by joinTokens.
        String[] out = LocalRomanizer.allocateReading("a b", 2, List.of("a", "b"));
        assertArrayEquals(new String[]{"a ", "b"}, out);
    }

    @Test
    public void allocateReadingMergesIntoOneSlot() {
        // Two tokens, one slot: both must merge into slot 0.
        String[] out = LocalRomanizer.allocateReading("a b", 1, null);
        assertArrayEquals(new String[]{"a b"}, out);
    }

    @Test
    public void allocateReadingSingleToken() {
        assertArrayEquals(new String[]{"a"}, LocalRomanizer.allocateReading("a", 1, null));
    }

    @Test
    public void allocateReadingNullEmptyAndZeroSlots() {
        assertArrayEquals(new String[]{"", "", ""}, LocalRomanizer.allocateReading(null, 3, null));
        assertArrayEquals(new String[]{"", ""}, LocalRomanizer.allocateReading("", 2, null));
        assertArrayEquals(new String[0], LocalRomanizer.allocateReading("a", 0, null));
    }

    // ── romanize ──

    @Test
    public void romanizeKoreanChanges() {
        LocalRomanizer.Result result = LocalRomanizer.romanize(List.of("사랑해"));
        assertNotNull(result);
        assertEquals("salanghae", result.lines.get(0));
        assertEquals("Built-in Romanization", result.dictionary);
    }

    @Test
    public void romanizeJapaneseKanaChanges() {
        LocalRomanizer.Result result = LocalRomanizer.romanize(List.of("こんにちは"));
        assertNotNull(result);
        assertEquals("konnichiha", result.lines.get(0));
        // Kana by rule is not the dictionary's work, so no name is credited.
        assertNull(result.dictionary);
    }

    @Test
    public void romanizeHanUnchangedReturnsNull() {
        // No character table loaded: Han is left alone, so nothing changed.
        assertNull(LocalRomanizer.romanize(List.of("你好")));
    }

    @Test
    public void romanizeLatinOtherReturnsNull() {
        assertNull(LocalRomanizer.romanize(List.of("Hello")));
    }

    @Test
    public void romanizeEmptyOrNull() {
        assertNull(LocalRomanizer.romanize(new ArrayList<>()));
        assertNull(LocalRomanizer.romanize(null));
    }

    // ── romanizeRime ──

    @Test
    public void romanizeRimeHanUnchangedReturnsNull() {
        // Rime word table not ready: Han passes through unchanged.
        assertNull(LocalRomanizer.romanizeRime(List.of("你好")));
    }

    @Test
    public void romanizeRimeKoreanChanges() {
        // Rime only changes the Han branch; Korean still reads by rule.
        LocalRomanizer.Result result = LocalRomanizer.romanizeRime(List.of("사랑해"));
        assertNotNull(result);
        assertEquals("salanghae", result.lines.get(0));
        assertEquals("Built-in Romanization", result.dictionary);
    }

    // ── syllableReading ──

    @Test
    public void syllableReadingKorean() {
        assertEquals("ga", LocalRomanizer.syllableReading("가", "가나다"));
        assertEquals("ga", LocalRomanizer.syllableReading("가", null));
    }

    @Test
    public void syllableReadingNullEmptyNonHan() {
        assertNull(LocalRomanizer.syllableReading(null, null));
        assertNull(LocalRomanizer.syllableReading("", null));
        assertNull(LocalRomanizer.syllableReading("A", null));
    }

    // ── alignWords ──

    @Test
    public void alignWordsMismatchedSizesReturnsNull() {
        List<LyricsLine> lines = List.of(new LyricsLine(0, "你", List.of(new Word(0, 1000, "你"))));
        assertNull(LocalRomanizer.alignWords(lines, new ArrayList<>()));
        assertNull(LocalRomanizer.alignWords(null, null));
    }

    @Test
    public void alignWordsLaysReadingOnWords() {
        // The whole-line reading "ni hao" is spread one syllable per character.
        List<Word> splitWords = List.of(new Word(0, 1000, "你"), new Word(1000, 2000, "好"));
        LyricsLine line = new LyricsLine(0, "你好", splitWords);
        LyricsLine whole = new LyricsLine(0, "ni hao", List.of(new Word(0, 2000, "ni hao")));

        List<LyricsLine> result = LocalRomanizer.alignWords(List.of(line), List.of(whole));
        assertNotNull(result);
        assertEquals(1, result.size());
        List<Word> read = result.get(0).words();
        assertEquals("ni", read.get(0).romaji());
        assertEquals("hao", read.get(1).romaji());
    }

    @Test
    public void alignWordsNothingChangedReturnsNull() {
        // The whole-line reading equals the original, so no word can take a reading.
        List<Word> words = List.of(new Word(0, 1000, "你"));
        LyricsLine line = new LyricsLine(0, "你", words);
        LyricsLine whole = new LyricsLine(0, "你", List.of(new Word(0, 1000, "你")));
        assertNull(LocalRomanizer.alignWords(List.of(line), List.of(whole)));
    }

    @Test
    public void alignWordsOneWordGetsWholeReading() {
        // One word of two Han, reading with two tokens: one-per-character spread.
        List<Word> words = List.of(new Word(0, 2000, "你好"));
        LyricsLine line = new LyricsLine(0, "你好", words);
        LyricsLine whole = new LyricsLine(0, "ni hao", List.of(new Word(0, 2000, "ni hao")));

        List<LyricsLine> result = LocalRomanizer.alignWords(List.of(line), List.of(whole));
        assertNotNull(result);
        assertEquals("ni hao", result.get(0).words().get(0).romaji());
    }
}
