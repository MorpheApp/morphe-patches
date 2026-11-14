/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4592
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.LyricsTestEnv;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class LyricsSpanBuilderDisplayTest {

    @Before
    public void setUp() {
        LyricsTestEnv.bootstrap();
    }

    @Test
    public void thaiDisplayKeepsSpaces() {
        LyricsLine line = new LyricsLine(0, "ฉัน รัก คุณ");
        assertEquals("ฉัน รัก คุณ", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void thaiDisplayKeepsMultipleSpaces() {
        LyricsLine line = new LyricsLine(0, "ฉัน  รัก   คุณ");
        assertEquals("ฉัน  รัก   คุณ", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void thaiDisplayWithoutSpacesUnchanged() {
        LyricsLine line = new LyricsLine(0, "ฉันรักคุณ");
        assertEquals("ฉันรักคุณ", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void thaiDisplayKeepsLatinSpaces() {
        LyricsLine line = new LyricsLine(0, "ฉัน love รัก");
        assertEquals("ฉัน love รัก", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void thaiDisplayLatinOnlyUnchanged() {
        LyricsLine line = new LyricsLine(0, "Hello world");
        assertEquals("Hello world", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void cjkDisplayUnchanged() {
        LyricsLine line = new LyricsLine(0, "你好世界");
        assertEquals("你好世界", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void cjkDisplayWithLatinUnchanged() {
        LyricsLine line = new LyricsLine(0, "Hello 世界");
        assertEquals("Hello 世界", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void thaiWordsMatchDisplayText() {
        String thaiText = "ฉัน รัก คุณ";
        LyricsLine line = new LyricsLine(0, 1000, thaiText,
                List.of(
                        new Word(0, 333, "ฉัน"),
                        new Word(333, 666, "รัก"),
                        new Word(666, 1000, "คุณ")
                ));
        String display = LyricsTextUtils.displayLineText(line);
        assertEquals(0, display.indexOf("ฉัน"));
        assertEquals(4, display.indexOf("รัก"));
        assertEquals(8, display.indexOf("คุณ"));
    }

    @Test
    public void thaiCharsAreNotSyllablesOfTheirOwn() {
        assertFalse(LyricsWordBoundaries.isCjkCodePoint('ก'));
        assertFalse(LyricsWordBoundaries.isCjkCodePoint('ั'));
        assertFalse(LyricsWordBoundaries.isCjkCodePoint('ำ'));
    }

    @Test
    public void laoDisplayKeepsSpaces() {
        LyricsLine line = new LyricsLine(0, "ສະ ບາ ດີ");
        assertEquals("ສະ ບາ ດີ", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void khmerDisplayKeepsSpaces() {
        LyricsLine line = new LyricsLine(0, "សួ ស្តី");
        assertEquals("សួ ស្តី", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void myanmarDisplayKeepsSpaces() {
        LyricsLine line = new LyricsLine(0, "မင်း သာ လို");
        assertEquals("မင်း သာ လို", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void tibetanDisplayKeepsSpaces() {
        LyricsLine line = new LyricsLine(0, "བཀྲ་ཤིས བདེ་ ལེགས་ ཤོག");
        assertEquals("བཀྲ་ཤིས བདེ་ ལེགས་ ཤོག", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void koreanDisplayKeepsSpaces() {
        LyricsLine line = new LyricsLine(0, "사랑해 친구들");
        assertEquals("사랑해 친구들", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void koreanDisplayUnchanged() {
        LyricsLine line = new LyricsLine(0, "사랑해");
        assertEquals("사랑해", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void japaneseDisplayUnchanged() {
        LyricsLine line = new LyricsLine(0, "愛してる");
        assertEquals("愛してる", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void thaiWithCjkUnchanged() {
        LyricsLine line = new LyricsLine(0, "ฉันรักคุณ 世界");
        assertEquals("ฉันรักคุณ 世界", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void thaiSpacesAndCjkMixed() {
        LyricsLine line = new LyricsLine(0, "ฉัน รัก 世界");
        assertEquals("ฉัน รัก 世界", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void emptyLineReturnsEmpty() {
        LyricsLine line = new LyricsLine(0, "");
        assertEquals("", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void bracketedVocalStripsBrackets() {
        LyricsLine line = new LyricsLine(0, "(oh)");
        assertEquals("oh", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void bracketedThaiStripsBrackets() {
        LyricsLine line = new LyricsLine(0, "(โอ้)");
        assertEquals("โอ้", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void bracketedThaiWithSpacesStripsBrackets() {
        LyricsLine line = new LyricsLine(0, "(โอ้ ฉัน รัก คุณ)");
        assertEquals("โอ้ ฉัน รัก คุณ", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void bgFlagStripsBrackets() {
        LyricsLine line = new LyricsLine(0, "(ah)la la", List.of(), null, false, true);
        assertEquals("ahla la", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void bgFlagWithoutBracketsUnchanged() {
        LyricsLine line = new LyricsLine(0, "la la", List.of(), null, false, true);
        assertEquals("la la", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void tagBracketsNotStripped() {
        // Lenticular brackets hold section labels, never a vocal, so the text is kept as-is.
        LyricsLine line = new LyricsLine(0, "【Verse】Hello");
        assertEquals("【Verse】Hello", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void bracketedVocalWithCjkStripsBrackets() {
        LyricsLine line = new LyricsLine(0, "(oh你好世界)");
        assertEquals("oh你好世界", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void nonBracketedTextUnchanged() {
        // Text with unbalanced brackets is not a bracketed vocal, so it stays as-is.
        LyricsLine line = new LyricsLine(0, "hello)world(");
        assertEquals("hello)world(", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void bracketedVocalWithSpacesInsideStripsBrackets() {
        LyricsLine line = new LyricsLine(0, "(oh yeah)");
        assertEquals("oh yeah", LyricsTextUtils.displayLineText(line));
    }

    @Test
    public void isSecondaryLineWithBrackets() {
        assertTrue(LyricsTextUtils.isSecondaryLine(new LyricsLine(0, "(oh)")));
    }

    @Test
    public void isSecondaryLineWithBgFlag() {
        assertTrue(LyricsTextUtils.isSecondaryLine(
                new LyricsLine(0, "hello", List.of(), null, false, true)));
    }

    @Test
    public void isSecondaryLineNormal() {
        assertFalse(LyricsTextUtils.isSecondaryLine(new LyricsLine(0, "hello")));
    }

    @Test
    public void isSecondaryLineTagBrackets() {
        assertFalse(LyricsTextUtils.isSecondaryLine(new LyricsLine(0, "【Verse】hello")));
    }
}
