/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches/pull/4439
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.parsers;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

public class LRCParserTest {

    // ── parseSynced ──

    @Test
    public void parseSyncedNullOrEmptyReturnsEmpty() {
        assertTrue(LRCParser.parseSynced(null).isEmpty());
        assertTrue(LRCParser.parseSynced("").isEmpty());
    }

    @Test
    public void parseSyncedBasicTimestamp() {
        List<LyricsLine> lines = LRCParser.parseSynced("[00:01.00]Hello world\n[00:02.00]This is a test");
        assertEquals(2, lines.size());
        assertEquals(1000L, lines.get(0).startTimeMs());
        assertEquals("Hello world", lines.get(0).text());
        assertEquals(2000L, lines.get(1).startTimeMs());
        assertEquals("This is a test", lines.get(1).text());
    }

    @Test
    public void parseSyncedWithMillis() {
        List<LyricsLine> lines = LRCParser.parseSynced("[00:01.500]Hello");
        assertEquals(1, lines.size());
        assertEquals(1500L, lines.get(0).startTimeMs());
    }

    @Test
    public void parseSyncedWithSecondsOnly() {
        List<LyricsLine> lines = LRCParser.parseSynced("[00:01]Hello");
        assertEquals(1, lines.size());
        assertEquals(1000L, lines.get(0).startTimeMs());
    }

    @Test
    public void parseSyncedWithWordTiming() {
        List<LyricsLine> lines = LRCParser.parseSynced("[00:01.00]Hello<00:01.25> world");
        assertEquals(1, lines.size());
        LyricsLine line = lines.get(0);
        assertEquals("Hello world", line.text());
        assertTrue(line.hasWordTimings());
        List<Word> words = line.words();
        assertNotNull(words);
        assertEquals(2, words.size());
        assertEquals("Hello", words.get(0).text());
        assertEquals(1000L, words.get(0).startMs());
        assertEquals("world", words.get(1).text());
    }

    @Test
    public void parseSyncedWithMultipleTimestamps() {
        List<LyricsLine> lines = LRCParser.parseSynced("[00:01.00][00:02.00]Hello");
        assertEquals(2, lines.size());
        assertEquals(1000L, lines.get(0).startTimeMs());
        assertEquals(2000L, lines.get(1).startTimeMs());
        assertEquals("Hello", lines.get(0).text());
        assertEquals("Hello", lines.get(1).text());
    }

