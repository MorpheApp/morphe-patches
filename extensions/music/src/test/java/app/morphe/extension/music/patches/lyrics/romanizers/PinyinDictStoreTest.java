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

public class PinyinDictStoreTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void quotedExtractsQuotedString() throws Exception {
        assertEquals("word", callQuoted("\"word\": value", 0));
    }

    @Test
    public void quotedExtractsFromPosition() throws Exception {
        // The opening '"' is at index 5; from=6 skips it and finds only the closing quote.
        assertEquals("reading", callQuoted("key: \"reading\"", 5));
    }

    @Test
    public void quotedReturnsNullWhenNoOpenQuote() throws Exception {
        assertNull(callQuoted("no quote", 0));
    }

    @Test
    public void quotedReturnsNullWhenNoCloseQuote() throws Exception {
        assertNull(callQuoted("\"no close", 0));
    }

    @Test
    public void quotedReturnsNullForNegativeFrom() throws Exception {
        assertNull(callQuoted("\"word\"", -1));
    }

    @Test
    public void quotedExtractsEmptyQuotedString() throws Exception {
        assertEquals("", callQuoted("\"\"", 0));
    }

    @Test
    public void quotedHandlesQuotedWithColons() throws Exception {
        assertEquals("a: b", callQuoted("\"a: b\"", 0));
    }

    @Test
    public void withErhuaReplacesErSuffix() throws Exception {
        assertEquals("ger", callWithErhua("ge er"));
    }

    @Test
    public void withErhuaNoSpaceReturnsUnchanged() throws Exception {
        assertEquals("ge", callWithErhua("ge"));
    }

    @Test
    public void withErhuaNotErSuffixReturnsUnchanged() throws Exception {
        assertEquals("ge er2", callWithErhua("ge er2"));
    }

    @Test
    public void withErhuaTrailingSpaceReturnsUnchanged() throws Exception {
        assertEquals("ge er ", callWithErhua("ge er "));
    }

    @Test
    public void withErhuaMultiSyllableReplacesLast() throws Exception {
        // "mo er er": last space at index 5, substring(6)="er" matches, so "mo er"+"r"="mo err"
        assertEquals("mo err", callWithErhua("mo er er"));
    }

    @Test
    public void withErhuaSingleSyllableReturnsUnchanged() throws Exception {
        assertEquals("er", callWithErhua("er"));
    }

    @Test
    public void withErhuaEmptyReturnsUnchanged() throws Exception {
        assertEquals("", callWithErhua(""));
    }

    private static String callQuoted(String line, int from) throws Exception {
        Method method = PinyinDictStore.class.getDeclaredMethod("quoted", String.class, int.class);
        method.setAccessible(true);
        return (String) method.invoke(null, line, from);
    }

    private static String callWithErhua(String reading) throws Exception {
        Method method = PinyinDictStore.class.getDeclaredMethod("withErhua", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, reading);
    }
}
