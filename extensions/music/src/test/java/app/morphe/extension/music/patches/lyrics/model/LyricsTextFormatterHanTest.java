/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Tests for the Han-mode branch of {@link LyricsTextFormatter#format} and {@link LyricsTextFormatter#isPunctuation}. Under the mockable android.jar the ICU Transliterator returns null, so Han passes through unchanged; these tests pin that fallback and the offset map. */
public class LyricsTextFormatterHanTest {

    // ── format with hanMode ──

    @Test
    public void formatHanNonePassesThroughHanChars() {
        LyricsTextFormatter.Result result =
                LyricsTextFormatter.format("你好世界", false, false, LyricsTextFormatter.HAN_NONE);
        assertEquals("你好世界", result.text());
    }

    @Test
    public void formatHanTraditionalPassesThroughUnchanged() {
        // ICU Transliterator is null under the mockable jar, so toTraditional is a no-op.
        LyricsTextFormatter.Result result =
                LyricsTextFormatter.format("你好世界", false, false, LyricsTextFormatter.HAN_TRADITIONAL);
        assertEquals("你好世界", result.text());
    }

    @Test
    public void formatHanSimplifiedPassesThroughUnchanged() {
        LyricsTextFormatter.Result result =
                LyricsTextFormatter.format("你好世界", false, false, LyricsTextFormatter.HAN_SIMPLIFIED);
        assertEquals("你好世界", result.text());
    }

    @Test
    public void formatHanMixedTextPassesThrough() {
        LyricsTextFormatter.Result result =
                LyricsTextFormatter.format("hello你好world", false, false, LyricsTextFormatter.HAN_TRADITIONAL);
        assertEquals("hello你好world", result.text());
    }

    @Test
    public void formatHanOffsetMapIsCorrect() {
        // No-op transliteration keeps output length equal to input, so offsets are identity.
        LyricsTextFormatter.Result result =
                LyricsTextFormatter.format("你好", false, false, LyricsTextFormatter.HAN_TRADITIONAL);
        assertEquals(2, result.text().length());
        assertEquals(0, result.offset(0));
        assertEquals(1, result.offset(1));
        assertEquals(2, result.offset(2));
    }

    @Test
    public void formatHanNotCapitalized() {
        // Han characters are excluded from the capitalize step even in Han mode.
        LyricsTextFormatter.Result result =
                LyricsTextFormatter.format("你好", true, false, LyricsTextFormatter.HAN_TRADITIONAL);
        assertEquals("你好", result.text());
    }

    @Test
    public void formatHanWithStripAndCapitalize() {
        LyricsTextFormatter.Result result =
                LyricsTextFormatter.format("你好。", true, true, LyricsTextFormatter.HAN_SIMPLIFIED);
        assertEquals("你好", result.text());
    }

    @Test
    public void formatHanNullInputReturnsEmpty() {
        LyricsTextFormatter.Result result =
                LyricsTextFormatter.format(null, false, false, LyricsTextFormatter.HAN_TRADITIONAL);
        assertEquals("", result.text());
    }

    // ── isPunctuation ──

    @Test
    public void isPunctuationFalseForLetter() {
        assertFalse(LyricsTextFormatter.isPunctuation('A'));
        assertFalse(LyricsTextFormatter.isPunctuation('z'));
    }

    @Test
    public void isPunctuationTrueForAsciiPunctuation() {
        assertTrue(LyricsTextFormatter.isPunctuation('.'));
        assertTrue(LyricsTextFormatter.isPunctuation(','));
        assertTrue(LyricsTextFormatter.isPunctuation(';'));
        assertTrue(LyricsTextFormatter.isPunctuation(':'));
        assertTrue(LyricsTextFormatter.isPunctuation('('));
        assertTrue(LyricsTextFormatter.isPunctuation(')'));
        assertTrue(LyricsTextFormatter.isPunctuation('-'));
        assertTrue(LyricsTextFormatter.isPunctuation('—'));
    }

    @Test
    public void isPunctuationTrueForUnicodePunctuationCategories() {
        assertTrue(LyricsTextFormatter.isPunctuation('“'));
        assertTrue(LyricsTextFormatter.isPunctuation('”'));
        assertTrue(LyricsTextFormatter.isPunctuation('—'));
        assertTrue(LyricsTextFormatter.isPunctuation('_'));
        assertTrue(LyricsTextFormatter.isPunctuation('…'));
    }

    @Test
    public void isPunctuationFalseForDigit() {
        assertFalse(LyricsTextFormatter.isPunctuation('0'));
        assertFalse(LyricsTextFormatter.isPunctuation('9'));
    }

    @Test
    public void isPunctuationFalseForHanChar() {
        assertFalse(LyricsTextFormatter.isPunctuation('中'));
    }
}
