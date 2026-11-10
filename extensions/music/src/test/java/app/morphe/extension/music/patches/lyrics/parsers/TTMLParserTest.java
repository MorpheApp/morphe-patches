/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class TTMLParserTest {

    // ── parseTime ──

    @Test
    public void parseTimeMmSsMmmFormat() {
        assertEquals(1000L, TTMLParser.parseTime("00:01.000"));
        assertEquals(62030L, TTMLParser.parseTime("01:02.030"));
        assertEquals(0L, TTMLParser.parseTime("00:00.000"));
    }

    @Test
    public void parseTimeMmSsFormat() {
        assertEquals(1000L, TTMLParser.parseTime("00:01"));
        assertEquals(62000L, TTMLParser.parseTime("01:02"));
    }

    @Test
    public void parseTimeSecondsFormat() {
        assertEquals(1000L, TTMLParser.parseTime("1s"));
        assertEquals(60000L, TTMLParser.parseTime("60s"));
    }

    @Test
    public void parseTimeMillisFormat() {
        assertEquals(1000L, TTMLParser.parseTime("1000ms"));
        assertEquals(500L, TTMLParser.parseTime("500ms"));
    }

    @Test
    public void parseTimeMinutesFormat() {
        assertEquals(60000L, TTMLParser.parseTime("1m"));
    }

    @Test
    public void parseTimeHoursFormat() {
        assertEquals(3600000L, TTMLParser.parseTime("1h"));
    }

    @Test
    public void parseTimeInvalidReturnsZero() {
        assertEquals(0L, TTMLParser.parseTime(null));
        assertEquals(0L, TTMLParser.parseTime(""));
        assertEquals(0L, TTMLParser.parseTime("invalid"));
    }

    // ── isContinuousCjk ──

    @Test
    public void isContinuousCjkWithHan() {
        assertTrue(TTMLParser.isContinuousCjk("你好世界"));
    }

    @Test
    public void isContinuousCjkWithKana() {
        assertTrue(TTMLParser.isContinuousCjk("こんにちは"));
    }

    @Test
    public void isContinuousCjkWithHangul() {
        // Hangul is NOT included in isContinuousCjk - only Han and Kana
        assertFalse(TTMLParser.isContinuousCjk("안녕하세요"));
    }

    @Test
    public void isContinuousCjkWithLatin() {
        assertFalse(TTMLParser.isContinuousCjk("Hello world"));
    }

    @Test
    public void isContinuousCjkWithMixed() {
        // Only the last code point matters: "Hello 你好" ends with Han, "你好 Hello" with Latin.
        assertTrue(TTMLParser.isContinuousCjk("Hello 你好"));
        assertFalse(TTMLParser.isContinuousCjk("你好 Hello"));
    }

    @Test
    public void isContinuousCjkNull() {
        assertFalse(TTMLParser.isContinuousCjk(null));
    }

    @Test
    public void isContinuousCjkEmpty() {
        assertFalse(TTMLParser.isContinuousCjk(""));
    }

    @Test
    public void isContinuousCjkPunctuationOnly() {
        assertFalse(TTMLParser.isContinuousCjk("!?."));
    }

    @Test
    public void isContinuousCjkSingleCjkChar() {
        assertTrue(TTMLParser.isContinuousCjk("字"));
    }
}
