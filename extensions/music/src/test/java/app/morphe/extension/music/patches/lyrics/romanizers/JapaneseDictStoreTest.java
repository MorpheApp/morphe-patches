/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class JapaneseDictStoreTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void splitSimpleCommaSeparated() throws Exception {
        assertArrayEquals(new String[]{"a", "b", "c"}, callSplit("a,b,c"));
    }

    @Test
    public void splitHandlesQuotedComma() throws Exception {
        assertArrayEquals(new String[]{"a", "b,c", "d"}, callSplit("a,\"b,c\",d"));
    }

    @Test
    public void splitHandlesQuotedField() throws Exception {
        assertArrayEquals(new String[]{"a", "b"}, callSplit("a,\"b\""));
    }

    @Test
    public void splitHandlesEmptyField() throws Exception {
        assertArrayEquals(new String[]{"a", "", "c"}, callSplit("a,,c"));
    }

    @Test
    public void splitEmptyStringReturnsEmpty() throws Exception {
        // An empty string produces one empty field via the end-of-string sentinel comma.
        assertEquals(1, callSplit("").length);
        assertEquals("", callSplit("")[0]);
    }

    @Test
    public void splitNullReturnsEmpty() throws Exception {
        assertEquals(0, callSplit(null).length);
    }

    @Test
    public void splitHandlesQuotedEmpty() throws Exception {
        assertArrayEquals(new String[]{"a", ""}, callSplit("a,\"\""));
    }

    @Test
    public void splitUnquotesFields() throws Exception {
        assertArrayEquals(new String[]{"a", "b"}, callSplit("a,\"b\""));
    }

    @Test
    public void splitSingleField() throws Exception {
        assertArrayEquals(new String[]{"hello"}, callSplit("hello"));
    }

    @Test
    public void unquoteRemovesQuotes() throws Exception {
        assertEquals("value", callUnquote("\"value\""));
    }

    @Test
    public void unquoteLeavesUnquoted() throws Exception {
        assertEquals("value", callUnquote("value"));
    }

    @Test
    public void unquoteSingleQuoteNotPair() throws Exception {
        assertEquals("\"", callUnquote("\""));
    }

    @Test
    public void unquoteEmptyString() throws Exception {
        assertEquals("", callUnquote(""));
    }

    @Test
    public void unquoteSingleChar() throws Exception {
        assertEquals("a", callUnquote("a"));
    }

    @Test
    public void unquoteMiddleQuotes() throws Exception {
        assertEquals("a\"b", callUnquote("a\"b"));
    }

    @Test
    public void containsHanReturnsTrueForHanzi() throws Exception {
        assertTrue(callContainsHan("日本語"));
    }

    @Test
    public void containsHanReturnsFalseForAscii() throws Exception {
        assertFalse(callContainsHan("abc"));
    }

    @Test
    public void containsHanReturnsTrueForMixed() throws Exception {
        assertTrue(callContainsHan("日本語abc"));
    }

    @Test
    public void containsHanReturnsFalseForEmpty() throws Exception {
        assertFalse(callContainsHan(""));
    }

    @Test
    public void containsHanReturnsTrueForCJKExtA() throws Exception {
        // CJK Extension A is 0x3400-0x4DBF
        assertTrue(callContainsHan("\u3400"));
    }

    @Test
    public void containsHanReturnsFalseForKana() throws Exception {
        assertFalse(callContainsHan("かんじ"));
    }

    @Test
    public void displayNameStripsZipExtension() throws Exception {
        // "_lex" is removed by replace("_lex", ""), then no "_" remains to become a space.
        assertEquals("small", callDisplayName("small_lex.zip"));
    }

    @Test
    public void displayNameStripsUnderscores() throws Exception {
        // "_lex" is removed first, so "small_lex" becomes "small", not "small lex".
        assertEquals("small", callDisplayName("small_lex"));
    }

    @Test
    public void displayNameStripsLexSuffix() throws Exception {
        assertEquals("core", callDisplayName("core.zip"));
    }

    @Test
    public void displayNameHandlesNotcore() throws Exception {
        assertEquals("notcore", callDisplayName("notcore_lex.zip"));
    }

    private static String[] callSplit(String line) throws Exception {
        Method method = JapaneseDictStore.class.getDeclaredMethod("split", String.class);
        method.setAccessible(true);
        return (String[]) method.invoke(null, line);
    }

    private static String callUnquote(String value) throws Exception {
        Method method = JapaneseDictStore.class.getDeclaredMethod("unquote", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static boolean callContainsHan(String text) throws Exception {
        Method method = JapaneseDictStore.class.getDeclaredMethod("containsHan", String.class);
        method.setAccessible(true);
        return (boolean) method.invoke(null, text);
    }

    private static String callDisplayName(String fileName) throws Exception {
        Method method = JapaneseDictStore.class.getDeclaredMethod("displayName", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, fileName);
    }
}
