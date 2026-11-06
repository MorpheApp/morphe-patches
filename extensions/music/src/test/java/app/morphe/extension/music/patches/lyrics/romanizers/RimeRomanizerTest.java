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

public class RimeRomanizerTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void romanizeHanPassesThrough() {
        // No dictionary configured → Han characters pass through unchanged
        assertEquals("你好", RimeRomanizer.romanize("你好"));
    }

    @Test
    public void romanizeEmptyString() {
        assertEquals("", RimeRomanizer.romanize(""));
    }

    @Test
    public void romanizeLatinPassesThrough() {
        assertEquals("hello", RimeRomanizer.romanize("hello"));
    }

    @Test
    public void romanizeMixedHanAndLatin() {
        assertEquals("你好 hello", RimeRomanizer.romanize("你好 hello"));
    }

    @Test
    public void romanizeHanSentence() {
        assertEquals("你好世界", RimeRomanizer.romanize("你好世界"));
    }

    @Test
    public void romanizePunctuation() {
        assertEquals("你好，世界！", RimeRomanizer.romanize("你好，世界！"));
    }

    @Test
    public void romanizeLatinWithPunctuation() {
        assertEquals("Hello, world!", RimeRomanizer.romanize("Hello, world!"));
    }
}
