/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class KanaRomanizerTest {

    // ── romanize ──

    @Test
    public void romanizeBasicHiragana() {
        assertEquals("konnichiha", KanaRomanizer.romanize("こんにちは"));
    }

    @Test
    public void romanizeBasicKatakana() {
        assertEquals("konnichiha", KanaRomanizer.romanize("コンニチハ"));
    }

    @Test
    public void romanizeSingleKana() {
        assertEquals("a", KanaRomanizer.romanize("あ"));
        assertEquals("ka", KanaRomanizer.romanize("か"));
        assertEquals("shi", KanaRomanizer.romanize("し"));
    }

    @Test
    public void romanizeWithSokuon() {
        // Small tsu indicates gemination
        assertEquals("kitte", KanaRomanizer.romanize("きって"));
        assertEquals("gakkou", KanaRomanizer.romanize("がっこう"));
    }

    @Test
    public void romanizeWithChoonpu() {
        // Long vowel: おう is two separate kana, not a long vowel mark
        assertEquals("ou", KanaRomanizer.romanize("おう"));
        assertEquals("ei", KanaRomanizer.romanize("えい"));
    }

    @Test
    public void romanizeWithYoon() {
        // Small ya, yu, yo
        assertEquals("kyo", KanaRomanizer.romanize("きょ"));
        assertEquals("sha", KanaRomanizer.romanize("しゃ"));
        assertEquals("cha", KanaRomanizer.romanize("ちゃ"));
        assertEquals("nya", KanaRomanizer.romanize("にゃ"));
    }

    @Test
    public void romanizeEmpty() {
        assertEquals("", KanaRomanizer.romanize(""));
    }

    @Test
    public void romanizeMixedKana() {
        assertEquals("konnichiha", KanaRomanizer.romanize("こんニチハ"));
    }

    // ── romanizeSyllables ──

    @Test
    public void romanizeSyllablesBasic() {
        // Space between syllables
        assertEquals("no ru ma", KanaRomanizer.romanizeSyllables("ノルマ"));
    }

    @Test
    public void romanizeSyllablesEmpty() {
        assertEquals("", KanaRomanizer.romanizeSyllables(""));
    }

    // ── toHiragana ──

    @Test
    public void toHiraganaFromKatakana() {
        assertEquals("ふかく", KanaRomanizer.toHiragana("フカク"));
    }

    @Test
    public void toHiraganaFromHiragana() {
        assertEquals("ふかく", KanaRomanizer.toHiragana("ふかく"));
    }

    @Test
    public void toHiraganaEmpty() {
        assertEquals("", KanaRomanizer.toHiragana(""));
    }

    // ── toKatakana ──

    @Test
    public void toKatakanaFromHiragana() {
        assertEquals("フカク", KanaRomanizer.toKatakana("ふかく"));
    }

    @Test
    public void toKatakanaFromKatakana() {
        assertEquals("フカク", KanaRomanizer.toKatakana("フカク"));
    }

    @Test
    public void toKatakanaEmpty() {
        assertEquals("", KanaRomanizer.toKatakana(""));
    }

    // ── isPackable / pack / unpack ──

    @Test
    public void isPackableKana() {
        // Hiragana (below KANA_BASE 0x30A0) is not packable
        assertFalse(KanaRomanizer.isPackable('あ'));
        // Katakana (above KANA_BASE) is packable
        assertTrue(KanaRomanizer.isPackable('カ'));
    }

    @Test
    public void isPackableNonKana() {
        assertFalse(KanaRomanizer.isPackable('A'));
        assertFalse(KanaRomanizer.isPackable('你'));
    }

    @Test
    public void packUnpackRoundTrip() {
        for (char c = 0x3040; c <= 0x30FF; c++) {
            if (KanaRomanizer.isPackable(c)) {
                byte packed = KanaRomanizer.pack(c);
                char unpacked = KanaRomanizer.unpack(packed);
                assertEquals(c, unpacked);
            }
        }
    }
}
