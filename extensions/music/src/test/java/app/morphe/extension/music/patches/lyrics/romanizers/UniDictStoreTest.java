/*
 * Copyright 2026 Morphe.
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class UniDictStoreTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void codePointParsesValidHex() throws Exception {
        assertEquals('中', callCodePoint("U+4E2D"));
    }

    @Test
    public void codePointParsesAscii() throws Exception {
        assertEquals('A', callCodePoint("U+0041"));
    }

    @Test
    public void codePointReturnsZeroForNonUPrefix() throws Exception {
        assertEquals(0, (int) callCodePoint("4E2D"));
    }

    @Test
    public void codePointReturnsZeroForTooShort() throws Exception {
        // "U+" is length 2, which is < 3, so codePoint returns 0
        assertEquals(0, (int) callCodePoint("U+"));
    }

    @Test
    public void codePointReturnsZeroForTooLarge() throws Exception {
        assertEquals(0, (int) callCodePoint("U+110000"));
    }

    @Test
    public void codePointReturnsZeroForNonHex() throws Exception {
        assertEquals(0, (int) callCodePoint("U+ZZZZ"));
    }

    @Test
    public void codePointReturnsZeroForEmpty() throws Exception {
        assertEquals(0, (int) callCodePoint(""));
    }

    @Test
    public void codePointReturnsZeroForValueZero() throws Exception {
        assertEquals(0, (int) callCodePoint("U+0000"));
    }

    @Test
    public void firstReturnsReadingWithoutCode() throws Exception {
        // No parentheses means the code isn't stripped; "wo23213" is returned as-is, lowercased.
        assertEquals("wo23213", callFirst("wo23213"));
    }

    @Test
    public void firstReturnsReadingBeforeColon() throws Exception {
        // The source takes the text AFTER the colon, not before.
        assertEquals("location", callFirst("wo:location"));
    }

    @Test
    public void firstReturnsReadingAfterColon() throws Exception {
        assertEquals("location", callFirst(":location"));
    }

    @Test
    public void firstReturnsReadingBeforeParenthesis() throws Exception {
        assertEquals("reading", callFirst("reading(code)"));
    }

    @Test
    public void firstReturnsLowercase() throws Exception {
        assertEquals("wo23213", callFirst("WO23213"));
    }

    @Test
    public void firstReturnsFirstOfCommaSeparated() throws Exception {
        assertEquals("wo", callFirst("wo,zhong"));
    }

    @Test
    public void firstReturnsNullForEmpty() throws Exception {
        assertNull(callFirst(""));
    }

    @Test
    public void firstReturnsNullForOnlyCode() throws Exception {
        assertNull(callFirst("(23213)"));
    }

    @Test
    public void firstReturnsReadingWithoutSpace() throws Exception {
        assertEquals("wo", callFirst("wo something"));
    }

    @Test
    public void rowReturnsZeroForMandarinFields() throws Exception {
        assertEquals(0, callRow("kMandarin"));
    }

    @Test
    public void rowReturnsZeroForHanyuPinlu() throws Exception {
        assertEquals(0, callRow("kHanyuPinlu"));
    }

    @Test
    public void rowReturnsOneForCantonese() throws Exception {
        assertEquals(1, callRow("kCantonese"));
    }

    @Test
    public void rowReturnsMinusOneForUnknownField() throws Exception {
        assertEquals(-1, callRow("kUnknownField"));
    }

    @Test
    public void rankReturnsZeroForFirstSource() throws Exception {
        Object table = makeReadingTable("zh", new String[]{"kMandarin", "kHanyuPinlu"});
        assertEquals(0, callRank(table, "kMandarin"));
    }

    @Test
    public void rankReturnsOneForSecondSource() throws Exception {
        Object table = makeReadingTable("zh", new String[]{"kMandarin", "kHanyuPinlu"});
        assertEquals(1, callRank(table, "kHanyuPinlu"));
    }

    @Test
    public void rankReturnsMinusOneForUnknownField() throws Exception {
        Object table = makeReadingTable("zh", new String[]{"kMandarin", "kHanyuPinlu"});
        assertEquals(-1, callRank(table, "kCantonese"));
    }

    private static Object makeReadingTable(String code, String[] sources) throws Exception {
        Class<?> clazz = Class.forName(
                "app.morphe.extension.music.patches.lyrics.romanizers.UniDictStore$ReadingTable");
        java.lang.reflect.Constructor<?> ctor = clazz.getDeclaredConstructor(
                String.class, String[].class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(code, sources, "test_table");
    }

    private static int callRank(Object table, String field) throws Exception {
        Method method = UniDictStore.class.getDeclaredMethod("rank", table.getClass(), String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, table, field);
    }

    private static char callCodePoint(String field) throws Exception {
        Method method = UniDictStore.class.getDeclaredMethod("codePoint", String.class);
        method.setAccessible(true);
        return (char) method.invoke(null, field);
    }

    private static String callFirst(String value) throws Exception {
        Method method = UniDictStore.class.getDeclaredMethod("first", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, value);
    }

    private static int callRow(String field) throws Exception {
        Method method = UniDictStore.class.getDeclaredMethod("row", String.class);
        method.setAccessible(true);
        return (int) method.invoke(null, field);
    }

}
