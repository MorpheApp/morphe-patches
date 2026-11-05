/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;

public class BopomofoRomanizerTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void romanizeHanPassesThrough() {
        // No dictionary configured → Han characters pass through unchanged
        assertEquals("你好", BopomofoRomanizer.romanize("你好"));
    }

    @Test
    public void romanizeEmptyString() {
        assertEquals("", BopomofoRomanizer.romanize(""));
    }

    @Test
    public void romanizeLatinPassesThrough() {
        assertEquals("hello", BopomofoRomanizer.romanize("hello"));
    }

    @Test
    public void romanizeBopomofoPassesThrough() {
        // Bopomofo characters pass through unchanged without a dictionary
        assertEquals("ㄅㄆㄇㄈ", BopomofoRomanizer.romanize("ㄅㄆㄇㄈ"));
    }

    @Test
    public void romanizeMixedHanAndBopomofo() {
        assertEquals("你好ㄅㄆ", BopomofoRomanizer.romanize("你好ㄅㄆ"));
    }

    @Test
    public void romanizeMixedHanAndLatin() {
        assertEquals("你好 hello", BopomofoRomanizer.romanize("你好 hello"));
    }

    @Test
    public void romanizePunctuation() {
        assertEquals("你好，世界！", BopomofoRomanizer.romanize("你好，世界！"));
    }

    @Test
    public void romanizeLongLine() {
        String line = "这是一段较长的中文歌词文本用于测试";
        assertEquals(line, BopomofoRomanizer.romanize(line));
    }
}
