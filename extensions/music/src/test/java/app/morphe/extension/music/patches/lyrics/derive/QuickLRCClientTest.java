/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.derive;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class QuickLRCClientTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void isCjkTrueForHanCharacter() throws Exception {
        assertTrue(callIsCjk('我'));
    }

    @Test
    public void isCjkTrueForKana() throws Exception {
        assertTrue(callIsCjk('あ'));
    }

    @Test
    public void isCjkTrueForHangul() throws Exception {
        assertTrue(callIsCjk('가'));
    }

    @Test
    public void isCjkTrueForCjkPunctuation() throws Exception {
        assertTrue(callIsCjk('，'));
    }

    @Test
    public void isCjkTrueForFullwidthPunctuation() throws Exception {
        assertTrue(callIsCjk('！'));
    }

    @Test
    public void isCjkTrueForCjkSymbols() throws Exception {
        assertTrue(callIsCjk('、'));
    }

    @Test
    public void isCjkFalseForAscii() throws Exception {
        assertFalse(callIsCjk('a'));
    }

    @Test
    public void isCjkFalseForSpace() throws Exception {
        assertFalse(callIsCjk(' '));
    }

    @Test
    public void dropCjkSeparatorsRemovesBetweenCjk() throws Exception {
        assertEquals("我靠", callDropCjkSeparators("我 靠"));
    }

    @Test
    public void dropCjkSeparatorsKeepsAsciiGap() throws Exception {
        assertEquals("我 world", callDropCjkSeparators("我 world"));
    }

    @Test
    public void dropCjkSeparatorsKeepsAsciiBothSides() throws Exception {
        assertEquals("hello world", callDropCjkSeparators("hello world"));
    }

    @Test
    public void dropCjkSeparatorsRemovesMultipleSpaces() throws Exception {
        assertEquals("我靠", callDropCjkSeparators("我   靠"));
    }

    @Test
    public void dropCjkSeparatorsKeepsTrailingAscii() throws Exception {
        assertEquals("我靠 world", callDropCjkSeparators("我 靠 world"));
    }

    @Test
    public void dropCjkSeparatorsWithTags() throws Exception {
        // Tags count as CJK through the text they wrap
        String result = callDropCjkSeparators("<t>我 <t>靠");
        assertEquals("<t>我<t>靠", result);
    }

    @Test
    public void dropCjkSeparatorsEmptyString() throws Exception {
        assertEquals("", callDropCjkSeparators(""));
    }

    @Test
    public void dropCjkSeparatorsNoGaps() throws Exception {
        assertEquals("我靠", callDropCjkSeparators("我靠"));
    }

    @Test
    public void dropCjkSeparatorsKeepsCjkPunctuationGap() throws Exception {
        // Full-width punctuation is CJK and should have its gap dropped
        assertEquals("我，靠", callDropCjkSeparators("我 ， 靠"));
    }

    @Test
    public void charAfterTagReturnsFirstCharAfterTag() throws Exception {
        assertEquals('我', callCharAfterTag("<t>我靠", 0));
    }

    @Test
    public void charAfterTagSkipsWhitespace() throws Exception {
        assertEquals('我', callCharAfterTag("<t>  我", 0));
    }

    @Test
    public void charAfterTagNoCloseTagReturnsNull() throws Exception {
        assertEquals('\0', callCharAfterTag("no tag", 0));
    }

    @Test
    public void charAfterTagEmptyAfterTagReturnsNull() throws Exception {
        assertEquals('\0', callCharAfterTag("<t>", 0));
    }

    @Test
    public void charAfterTagTagAtEnd() throws Exception {
        assertEquals('\0', callCharAfterTag("text<t>", 4));
    }

    @Test
    public void isCjkGapTrueBetweenCjkChars() throws Exception {
        assertTrue(callIsCjkGap("我 靠", 1));
    }

    @Test
    public void isCjkGapFalseBetweenAsciiChars() throws Exception {
        assertFalse(callIsCjkGap("a b", 1));
    }

    @Test
    public void isCjkGapFalseBeforeFirstChar() throws Exception {
        assertFalse(callIsCjkGap(" 我", 0));
    }

    @Test
    public void isCjkGapTrueBeforeTagWithCjk() throws Exception {
        assertTrue(callIsCjkGap("我 <t>靠", 2));
    }

    @Test
    public void isCjkGapFalseBeforeTagWithAscii() throws Exception {
        assertFalse(callIsCjkGap("我 <t>world", 2));
    }

    @Test
    public void resolveAbsoluteUrl() throws Exception {
        String result = callResolve("https://example.com", "https://other.com/path");
        assertEquals("https://other.com/path", result);
    }

    @Test
    public void resolveRelativePath() throws Exception {
        String result = callResolve("https://example.com/dir/", "subpath");
        assertEquals("https://example.com/dir/subpath", result);
    }

    @Test
    public void resolveWithQueryString() throws Exception {
        String result = callResolve("https://example.com", "https://other.com/path?q=1");
        assertEquals("https://other.com/path?q=1", result);
    }

    @Test
    public void resolveInvalidReturnsNull() throws Exception {
        assertNull(callResolve("not a url", "/path"));
    }

    @Test
    public void toLyricsParsesSimpleLrc() throws Exception {
        String lrc = "[00:00.00]first line\n[00:10.00]second line";
        Object result = callToLyrics(lrc);
        assertNotNull(result);
    }

    @Test
    public void toLyricsReturnsNullForEmpty() throws Exception {
        assertNull(callToLyrics(""));
    }

    private static boolean callIsCjk(char c) throws Exception {
        Method method = QuickLRCClient.class.getDeclaredMethod("isCjk", char.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, c);
    }

    private static String callDropCjkSeparators(String lrc) throws Exception {
        Method method = QuickLRCClient.class.getDeclaredMethod("dropCjkSeparators", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, lrc);
    }

    private static char callCharAfterTag(String lrc, int tagStart) throws Exception {
        Method method = QuickLRCClient.class.getDeclaredMethod("charAfterTag", String.class, int.class);
        method.setAccessible(true);
        return (char) method.invoke(null, lrc, tagStart);
    }

    private static boolean callIsCjkGap(String lrc, int start) throws Exception {
        Method method = QuickLRCClient.class.getDeclaredMethod("isCjkGap", String.class, int.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, lrc, start);
    }

    private static String callResolve(String base, String location) throws Exception {
        Method method = QuickLRCClient.class.getDeclaredMethod("resolve", String.class, String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, base, location);
    }

    private static Object callToLyrics(String lrc) throws Exception {
        Method method = QuickLRCClient.class.getDeclaredMethod("toLyrics", String.class);
        method.setAccessible(true);
        return method.invoke(null, lrc);
    }
}
