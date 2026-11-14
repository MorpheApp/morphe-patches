/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.Lyrics;
import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;
import app.morphe.extension.music.patches.lyrics.parsers.LRCParser;

public class SimpMusicProviderStaticTest {

    @Test
    public void sanitizeKeepsTagWordBoundarySpaces() {
        String out = SimpMusicProvider.sanitizeLrc(
                "[00:10.84] <00:10.84>Hum <00:11.29>tere <00:11.89>bin\n", new long[1]);
        assertEquals("[00:10.84] <00:10.84>Hum <00:11.29>tere <00:11.89>bin", out);
    }

    @Test
    public void sanitizeKeepsCjkTagBoundarySpaces() {
        String out = SimpMusicProvider.sanitizeLrc(
                "[00:00.88] <00:00.88>沈 <00:01.30>む <00:01.78>ように\n", new long[1]);
        assertEquals("[00:00.88] <00:00.88>沈 <00:01.30>む <00:01.78>ように", out);
    }

    @Test
    public void sanitizeDecodesNbsp() {
        String out = SimpMusicProvider.sanitizeLrc(
                "[00:00.00] <00:00.00>We're&nbsp;&nbsp;no\n", new long[1]);
        assertEquals("[00:00.00] <00:00.00>We're\u00A0\u00A0no", out);
    }

