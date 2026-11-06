/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.romanizers;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class HangulRomanizerTest {

    @Test
    public void romanizeBasic() {
        assertEquals("salanghae", HangulRomanizer.romanize("사랑해"));
    }

    @Test
    public void romanizeSingleSyllable() {
        assertEquals("ga", HangulRomanizer.romanize("가"));
        assertEquals("na", HangulRomanizer.romanize("나"));
        assertEquals("da", HangulRomanizer.romanize("다"));
    }

    @Test
    public void romanizeWithCoda() {
        // Coda before silent ㅇ
        assertEquals("sigan-i", HangulRomanizer.romanize("시간이"));
    }

    @Test
    public void romanizeWithSilentIeung() {
        // Silent ㅇ at syllable start
        assertEquals("gapi", HangulRomanizer.romanize("가피"));
    }

    @Test
    public void romanizeWithLl() {
        // ㄹ always reads as l
        assertEquals("al", HangulRomanizer.romanize("알"));
    }

    @Test
    public void romanizeMixedText() {
        // Non-Hangul passes through, Hangul is romanized
        assertEquals("Hello sa", HangulRomanizer.romanize("Hello 사"));
    }

    @Test
    public void romanizeEmpty() {
        assertEquals("", HangulRomanizer.romanize(""));
    }

    @Test
    public void romanizeCommonPhrase() {
        assertEquals("yeonghwa", HangulRomanizer.romanize("영화"));
    }

    @Test
    public void romanizeWithLineContext() {
        // romanize(text, line) - coda depends on next syllable
        String result = HangulRomanizer.romanize("시간", "시간이");
        // "시간" followed by "이" - the coda ㄱ moves to the ㅇ
        assertEquals("sigan", result);
    }

    @Test
    public void romanizeCommonWords() {
        assertEquals("geu", HangulRomanizer.romanize("그"));
        assertEquals("i", HangulRomanizer.romanize("이"));
        assertEquals("eun", HangulRomanizer.romanize("은"));
        assertEquals("ge", HangulRomanizer.romanize("게"));
    }

    @Test
    public void romanizeWithKonglish() {
        // Konglish is phonetically romanized, not recognized as English
        assertEquals("lomanlaijeu", HangulRomanizer.romanize("로만라이즈"));
    }
}