    @Test
    public void parseSyncedWithMetadataTags() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[ti:Test Song]\n[ar:Test Artist]\n[00:01.00]Hello");
        assertEquals(1, lines.size());
        assertEquals("Hello", lines.get(0).text());
    }

    @Test
    public void parseSyncedWithOffsetTag() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[offset:-5000]\n[00:01.00]Hello");
        assertEquals(1, lines.size());
        // offset -5000 is negated to +5000: lyrics shown 5000ms later
        assertEquals(6000L, lines.get(0).startTimeMs());
    }

    @Test
    public void parseSyncedWithOffsetPlusTag() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[offset:+1000]\n[00:01.00]Hello");
        assertEquals(1, lines.size());
        // offset +1000 means lyrics shown later: 1000 + (-1000) = 0
        assertEquals(0L, lines.get(0).startTimeMs());
    }

    @Test
    public void parseSyncedWithBgTag() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[00:01.00]Hello\n[bg:1][00:01.00]world");
        assertEquals(2, lines.size());
        assertFalse(lines.get(0).isBG());
        assertTrue(lines.get(1).isBG());
    }

    @Test
    public void parseSyncedWithParenthesesBg() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[00:01.00]Hello\n[00:01.00](world)");
        assertEquals(2, lines.size());
        assertFalse(lines.get(0).isBG());
        assertTrue(lines.get(1).isBG());
    }

    @Test
    public void parseSyncedWithDuetMarker() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[00:01.00]v1:Hello\n[00:01.00]v2:world");
        assertEquals(2, lines.size());
        assertEquals("Hello", lines.get(0).text());
        assertEquals("world", lines.get(1).text());
    }

    @Test
    public void parseSyncedWithSongPart() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[00:01.00][Verse 1]Hello");
        assertEquals(1, lines.size());
        assertEquals("Verse 1", lines.get(0).songPart());
    }

    @Test
    public void parseSyncedLinesAreSortedByTime() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[00:03.00]Third\n[00:01.00]First\n[00:02.00]Second");
        assertEquals(3, lines.size());
        assertEquals("First", lines.get(0).text());
        assertEquals("Second", lines.get(1).text());
        assertEquals("Third", lines.get(2).text());
    }

    @Test
    public void parseSyncedBreakMarkersAreSkipped() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[00:01.00]Hello\n[00:02.00]♪\n[00:03.00]World");
        assertEquals(2, lines.size());
        assertEquals("Hello", lines.get(0).text());
        assertEquals("World", lines.get(1).text());
    }

    @Test
    public void parseSyncedEchoBecomesBackground() {
        List<LyricsLine> lines = LRCParser.parseSynced(
                "[00:01.00]Hello world\n[00:01.500]Hello world");
        assertEquals(2, lines.size());
        assertFalse(lines.get(0).isBG());
        assertTrue(lines.get(1).isBG());
    }

    // ── parsePlain ──

    @Test
    public void parsePlainNullOrEmptyReturnsEmpty() {
        assertTrue(LRCParser.parsePlain(null).isEmpty());
        assertTrue(LRCParser.parsePlain("").isEmpty());
    }

    @Test
    public void parsePlainBasic() {
        List<LyricsLine> lines = LRCParser.parsePlain("Hello\nWorld");
        assertEquals(2, lines.size());
        assertEquals("Hello", lines.get(0).text());
        assertEquals("World", lines.get(1).text());
        assertEquals(LyricsLine.NO_TIME, lines.get(0).startTimeMs());
    }

    @Test
    public void parsePlainSectionHeaderBecomesSongPart() {
        List<LyricsLine> lines = LRCParser.parsePlain("[Verse 1]\nHello world");
        assertEquals(1, lines.size());
        assertEquals("Hello world", lines.get(0).text());
        assertEquals("Verse 1", lines.get(0).songPart());
    }

    @Test
    public void parsePlainBracketsInsideTextStay() {
        List<LyricsLine> lines = LRCParser.parsePlain("Hello [world]");
        assertEquals(1, lines.size());
        assertEquals("Hello [world]", lines.get(0).text());
    }

    @Test
    public void parsePlainTrailingBlankLinesRemoved() {
        List<LyricsLine> lines = LRCParser.parsePlain("Hello\n\n\n");
        assertEquals(1, lines.size());
        assertEquals("Hello", lines.get(0).text());
    }

    @Test
    public void parsePlainWithSlots() {
        LRCParser.PlainLyrics result = LRCParser.parsePlainWithSlots("[Chorus]\nSing\n\nDance");
        assertNotNull(result);
        assertEquals(3, result.lines().size());
        assertEquals("Chorus", result.lines().get(0).songPart());
        assertEquals("Sing", result.lines().get(0).text());
        assertEquals("", result.lines().get(1).text());
        assertEquals("Dance", result.lines().get(2).text());
        assertEquals(-1, result.slots()[0]);
        assertEquals(0, result.slots()[1]);
        assertEquals(1, result.slots()[2]);
        assertEquals(2, result.slots()[3]);
    }

    // ── extractCreditMetadata ──

    @Test
    public void extractCreditMetadataNullOrEmpty() {
        assertTrue(LRCParser.extractCreditMetadata(null).isEmpty());
        assertTrue(LRCParser.extractCreditMetadata("").isEmpty());
    }

    @Test
    public void extractCreditMetadataWithKnownKeys() {
        List<String> credits = LRCParser.extractCreditMetadata(
                "[ti:Test Song]\n[ar:Test Artist]\n[al:Test Album]\n[au:Composer]\n[by:Uploader]");
        // Only au/by are true credit lines; ti/ar/al stay in rawFormat for TTMLGenerator.
        assertEquals(2, credits.size());
        assertTrue(credits.contains("au:Composer"));
        assertTrue(credits.contains("by:Uploader"));
    }

    @Test
    public void extractCreditMetadataSkipsOffset() {
        List<String> credits = LRCParser.extractCreditMetadata(
                "[offset:5000]\n[au:Composer]");
        assertEquals(1, credits.size());
        assertTrue(credits.contains("au:Composer"));
    }

    @Test
    public void extractCreditMetadataSkipsUnknownKeys() {
        List<String> credits = LRCParser.extractCreditMetadata(
                "[by:Uploader]\n[re:Remastered]\n[au:Composer]");
        assertEquals(2, credits.size());
        assertTrue(credits.contains("by:Uploader"));
        assertTrue(credits.contains("au:Composer"));
    }

    @Test
    public void extractCreditMetadataSkipsEmptyValues() {
        List<String> credits = LRCParser.extractCreditMetadata(
                "[au:]\n[by:]\n[au:Composer]");
        assertEquals(1, credits.size());
        assertTrue(credits.contains("au:Composer"));
    }

    @Test
    public void extractCreditMetadataCaseInsensitiveKeys() {
        List<String> credits = LRCParser.extractCreditMetadata(
                "[AU:Composer]\n[BY:Uploader]");
        assertEquals(2, credits.size());
        assertTrue(credits.contains("AU:Composer"));
        assertTrue(credits.contains("BY:Uploader"));
    }

    @Test
    public void extractCreditMetadataStdMetaKeysNotCredits() {
        // ti/ar/al are standard metadata, not credits. They should not appear in creditLines.
        List<String> credits = LRCParser.extractCreditMetadata(
                "[ti:Test Song]\n[ar:Test Artist]\n[al:Test Album]");
        assertTrue(credits.isEmpty());
    }

    // ── hasOuterParentheses ──

    @Test
    public void hasOuterParenthesesBasic() {
        assertTrue(LRCParser.hasOuterParentheses("(text)"));
        assertTrue(LRCParser.hasOuterParentheses("（text）"));
        assertTrue(LRCParser.hasOuterParentheses("[text]"));
        assertTrue(LRCParser.hasOuterParentheses("{text}"));
        assertTrue(LRCParser.hasOuterParentheses("<text>"));
    }

    @Test
    public void hasOuterParenthesesNegative() {
        assertFalse(LRCParser.hasOuterParentheses(null));
        assertFalse(LRCParser.hasOuterParentheses(""));
        assertFalse(LRCParser.hasOuterParentheses("text"));
        assertFalse(LRCParser.hasOuterParentheses("(text"));
        assertFalse(LRCParser.hasOuterParentheses("text)"));
        assertFalse(LRCParser.hasOuterParentheses("("));
    }

    // ── cleanTextForComparison ──

    @Test
    public void cleanTextForComparisonNull() {
        assertEquals("", LRCParser.cleanTextForComparison(null));
    }

    @Test
    public void cleanTextForComparisonStripsPunctuation() {
        assertEquals("hello world", LRCParser.cleanTextForComparison("Hello, World!"));
        assertEquals("hello", LRCParser.cleanTextForComparison("'Hello'"));
        assertEquals("test", LRCParser.cleanTextForComparison("Test..."));
    }

    @Test
    public void cleanTextForComparisonLowercases() {
        assertEquals("hello", LRCParser.cleanTextForComparison("HELLO"));
    }

    // ── estimateEndMs ──

    @Test
    public void estimateEndMsNextLineSoon() {
        // Gap < HELD_GAP_MS (15s), so end = next start
        long end = LRCParser.estimateEndMs(1000, 5000, "Hello");
        assertEquals(5000L, end);
    }

    @Test
    public void estimateEndMsGapJustBelowHoldThreshold() {
        // 12s gap < HELD_GAP_MS (15s), so the line lasts until the next one starts
        long end = LRCParser.estimateEndMs(0, 12000, "Hello");
        assertEquals(12000L, end);
    }

    @Test
    public void estimateEndMsLongHold() {
        // Gap >= HELD_GAP_MS, so end = start + hold (min 10000, or len*180)
        long end = LRCParser.estimateEndMs(0, 20000, "Hello");
        assertEquals(10000L, end);
    }

    @Test
    public void estimateEndMsLastLine() {
        long end = LRCParser.estimateEndMs(1000, LyricsLine.NO_TIME, "Hello");
        assertEquals(11000L, end);
    }

    // ── inferMissingWordEnds ──

    @Test
    public void inferMissingWordEndsFillsGaps() {
        List<Word> words = new ArrayList<>();
        words.add(new Word(1000, LyricsLine.NO_TIME, "Hello"));
        words.add(new Word(2000, LyricsLine.NO_TIME, "world"));
        LRCParser.inferMissingWordEnds(words, 800);
        assertEquals(2000L, words.get(0).endMs());
        assertEquals(2800L, words.get(1).endMs());
    }

    @Test
    public void inferMissingWordEndsPreservesExisting() {
        List<Word> words = new ArrayList<>();
        words.add(new Word(1000, 1500, "Hello"));
        words.add(new Word(2000, LyricsLine.NO_TIME, "world"));
        LRCParser.inferMissingWordEnds(words, 800);
        assertEquals(1500L, words.get(0).endMs());
        assertEquals(2800L, words.get(1).endMs());
    }

    @Test
    public void inferMissingWordEndsEmptyList() {
        List<Word> words = new ArrayList<>();
        LRCParser.inferMissingWordEnds(words, 800);
        assertTrue(words.isEmpty());
    }

    // ── markEchoesAsBackground ──

    @Test
    public void markEchoesAsBackgroundRepeatedLine() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsLine(1000, 2000, "Hello world", List.of()));
        lines.add(new LyricsLine(2000, 3000, "Hello world", List.of()));
        LRCParser.markEchoesAsBackground(lines);
        assertFalse(lines.get(0).isBG());
        assertTrue(lines.get(1).isBG());
    }

    @Test
    public void markEchoesAsBackgroundDifferentLine() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsLine(1000, 2000, "Hello", List.of()));
        lines.add(new LyricsLine(2000, 3000, "Different", List.of()));
        LRCParser.markEchoesAsBackground(lines);
        assertFalse(lines.get(0).isBG());
        assertFalse(lines.get(1).isBG());
    }

    // ── fillEstimatedEnds ──

    @Test
    public void fillEstimatedEndsGivesEachLineAnEnd() {
        List<LyricsLine> lines = new ArrayList<>();
        lines.add(new LyricsLine(0, LyricsLine.NO_TIME, "Hello", List.of()));
        lines.add(new LyricsLine(5000, LyricsLine.NO_TIME, "World", List.of()));
        LRCParser.fillEstimatedEnds(lines);
        assertEquals(5000L, lines.get(0).endTimeMs());
        assertEquals(5000L + 10000L, lines.get(1).endTimeMs());
    }

    // ── formatCentiseconds ──

    @Test
    public void formatCentisecondsBasic() {
        assertEquals("00:01.00", LRCParser.formatCentiseconds(1000));
        assertEquals("00:00.00", LRCParser.formatCentiseconds(0));
        assertEquals("01:02.03", LRCParser.formatCentiseconds(62030));
    }

    // ── parseSyncedWithCreditLines ──

    @Test
    public void parseSyncedWithCreditLines() {
        LRCParser.LrcParseResult result = LRCParser.parseSyncedWithCreditLines(
                "[ti:Test Song]\n[au:Composer]\n[00:01.00]Hello");
        assertNotNull(result);
        assertEquals(1, result.lines.size());
        // Only au is a true credit line; ti stays in rawFormat for TTMLGenerator.
        assertEquals(1, result.creditLines.size());
        assertTrue(result.creditLines.contains("au:Composer"));
    }
}
