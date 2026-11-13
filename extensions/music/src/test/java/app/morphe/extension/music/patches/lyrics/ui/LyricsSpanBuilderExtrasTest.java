/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4592
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class LyricsSpanBuilderExtrasTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void zeroWidthSpaceIsInvisible() {
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u200B'));
    }

    @Test
    public void bidiControlsAreInvisible() {
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u202A'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u202B'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u202C'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u202D'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u202E'));
    }

    @Test
    public void directionalityOverridesAreInvisible() {
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u2066'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u2067'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u2068'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u2069'));
    }

    @Test
    public void bomIsInvisible() {
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\uFEFF'));
    }

    @Test
    public void zeroWidthJoinerIsInvisible() {
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u200C'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u200D'));
    }

    @Test
    public void lrmAndRlmAreInvisible() {
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u200E'));
        assertTrue(LyricsTextUtils.isBidiOrInvisible('\u200F'));
    }

    @Test
    public void regularCharsAreNotInvisible() {
        assertFalse(LyricsTextUtils.isBidiOrInvisible('a'));
        assertFalse(LyricsTextUtils.isBidiOrInvisible('\u4F60'));
        assertFalse(LyricsTextUtils.isBidiOrInvisible(' '));
        assertFalse(LyricsTextUtils.isBidiOrInvisible('\n'));
    }

    @Test
    public void stripBidiRemovesZeroWidthSpace() {
        assertEquals("helloworld", LyricsTextUtils.stripBidiControls("hello\u200Bworld"));
    }

    @Test
    public void stripBidiRemovesMultipleInvisibleChars() {
        assertEquals("abc", LyricsTextUtils.stripBidiControls("a\u200Bb\uFEFFc"));
    }

    @Test
    public void stripBidiRemovesBidiControls() {
        assertEquals("helloworld", LyricsTextUtils.stripBidiControls("hello\u202A\u202Bworld"));
    }

    @Test
    public void stripBidiLeavesCleanTextUnchanged() {
        assertEquals("hello world", LyricsTextUtils.stripBidiControls("hello world"));
    }

    @Test
    public void stripBidiNullReturnsEmpty() {
        assertEquals("", LyricsTextUtils.stripBidiControls(null));
    }

    @Test
    public void stripBidiEmptyReturnsEmpty() {
        assertEquals("", LyricsTextUtils.stripBidiControls(""));
    }

    @Test
    public void stripBidiAllInvisibleReturnsEmpty() {
        assertEquals("", LyricsTextUtils.stripBidiControls("\u200B\uFEFF\u202E\u202C"));
    }

    @Test
    public void arabicContainsJoiningScript() {
        assertTrue(LyricsTextUtils.containsJoiningScript("\u0645\u0631\u062D\u0628\u0627"));
    }

    @Test
    public void persianContainsJoiningScript() {
        assertTrue(LyricsTextUtils.containsJoiningScript("\u0633\u0644\u0627\u0645"));
    }

    @Test
    public void zwjTriggersJoiningScript() {
        assertTrue(LyricsTextUtils.containsJoiningScript("a\u200Db"));
    }

    @Test
    public void zwnjTriggersJoiningScript() {
        assertTrue(LyricsTextUtils.containsJoiningScript("a\u200Cb"));
    }

    @Test
    public void latinDoesNotContainJoiningScript() {
        assertFalse(LyricsTextUtils.containsJoiningScript("hello world"));
    }

    @Test
    public void cjkDoesNotContainJoiningScript() {
        assertFalse(LyricsTextUtils.containsJoiningScript("\u4F60\u597D\u4E16\u754C"));
    }

    @Test
    public void devanagariWithCombiningMarksContainsJoiningScript() {
        // Devanagari virama and vowel signs are NON_SPACING_MARK.
        assertTrue(LyricsTextUtils.containsJoiningScript("\u0928\u092E\u0938\u094D\u0947\u0924\u0947"));
    }

    @Test
    public void devanagariConsonantsOnlyDoNotContainJoiningScript() {
        // Pure Devanagari consonants without combining marks are just LETTER category.
        assertFalse(LyricsTextUtils.containsJoiningScript("\u0928\u092E"));
    }

    @Test
    public void emptyDoesNotContainJoiningScript() {
        assertFalse(LyricsTextUtils.containsJoiningScript(""));
    }

    @Test
    public void nullDoesNotContainJoiningScript() {
        assertFalse(LyricsTextUtils.containsJoiningScript(null));
    }

    @Test
    public void arabicIsRtl() {
        assertTrue(LyricsSpanBuilder.isRtlText("\u0645\u0631\u062D\u0628\u0627 \u0628\u0627\u0644\u0639\u0627\u0644\u0645"));
    }

    @Test
    public void hebrewIsRtl() {
        assertTrue(LyricsSpanBuilder.isRtlText("\u05E9\u05DC\u05D5\u05DD \u05E2\u05D5\u05DC\u05DD"));
    }

    @Test
    public void latinIsNotRtl() {
        assertFalse(LyricsSpanBuilder.isRtlText("hello world"));
    }

    @Test
    public void cjkIsNotRtl() {
        assertFalse(LyricsSpanBuilder.isRtlText("\u4F60\u597D\u4E16\u754C"));
    }

    @Test
    public void mixedWithMajorityRtl() {
        assertTrue(LyricsSpanBuilder.isRtlText("\u0645\u0631\u062D\u0628\u0627a"));
    }

    @Test
    public void mixedWithMajorityLtr() {
        assertFalse(LyricsSpanBuilder.isRtlText("aaaaaaaa\u0645\u0631\u062D\u0628\u0627"));
    }

    @Test
    public void emptyIsNotRtl() {
        assertFalse(LyricsSpanBuilder.isRtlText(""));
    }

    @Test
    public void nullIsNotRtl() {
        assertFalse(LyricsSpanBuilder.isRtlText(null));
    }

    @Test
    public void digitsOnlyIsNotRtl() {
        assertFalse(LyricsSpanBuilder.isRtlText("12345"));
    }

    @Test
    public void skipNewlinesAtStart() {
        assertEquals(2, LyricsTextUtils.skipNewlines("\n\nhello", 0));
    }

    @Test
    public void skipNewlinesCrLf() {
        assertEquals(2, LyricsTextUtils.skipNewlines("\r\nhello", 0));
    }

    @Test
    public void skipNewlinesMixedCrAndLf() {
        assertEquals(3, LyricsTextUtils.skipNewlines("\r\n\rhello", 0));
    }

    @Test
    public void skipNewlinesNoNewlines() {
        assertEquals(0, LyricsTextUtils.skipNewlines("hello", 0));
    }

    @Test
    public void skipNewlinesAtOffset() {
        assertEquals(4, LyricsTextUtils.skipNewlines("he\n\nllo", 2));
    }

    @Test
    public void skipNewlinesAllNewlines() {
        assertEquals(3, LyricsTextUtils.skipNewlines("\n\n\n", 0));
    }

    @Test
    public void skipNewlinesAtEnd() {
        assertEquals(6, LyricsTextUtils.skipNewlines("hello\n", 5));
    }

    // --- CJK per-syllable word boundary tests (regression fix for 81e1b9a) ---

    private static int[] invokeWordBoundaries(String text) throws Exception {
        Method m = LyricsWordBoundaries.class.getDeclaredMethod("wordBoundaries", String.class);
        m.setAccessible(true);
        return (int[]) m.invoke(null, text);
    }

    private static int invokeCountWords(String text) throws Exception {
        Method m = LyricsWordBoundaries.class.getDeclaredMethod("countWords", String.class);
        m.setAccessible(true);
        return (int) m.invoke(null, text);
    }

    @Test
    public void thaiWordsKeepTheirMarks() throws Exception {
        final String text = "ฉันรักคุณ";
        int[] bounds = invokeWordBoundaries(text);
        assertEquals(0, bounds[0]);
        assertEquals(text.length(), bounds[bounds.length - 1]);
        for (int bound : bounds) {
            if (bound < text.length()) {
                assertNotSame("A word never starts at a combining mark: " + bound, Character.getType(text.charAt(bound)), Character.NON_SPACING_MARK);
            }
        }
    }

    private static boolean invokeHasCjkCodePoint(String text) throws Exception {
        Method m = LyricsWordBoundaries.class.getDeclaredMethod("hasCjkCodePoint", String.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, text);
    }

    @Test
    public void cjkWordBoundariesSplitPerCharacter() throws Exception {
        // 6 CJK characters: 6 spans, so 12 bound values.
        int[] bounds = invokeWordBoundaries("今天天气很好");
        assertEquals(12, bounds.length);
    }

    @Test
    public void cjkCountWordsEqualsCharacterCount() throws Exception {
        // Each CJK character is one syllable = one word
        assertEquals(6, invokeCountWords("今天天气很好"));
    }

    @Test
    public void cjkWordBoundariesEachCharacterOwnSpan() throws Exception {
        // Each CJK character is its own span: [0,1, 1,2].
        int[] bounds = invokeWordBoundaries("你好");
        assertEquals(4, bounds.length);
        assertEquals(0, bounds[0]);
        assertEquals(1, bounds[1]);
        assertEquals(1, bounds[2]);
        assertEquals(2, bounds[3]);
    }

    @Test
    public void cjkCountWordsSingleCharacter() throws Exception {
        assertEquals(1, invokeCountWords("好"));
    }

    @Test
    public void cjkCountWordsEmpty() throws Exception {
        assertEquals(0, invokeCountWords(""));
    }

    @Test
    public void cjkWithSpaceCountWords() throws Exception {
        // 4 CJK characters, a space between the pairs.
        assertEquals(4, invokeCountWords("你好 世界"));
    }

    @Test
    public void latinWordBoundariesProduceTwoWords() throws Exception {
        // "hello world": 2 spans, so 4 bound values.
        int[] bounds = invokeWordBoundaries("hello world");
        assertEquals(4, bounds.length);
    }

    @Test
    public void latinCountWordsTwoWords() throws Exception {
        assertEquals(2, invokeCountWords("hello world"));
    }

    @Test
    public void latinCountWordsSingleWord() throws Exception {
        assertEquals(1, invokeCountWords("hello"));
    }

    @Test
    public void mixedCjkLatinSplitsCjkPerCharacter() throws Exception {
        // Two CJK characters and "hello": 3 spans, so 6 bound values.
        int[] bounds = invokeWordBoundaries("你好hello");
        assertEquals(6, bounds.length);
    }

    @Test
    public void mixedCjkLatinCountWords() throws Exception {
        // Two CJK characters and "hello": 3 words.
        assertEquals(3, invokeCountWords("你好hello"));
    }

    @Test
    public void mixedCjkLatinWithSpace() throws Exception {
        // Two CJK characters, a space and "hello": 3 words.
        assertEquals(3, invokeCountWords("你好 hello"));
    }

    @Test
    public void japaneseKanaSplitsPerCharacter() throws Exception {
        // 5 kana: 5 words.
        assertEquals(5, invokeCountWords("こんにちは"));
    }

    @Test
    public void koreanHangulSplitsPerSyllable() throws Exception {
        // 5 Hangul syllables: 5 words.
        assertEquals(5, invokeCountWords("안녕하세요"));
    }

    @Test
    public void hasCjkCodePointDetectsHan() throws Exception {
        assertTrue(invokeHasCjkCodePoint("今天天气"));
    }

    @Test
    public void hasCjkCodePointDetectsKana() throws Exception {
        assertTrue(invokeHasCjkCodePoint("こんにちは"));
    }

    @Test
    public void hasCjkCodePointDetectsHangul() throws Exception {
        assertTrue(invokeHasCjkCodePoint("안녕하세요"));
    }

    @Test
    public void hasCjkCodePointDetectsMixed() throws Exception {
        assertTrue(invokeHasCjkCodePoint("hello 世界"));
    }

    @Test
    public void hasCjkCodePointRejectsLatin() throws Exception {
        assertFalse(invokeHasCjkCodePoint("hello world"));
    }

    @Test
    public void hasCjkCodePointRejectsEmpty() throws Exception {
        assertFalse(invokeHasCjkCodePoint(""));
    }

    @Test
    public void hasCjkCodePointRejectsPunctuation() throws Exception {
        assertFalse(invokeHasCjkCodePoint("!!!???..."));
    }
}
