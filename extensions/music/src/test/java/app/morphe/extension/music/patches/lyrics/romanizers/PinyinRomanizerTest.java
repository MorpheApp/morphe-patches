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

public class PinyinRomanizerTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    // ── known WORDS fallback entries ──

    @Test
    public void romanizeKnownWord一个人() {
        assertEquals("yí gè rén", PinyinRomanizer.romanize("一个人"));
    }

    @Test
    public void romanizeKnownWord重新开始() {
        assertEquals("chóng xīn kāi shǐ", PinyinRomanizer.romanize("重新开始"));
    }

    // ── other Han passes through ──

    @Test
    public void romanizeOtherHanPassesThrough() {
        // Not in WORDS and no character table loaded: the Han is left alone.
        assertEquals("你好", PinyinRomanizer.romanize("你好"));
    }

    @Test
    public void romanizeSingleHanCharPassesThrough() {
        // "人" appears inside 一个人 but is not a word key on its own.
        assertEquals("人", PinyinRomanizer.romanize("人"));
    }

    // ── empty and Latin ──

    @Test
    public void romanizeEmpty() {
        assertEquals("", PinyinRomanizer.romanize(""));
    }

    @Test
    public void romanizeLatinPassesThrough() {
        assertEquals("Hello", PinyinRomanizer.romanize("Hello"));
    }

    @Test
    public void romanizePunctuationOnly() {
        assertEquals("!!!", PinyinRomanizer.romanize("!!!"));
    }

    // ── mixed scripts ──

    @Test
    public void romanizeMixedWordAndLatin() {
        assertEquals("yí gè rén Hello", PinyinRomanizer.romanize("一个人 Hello"));
    }

    @Test
    public void romanizeWordFollowedByHan() {
        assertEquals("yí gè rén 来了", PinyinRomanizer.romanize("一个人来了"));
    }

    @Test
    public void romanizeWordFollowedByKana() {
        assertEquals("chóng xīn kāi shǐ ohayou",
                PinyinRomanizer.romanize("重新开始おはよう"));
    }

    @Test
    public void romanizeWordSubstringOfLongerLine() {
        // "重新开始" is a prefix of a longer Han run; the rest passes through.
        assertEquals("chóng xīn kāi shǐ 了", PinyinRomanizer.romanize("重新开始了"));
    }

    @Test
    public void romanizeKanaInWordLine() {
        assertEquals("yí gè rén konnichiha", PinyinRomanizer.romanize("一个人こんにちは"));
    }
}