    @Test
    public void normalizeRemovesSpacesBetweenHan() {
        LyricsLine line = new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME,
                "沈 む ように", List.of(), null, false, false, null);
        assertEquals("沈むように",
                SimpMusicProvider.normalizeLines(List.of(line)).get(0).text());
    }

    @Test
    public void normalizeRemovesSpacesBetweenKana() {
        LyricsLine line = new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME,
                "あ い う え お", List.of(), null, false, false, null);
        assertEquals("あいうえお",
                SimpMusicProvider.normalizeLines(List.of(line)).get(0).text());
    }

    @Test
    public void normalizeRemovesIdeographicSpacesBetweenHan() {
        LyricsLine line = new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME,
                "陈\u3000任\u3000佑", List.of(), null, false, false, null);
        assertEquals("陈任佑",
                SimpMusicProvider.normalizeLines(List.of(line)).get(0).text());
    }

    @Test
    public void normalizeKeepsHangulSpaces() {
        LyricsLine line = new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME,
                "어쩜 이렇게", List.of(), null, false, false, null);
        assertEquals("어쩜 이렇게",
                SimpMusicProvider.normalizeLines(List.of(line)).get(0).text());
    }

    @Test
    public void normalizeKeepsThaiSpaces() {
        LyricsLine line = new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME,
                "สบายดีไหม เธอกลัวฟ้าร้อง", List.of(), null, false, false, null);
        assertEquals("สบายดีไหม เธอกลัวฟ้าร้อง",
                SimpMusicProvider.normalizeLines(List.of(line)).get(0).text());
    }

    @Test
    public void normalizeKeepsArabicSpaces() {
        LyricsLine line = new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME,
                "عندي سؤال وجاوبني عليه", List.of(), null, false, false, null);
        assertEquals("عندي سؤال وجاوبني عليه",
                SimpMusicProvider.normalizeLines(List.of(line)).get(0).text());
    }

    @Test
    public void normalizeCollapsesEverySpaceKind() {
        LyricsLine line = new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME,
                "  We're    no\u00A0\u3000strangers  ", List.of(), null, false, false, null);
        assertEquals("We're no strangers",
                SimpMusicProvider.normalizeLines(List.of(line)).get(0).text());
    }

    @Test
    public void normalizeKeepsSpaceAroundLatinWithinCjk() {
        LyricsLine line = new LyricsLine(LyricsLine.NO_TIME, LyricsLine.NO_TIME,
                "第 69 回 NHK 紅白歌会", List.of(), null, false, false, null);
        assertEquals("第 69 回 NHK 紅白歌会",
                SimpMusicProvider.normalizeLines(List.of(line)).get(0).text());
    }

    @Test
    public void normalizeKeepsWordsAndTimings() {
        List<Word> words = List.of(
                new Word(1000, 2000, "沈", null, true),
                new Word(2000, 3000, "む", null, false));
        LyricsLine line = new LyricsLine(500, 3000, "沈 む", words, null, false, false, null);
        LyricsLine out = SimpMusicProvider.normalizeLines(List.of(line)).get(0);
        assertEquals("沈む", out.text());
        assertEquals(words, out.words());
        assertEquals(500, out.startTimeMs());
        assertEquals(3000, out.endTimeMs());
    }

    @Test
    public void parseLrcCjkRisyncMergesWordSpaces() {
        Lyrics lyrics = SimpMusicProvider.parseLrc(
                "[00:00.88] <00:00.88>沈 <00:01.30>む <00:01.78>ように <00:02.47>溶けて\n", "video");
        assertEquals("沈むように溶けて", lyrics.lines().get(0).text());
    }

    @Test
    public void parseLrcLatinKeepsSpaceSeparatedWords() {
        Lyrics lyrics = SimpMusicProvider.parseLrc(
                "[00:00.00] <00:00.00>Tum<00:00.51> <00:01.03>Hi<00:01.54> <00:02.06>Ho\n", "video");
        assertEquals("Tum Hi Ho", lyrics.lines().get(0).text());
    }

    @Test
    public void parseLrcCollapsesNbspInBody() {
        Lyrics lyrics = SimpMusicProvider.parseLrc(
                "[00:00.00] <00:00.00>We're&nbsp;&nbsp;no\n", "video");
        assertEquals("We're no", lyrics.lines().get(0).text());
    }

    @Test
    public void plainCjkLinesGetSpacesRemoved() {
        List<LyricsLine> lines =
                SimpMusicProvider.normalizeLines(LRCParser.parsePlain("如何瞬間 凍結時間\n"));
        assertEquals("如何瞬間凍結時間", lines.get(0).text());
    }

    // ── sourceUrl ──

    @Test
    public void sourceUrlBuildsUrl() {
        assertEquals("https://lyrics.simpmusic.org/#/video/abc123",
                SimpMusicProvider.sourceUrl("abc123"));
    }

    @Test
    public void sourceUrlEmptyId() {
        assertEquals("https://lyrics.simpmusic.org/#/video/",
                SimpMusicProvider.sourceUrl(""));
    }

    // ── decodeHtmlEntities ──

    @Test
    public void decodeHtmlEntitiesDecimal() {
        assertEquals("A", SimpMusicProvider.decodeHtmlEntities("&#65;"));
    }

    @Test
    public void decodeHtmlEntitiesHex() {
        assertEquals("B", SimpMusicProvider.decodeHtmlEntities("&#x42;"));
    }

    @Test
    public void decodeHtmlEntitiesHexLowercase() {
        assertEquals("B", SimpMusicProvider.decodeHtmlEntities("&#x42;"));
    }

    @Test
    public void decodeHtmlEntitiesHexDecodesLargeValue() {
        // 0x4a = 74 = 'J'
        assertEquals("J", SimpMusicProvider.decodeHtmlEntities("&#x4a;"));
    }

    @Test
    public void decodeHtmlEntitiesMultiple() {
        assertEquals("AB", SimpMusicProvider.decodeHtmlEntities("&#65;&#66;"));
    }

    @Test
    public void decodeHtmlEntitiesNbsp() {
        assertEquals("\u00A0", SimpMusicProvider.decodeHtmlEntities("&nbsp;"));
    }

    @Test
    public void decodeHtmlEntitiesAmp() {
        assertEquals("&", SimpMusicProvider.decodeHtmlEntities("&amp;"));
    }

    @Test
    public void decodeHtmlEntitiesLt() {
        assertEquals("<", SimpMusicProvider.decodeHtmlEntities("&lt;"));
    }

    @Test
    public void decodeHtmlEntitiesGt() {
        assertEquals(">", SimpMusicProvider.decodeHtmlEntities("&gt;"));
    }

    @Test
    public void decodeHtmlEntitiesQuot() {
        assertEquals("\"", SimpMusicProvider.decodeHtmlEntities("&quot;"));
    }

    @Test
    public void decodeHtmlEntitiesApos() {
        assertEquals("'", SimpMusicProvider.decodeHtmlEntities("&apos;"));
    }

    @Test
    public void decodeHtmlEntitiesInvalidEntityKeepsRaw() {
        // Invalid hex: not a valid number
        String result = SimpMusicProvider.decodeHtmlEntities("&#xZZZ;");
        assertEquals("&#xZZZ;", result);
    }

    @Test
    public void decodeHtmlEntitiesInvalidDecimalKeepsRaw() {
        String result = SimpMusicProvider.decodeHtmlEntities("&#abc;");
        assertEquals("&#abc;", result);
    }

    @Test
    public void decodeHtmlEntitiesNoEntities() {
        assertEquals("Hello World", SimpMusicProvider.decodeHtmlEntities("Hello World"));
    }

    @Test
    public void decodeHtmlEntitiesEmpty() {
        assertEquals("", SimpMusicProvider.decodeHtmlEntities(""));
    }

    @Test
    public void decodeHtmlEntitiesSurroundedByText() {
        assertEquals("Tom & Jerry",
                SimpMusicProvider.decodeHtmlEntities("Tom &amp; Jerry"));
    }

    @Test
    public void decodeHtmlEntitiesNumericAndNamed() {
        assertEquals("A&B",
                SimpMusicProvider.decodeHtmlEntities("&#65;&amp;&#66;"));
    }

    @Test
    public void decodeHtmlEntitiesOutOfRangeChar() {
        // 9999999 parses as a valid int and casts to a char without throwing
        String result = SimpMusicProvider.decodeHtmlEntities("&#9999999;");
        assertEquals("陿", result);
    }

    @Test
    public void decodeHtmlEntitiesHexOutOfRange() {
        // "ZZZZZZ" is not valid hex, so NumberFormatException is caught
        String result = SimpMusicProvider.decodeHtmlEntities("&#xZZZZZZ;");
        assertEquals("&#xZZZZZZ;", result);
    }

    @Test
    public void decodeHtmlEntitiesNonNumericDecimal() {
        // "abc" is not a valid decimal number
        String result = SimpMusicProvider.decodeHtmlEntities("&#abc;");
        assertEquals("&#abc;", result);
    }

    @Test
    public void decodeHtmlEntitiesUppercaseHexPrefixNotMatched() {
        // The regex only matches lowercase 'x', so uppercase 'X' is not decoded
        String result = SimpMusicProvider.decodeHtmlEntities("&#X42;");
        assertEquals("&#X42;", result);
    }

    @Test
    public void decodeHtmlEntitiesHexDecodesCorrectly() {
        // 0x42 = 66 = 'B'
        assertEquals("B", SimpMusicProvider.decodeHtmlEntities("&#x42;"));
    }

    // ── removeCjkSpaces ──

    @Test
    public void removeCjkSpacesBetweenHan() {
        assertEquals("你好世界", SimpMusicProvider.removeCjkSpaces("你 好 世 界"));
    }

    @Test
    public void removeCjkSpacesBetweenKana() {
        assertEquals("あいうえお", SimpMusicProvider.removeCjkSpaces("あ い う え お"));
    }

    @Test
    public void removeCjkSpacesMixedHanKana() {
        assertEquals("汉字かきくけこ", SimpMusicProvider.removeCjkSpaces("汉 字 か き く け こ"));
    }

    @Test
    public void removeCjkSpacesKeepsIdeographicSpaces() {
        // removeCjkSpaces only handles regular spaces (0x20), not U+3000
        assertEquals("陈　任　佑", SimpMusicProvider.removeCjkSpaces("陈\u3000任\u3000佑"));
    }

    @Test
    public void removeCjkSpacesKeepsLatinSpaces() {
        assertEquals("hello world", SimpMusicProvider.removeCjkSpaces("hello world"));
    }

    @Test
    public void removeCjkSpacesKeepsHangulSpaces() {
        assertEquals("어쩜 이렇게", SimpMusicProvider.removeCjkSpaces("어쩜 이렇게"));
    }

    @Test
    public void removeCjkSpacesKeepsThaiSpaces() {
        assertEquals("สบายดีไหม เธอกลัวฟ้าร้อง",
                SimpMusicProvider.removeCjkSpaces("สบายดีไหม เธอกลัวฟ้าร้อง"));
    }

    @Test
    public void removeCjkSpacesKeepsArabicSpaces() {
        assertEquals("عندي سؤال وجاوبني عليه",
                SimpMusicProvider.removeCjkSpaces("عندي سؤال وجاوبني عليه"));
    }

    @Test
    public void removeCjkSpacesKeepsSpaceBeforeLatin() {
        assertEquals("第 69 回", SimpMusicProvider.removeCjkSpaces("第 69 回"));
    }

    @Test
    public void removeCjkSpacesKeepsSpaceAfterLatin() {
        assertEquals("回 NHK 紅白", SimpMusicProvider.removeCjkSpaces("回 NHK 紅白"));
    }

    @Test
    public void removeCjkSpacesNoSpaces() {
        assertEquals("你好世界", SimpMusicProvider.removeCjkSpaces("你好世界"));
    }

    @Test
    public void removeCjkSpacesEmpty() {
        assertEquals("", SimpMusicProvider.removeCjkSpaces(""));
    }

    @Test
    public void removeCjkSpacesLeadingSpaceBeforeHan() {
        // Space at start has no left Han/Kana, so it's kept
        assertEquals(" 你好", SimpMusicProvider.removeCjkSpaces(" 你好"));
    }

    @Test
    public void removeCjkSpacesTrailingSpaceAfterHan() {
        // Space at end has no right Han/Kana, so it's kept
        assertEquals("你好 ", SimpMusicProvider.removeCjkSpaces("你好 "));
    }

    @Test
    public void removeCjkSpacesKeepsDigitBoundary() {
        // Digits are not Han/Kana, so spaces around them are preserved
        assertEquals("69 回", SimpMusicProvider.removeCjkSpaces("69 回"));
    }

    // ── withContributor ──

    @Test
    public void withContributorAddsCredit() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello", List.of()));
        Lyrics lyrics = new Lyrics(lines, "SimpMusic", true,
                null, null, null, null, null, "lrc", "source");
        Lyrics result = SimpMusicProvider.withContributor(lyrics, "JohnDoe");

        assertNotNull(result);
        assertEquals(List.of("Submitted by JohnDoe"), result.credits());
        assertEquals(lines, result.lines());
        assertEquals("SimpMusic", result.providerName());
    }

    @Test
    public void withContributorNullLyricsReturnsNull() {
        assertNull(SimpMusicProvider.withContributor(null, "JohnDoe"));
    }

    @Test
    public void withContributorNullContributorReturnsSame() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello", List.of()));
        Lyrics lyrics = new Lyrics(lines, "SimpMusic", true,
                null, null, null, null, null, "lrc", "source");
        Lyrics result = SimpMusicProvider.withContributor(lyrics, null);

        assertNotNull(result);
        assertNull(result.credits());
        assertEquals(lines, result.lines());
    }

    @Test
    public void withContributorPreservesExistingCredits() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello", List.of()));
        List<String> existing = List.of("Existing credit");
        Lyrics lyrics = new Lyrics(lines, "SimpMusic", true,
                null, null, null, null, existing, "lrc", "lrc", "source", null, null);
        Lyrics result = SimpMusicProvider.withContributor(lyrics, "JohnDoe");

        assertNotNull(result);
        assertEquals(List.of("Existing credit", "Submitted by JohnDoe"), result.credits());
    }

    @Test
    public void withContributorDeduplicatesSameCredit() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello", List.of()));
        List<String> existing = List.of("Submitted by JohnDoe");
        Lyrics lyrics = new Lyrics(lines, "SimpMusic", true,
                null, null, null, null, existing, "lrc", "lrc", "source", null, null);
        Lyrics result = SimpMusicProvider.withContributor(lyrics, "JohnDoe");

        assertNotNull(result);
        // Same entry, so no duplicate added
        assertEquals(List.of("Submitted by JohnDoe"), result.credits());
    }

    @Test
    public void withContributorReturnsNewLyricsInstance() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "Hello", List.of()));
        Lyrics lyrics = new Lyrics(lines, "SimpMusic", true,
                null, null, null, null, null, "lrc", "source");
        Lyrics result = SimpMusicProvider.withContributor(lyrics, "JohnDoe");

        assertNotSame(lyrics, result);
    }

    // ── applyOffset safety / side-effect tests ──

    @Test
    public void applyOffsetDoesNotMutateInput() {
        List<LyricsLine> original = new ArrayList<>();
        original.add(new LyricsLine(1000, 2000, "Hello", List.of()));
        List<LyricsLine> copy = new ArrayList<>(original);

        LyricsRequests.applyOffset(original, 500L);

        assertEquals(copy, original);
        assertEquals(1000L, original.get(0).startTimeMs());
        assertEquals(2000L, original.get(0).endTimeMs());
    }

    @Test
    public void applyOffsetReturnsDistinctList() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsLine(1000, 2000, "Hello", List.of()));

        List<LyricsLine> result = LyricsRequests.applyOffset(lines, 500L);

        assertNotSame(lines, result);
    }

    @Test
    public void applyOffsetZeroOffsetReturnsDistinctList() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsLine(1000, 2000, "Hello", List.of()));

        List<LyricsLine> result = LyricsRequests.applyOffset(lines, 0L);

        assertNotSame(lines, result);
        assertEquals(lines, result);
    }

    @Test
    public void applyOffsetZeroOffsetDoesNotMutateInput() {
        List<LyricsLine> original = new ArrayList<>();
        original.add(new LyricsLine(1000, 2000, "Hello", List.of()));
        List<LyricsLine> copy = new ArrayList<>(original);

        LyricsRequests.applyOffset(original, 0L);

        assertEquals(copy, original);
    }

    @Test
    public void applyOffsetNonZeroReturnsAdjustedTimes() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsLine(1000, 2000, "Hello", List.of()));

        List<LyricsLine> result = LyricsRequests.applyOffset(lines, 500L);

        assertEquals(1, result.size());
        assertEquals(1500L, result.get(0).startTimeMs());
        assertEquals(2500L, result.get(0).endTimeMs());
    }

    @Test
    public void applyOffsetPreservesLineFields() {
        List<Word> words = List.of(new Word(1000, 2000, "Hello", "Hello", false));
        LyricsLine line = new LyricsLine(1000, 2000, "Hello", words, "agent1", true, false, "Chorus");
        List<LyricsLine> lines = new ArrayList<>(List.of(line));

        List<LyricsLine> result = LyricsRequests.applyOffset(lines, 500L);

        LyricsLine out = result.get(0);
        assertEquals("Hello", out.text());
        assertEquals("agent1", out.agentId());
        assertTrue(out.isDuet());
        assertFalse(out.isBG());
        assertEquals("Chorus", out.songPart());
        // Words also get shifted
        assertEquals(1, out.words().size());
        assertEquals(1500L, out.words().get(0).startMs());
        assertEquals(2500L, out.words().get(0).endMs());
    }

    @Test
    public void applyOffsetNullReturnsNull() {
        assertNull(LyricsRequests.applyOffset(null, 500L));
    }

    @Test
    public void applyOffsetEmptyReturnsEmpty() {
        assertTrue(LyricsRequests.applyOffset(new ArrayList<>(), 500L).isEmpty());
    }

    @Test
    public void applyOffsetNoTimeEndPreserved() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsLine(1000, LyricsLine.NO_TIME, "Hello", List.of()));

        List<LyricsLine> result = LyricsRequests.applyOffset(lines, 500L);

        assertEquals(1500L, result.get(0).startTimeMs());
        assertEquals(LyricsLine.NO_TIME, result.get(0).endTimeMs());
    }
}
