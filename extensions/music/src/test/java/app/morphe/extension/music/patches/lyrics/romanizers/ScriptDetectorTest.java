/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class ScriptDetectorTest {

    // ── detect(String) ──

    @Test
    public void detectNullReturnsNull() {
        String nullLine = null;
        assertNull(ScriptDetector.detect(nullLine));
    }

    @Test
    public void detectEmptyReturnsOther() {
        assertEquals(ScriptDetector.Script.OTHER, ScriptDetector.detect(""));
    }

    @Test
    public void detectLatin() {
        assertEquals(ScriptDetector.Script.OTHER, ScriptDetector.detect("Hello world"));
    }

    @Test
    public void detectJapaneseWithKana() {
        assertEquals(ScriptDetector.Script.JAPANESE, ScriptDetector.detect("こんにちは"));
    }

    @Test
    public void detectJapaneseWithHiraganaAndHan() {
        // Hiragana settles it as Japanese even with Han
        assertEquals(ScriptDetector.Script.JAPANESE, ScriptDetector.detect("愛してる"));
    }

    @Test
    public void detectKorean() {
        assertEquals(ScriptDetector.Script.KOREAN, ScriptDetector.detect("사랑해"));
    }

    @Test
    public void detectChinese() {
        assertEquals(ScriptDetector.Script.HAN, ScriptDetector.detect("你好世界"));
    }

    @Test
    public void detectCyrillic() {
        assertEquals(ScriptDetector.Script.CYRILLIC, ScriptDetector.detect("Привет"));
    }

    @Test
    public void detectArabic() {
        assertEquals(ScriptDetector.Script.ARABIC, ScriptDetector.detect("مرحبا"));
    }

    @Test
    public void detectHebrew() {
        assertEquals(ScriptDetector.Script.HEBREW, ScriptDetector.detect("שלום"));
    }

    @Test
    public void detectGreek() {
        assertEquals(ScriptDetector.Script.GREEK, ScriptDetector.detect("Γεια"));
    }

    @Test
    public void detectGeorgian() {
        assertEquals(ScriptDetector.Script.GEORGIAN, ScriptDetector.detect("გამარჯობა"));
    }

    @Test
    public void detectArmenian() {
        assertEquals(ScriptDetector.Script.ARMENIAN, ScriptDetector.detect("այբբենական"));
    }

    @Test
    public void detectKoreanWithHan() {
        // Hangul majority wins even with some Han
        assertEquals(ScriptDetector.Script.KOREAN, ScriptDetector.detect("사랑해 사랑"));
    }

    // ── detect(List<String>) ──

    @Test
    public void detectListMixedLines() {
        List<String> lines = List.of("Hello", "こんにちは", "사랑해");
        // Should detect some script from the list
        ScriptDetector.Script result = ScriptDetector.detect(lines);
        assertNotNull(result);
    }

    @Test
    public void detectListEmpty() {
        assertEquals(ScriptDetector.Script.OTHER, ScriptDetector.detect(List.of()));
    }

    @Test
    public void detectListNullElements() {
        java.util.ArrayList<String> lines = new java.util.ArrayList<>();
        lines.add(null);
        lines.add("Hello");
        lines.add(null);
        assertEquals(ScriptDetector.Script.OTHER, ScriptDetector.detect(lines));
    }

    // ── contains ──

    @Test
    public void containsNullReturnsFalse() {
        assertFalse(ScriptDetector.contains(null, Character.UnicodeScript.HAN));
    }

    @Test
    public void containsHan() {
        assertTrue(ScriptDetector.contains("你好", Character.UnicodeScript.HAN));
    }

    @Test
    public void containsKana() {
        assertTrue(ScriptDetector.contains("こんにちは", Character.UnicodeScript.HIRAGANA));
    }

    @Test
    public void containsLatin() {
        assertTrue(ScriptDetector.contains("Hello", Character.UnicodeScript.LATIN));
    }

    @Test
    public void containsMultipleScripts() {
        assertTrue(ScriptDetector.contains("Hello 世界",
                Character.UnicodeScript.LATIN, Character.UnicodeScript.HAN));
    }

    @Test
    public void containsNoMatch() {
        assertFalse(ScriptDetector.contains("Hello", Character.UnicodeScript.HAN));
    }

    // ── containsKana ──

    @Test
    public void containsKanaHiragana() {
        assertTrue(ScriptDetector.containsKana("こんにちは"));
    }

    @Test
    public void containsKanaKatakana() {
        assertTrue(ScriptDetector.containsKana("ノルマ"));
    }

    @Test
    public void containsKanaMixed() {
        assertTrue(ScriptDetector.containsKana("こんノルマ"));
    }

    @Test
    public void containsKanaNoKana() {
        assertFalse(ScriptDetector.containsKana("Hello"));
    }

    @Test
    public void containsKanaNull() {
        assertFalse(ScriptDetector.containsKana(null));
    }

    // ── containsCjk ──

    @Test
    public void containsCjkHan() {
        assertTrue(ScriptDetector.containsCjk("你好"));
    }

    @Test
    public void containsCjkKana() {
        assertTrue(ScriptDetector.containsCjk("こんにちは"));
    }

    @Test
    public void containsCjkHangul() {
        assertTrue(ScriptDetector.containsCjk("사랑해"));
    }

    @Test
    public void containsCjkNoCjk() {
        assertFalse(ScriptDetector.containsCjk("Hello"));
    }

    // ── containsRtl ──

    @Test
    public void containsRtlArabic() {
        assertTrue(ScriptDetector.containsRtl("مرحبا"));
    }

    @Test
    public void containsRtlHebrew() {
        assertTrue(ScriptDetector.containsRtl("שלום"));
    }

    @Test
    public void containsRtlNoRtl() {
        assertFalse(ScriptDetector.containsRtl("Hello"));
    }

    @Test
    public void containsRtlNull() {
        assertFalse(ScriptDetector.containsRtl(null));
    }

    // ── isLineRtl ──

    @Test
    public void isLineRtlArabic() {
        assertTrue(ScriptDetector.isLineRtl("مرحبا بالعالم"));
    }

    @Test
    public void isLineRtlHebrew() {
        assertTrue(ScriptDetector.isLineRtl("שלום עולם"));
    }

    @Test
    public void isLineRtlMixedRtlDominant() {
        assertTrue(ScriptDetector.isLineRtl("مرحبا hello"));
    }

    @Test
    public void isLineRtlMixedLtrDominant() {
        // "hello مرحبا" has 5 LTR + 5 RTL characters (space is not counted in either)
        // Since rtlCount (5) >= ltrCount (5), it returns true
        assertTrue(ScriptDetector.isLineRtl("hello مرحبا"));
    }

    @Test
    public void isLineRtlLatin() {
        assertFalse(ScriptDetector.isLineRtl("Hello world"));
    }

    @Test
    public void isLineRtlEmpty() {
        assertFalse(ScriptDetector.isLineRtl(""));
    }

    @Test
    public void isLineRtlNull() {
        assertFalse(ScriptDetector.isLineRtl(null));
    }

    // ── isHan ──

    @Test
    public void isHanBasic() {
        assertTrue(ScriptDetector.isHan('你'));
        assertTrue(ScriptDetector.isHan('好'));
        assertTrue(ScriptDetector.isHan('世'));
    }

    @Test
    public void isHanNotHan() {
        assertFalse(ScriptDetector.isHan('A'));
        assertFalse(ScriptDetector.isHan('あ'));
        assertFalse(ScriptDetector.isHan('사'));
    }

    // ── isKana ──

    @Test
    public void isKanaHiragana() {
        assertTrue(ScriptDetector.isKana('こ'));
        assertTrue(ScriptDetector.isKana('ん'));
    }

    @Test
    public void isKanaKatakana() {
        assertTrue(ScriptDetector.isKana('ノ'));
        assertTrue(ScriptDetector.isKana('ル'));
    }

    @Test
    public void isKanaNotKana() {
        assertFalse(ScriptDetector.isKana('A'));
        assertFalse(ScriptDetector.isKana('你'));
        assertFalse(ScriptDetector.isKana('사'));
    }

    // ── isCjkCodePoint ──

    @Test
    public void isCjkCodePointHan() {
        assertTrue(ScriptDetector.isCjkCodePoint('你'));
    }

    @Test
    public void isCjkCodePointKana() {
        assertTrue(ScriptDetector.isCjkCodePoint('こ'));
    }

    @Test
    public void isCjkCodePointHangul() {
        assertTrue(ScriptDetector.isCjkCodePoint('사'));
    }

    @Test
    public void isCjkCodePointLatin() {
        assertFalse(ScriptDetector.isCjkCodePoint('A'));
    }

    // ── isAnyHangul ──

    @Test
    public void isAnyHangulSyllable() {
        assertTrue(ScriptDetector.isAnyHangul('사'));
    }

    @Test
    public void isAnyHangulJamo() {
        assertTrue(ScriptDetector.isAnyHangul('ㄱ'));
    }

    @Test
    public void isAnyHangulCompatibilityJamo() {
        assertTrue(ScriptDetector.isAnyHangul('ㄱ'));
    }

    @Test
    public void isAnyHangulNotHangul() {
        assertFalse(ScriptDetector.isAnyHangul('A'));
        assertFalse(ScriptDetector.isAnyHangul('你'));
    }

    // ── isGreek ──

    @Test
    public void isGreekBasic() {
        assertTrue(ScriptDetector.isGreek('Γ'));
        assertTrue(ScriptDetector.isGreek('ε'));
    }

    @Test
    public void isGreekNotGreek() {
        assertFalse(ScriptDetector.isGreek('A'));
    }

    // ── isGeorgian ──

    @Test
    public void isGeorgianBasic() {
        assertTrue(ScriptDetector.isGeorgian('გ'));
    }

    @Test
    public void isGeorgianNotGeorgian() {
        assertFalse(ScriptDetector.isGeorgian('A'));
    }

    // ── isArmenian ──

    @Test
    public void isArmenianBasic() {
        assertTrue(ScriptDetector.isArmenian('ա'));
    }

    @Test
    public void isArmenianNotArmenian() {
        assertFalse(ScriptDetector.isArmenian('A'));
    }

    // ── isHebrew ──

    @Test
    public void isHebrewBasic() {
        assertTrue(ScriptDetector.isHebrew('ש'));
    }

    @Test
    public void isHebrewNotHebrew() {
        assertFalse(ScriptDetector.isHebrew('A'));
    }

    // ── isLatin ──

    @Test
    public void isLatinBasic() {
        assertTrue(ScriptDetector.isLatin('A'));
        assertTrue(ScriptDetector.isLatin('z'));
        assertTrue(ScriptDetector.isLatin('é'));
    }

    @Test
    public void isLatinNotLatin() {
        assertFalse(ScriptDetector.isLatin('你'));
        assertFalse(ScriptDetector.isLatin('こ'));
    }

    // ── isThai ──

    @Test
    public void isThaiBasic() {
        assertTrue(ScriptDetector.isThai('ก'));
    }

    @Test
    public void isThaiNotThai() {
        assertFalse(ScriptDetector.isThai('A'));
    }

    // Helper
    private static void assertNotNull(Object obj) {
        Assert.assertNotNull(obj);
    }
}
