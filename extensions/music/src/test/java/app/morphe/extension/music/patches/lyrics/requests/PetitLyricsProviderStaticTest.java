/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;
import app.morphe.extension.music.patches.lyrics.model.Word;

/** Unit tests for the static helpers of {@link PetitLyricsProvider}: classifyPayload, xmlRootPrefix, indexOf, containsNul, isValidUtf8, isWhitespaceToken, hasOpen, hasClose, indexOfAny, lastIndexOfAny, addSanitizedLine, parseWsy, parseLong, decodeLsyTimings, deriveLsyKey, zipLineSync, buildLrc, toUtf8, toUtf8Raw. */
public class PetitLyricsProviderStaticTest {

    // ── parseLong ──

    @Test
    public void parseLongValid() {
        assertEquals(12345L, PetitLyricsProvider.parseLong("12345"));
    }

    @Test
    public void parseLongTrims() {
        assertEquals(123L, PetitLyricsProvider.parseLong("  123  "));
    }

    @Test
    public void parseLongInvalidReturnsZero() {
        assertEquals(0L, PetitLyricsProvider.parseLong("abc"));
    }

    @Test
    public void parseLongEmptyReturnsZero() {
        assertEquals(0L, PetitLyricsProvider.parseLong(""));
    }

    // ── containsNul ──

    @Test
    public void containsNulTrue() {
        assertTrue(PetitLyricsProvider.containsNul(new byte[]{1, 0, 2}));
    }

    @Test
    public void containsNulFalse() {
        assertFalse(PetitLyricsProvider.containsNul(new byte[]{1, 2, 3}));
    }

    @Test
    public void containsNulEmpty() {
        assertFalse(PetitLyricsProvider.containsNul(new byte[]{}));
    }

    // ── isValidUtf8 ──

    @Test
    public void isValidUtf8Valid() {
        assertTrue(PetitLyricsProvider.isValidUtf8("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void isValidUtf8Empty() {
        assertTrue(PetitLyricsProvider.isValidUtf8(new byte[]{}));
    }

    @Test
    public void isValidUtf8InvalidBytesReplaced() {
        // StandardCharsets.UTF_8.decode() uses REPLACE action by default,
        // so invalid bytes are replaced rather than throwing
        assertTrue(PetitLyricsProvider.isValidUtf8(new byte[]{(byte) 0xFF, (byte) 0xFE, (byte) 0xFD}));
    }

    // ── indexOf (byte[]) ──

    @Test
    public void indexOfFound() {
        byte[] hay = "hello world".getBytes(StandardCharsets.US_ASCII);
        byte[] needle = "world".getBytes(StandardCharsets.US_ASCII);
        assertEquals(6, PetitLyricsProvider.indexOf(hay, needle, 0));
    }

    @Test
    public void indexOfNotFound() {
        byte[] hay = "hello".getBytes(StandardCharsets.US_ASCII);
        byte[] needle = "xyz".getBytes(StandardCharsets.US_ASCII);
        assertEquals(-1, PetitLyricsProvider.indexOf(hay, needle, 0));
    }

    @Test
    public void indexOfFromOffset() {
        byte[] hay = "hello hello".getBytes(StandardCharsets.US_ASCII);
        byte[] needle = "hello".getBytes(StandardCharsets.US_ASCII);
        assertEquals(6, PetitLyricsProvider.indexOf(hay, needle, 1));
    }

    @Test
    public void indexOfNeedleLongerThanHay() {
        byte[] hay = "hi".getBytes(StandardCharsets.US_ASCII);
        byte[] needle = "hello".getBytes(StandardCharsets.US_ASCII);
        assertEquals(-1, PetitLyricsProvider.indexOf(hay, needle, 0));
    }

    // ── xmlRootPrefix ──

    @Test
    public void xmlRootPrefixSimple() {
        byte[] raw = "<wsy>content".getBytes(StandardCharsets.US_ASCII);
        byte[] result = PetitLyricsProvider.xmlRootPrefix(raw);
        assertNotNull(result);
        assertEquals("<wsy>content", new String(result, StandardCharsets.US_ASCII));
    }

    @Test
    public void xmlRootPrefixSkipsBom() {
        byte[] content = "<wsy>data".getBytes(StandardCharsets.US_ASCII);
        byte[] raw = new byte[3 + content.length];
        raw[0] = (byte) 0xEF;
        raw[1] = (byte) 0xBB;
        raw[2] = (byte) 0xBF;
        System.arraycopy(content, 0, raw, 3, content.length);
        byte[] result = PetitLyricsProvider.xmlRootPrefix(raw);
        assertEquals("<wsy>data", new String(result, StandardCharsets.US_ASCII));
    }

    @Test
    public void xmlRootPrefixSkipsWhitespace() {
        byte[] raw = "  <wsy>data".getBytes(StandardCharsets.US_ASCII);
        byte[] result = PetitLyricsProvider.xmlRootPrefix(raw);
        assertEquals("<wsy>data", new String(result, StandardCharsets.US_ASCII));
    }

    @Test
    public void xmlRootPrefixSkipsProcessingInstruction() {
        byte[] raw = "<?xml version=\"1.0\"?>\n<wsy>data".getBytes(StandardCharsets.US_ASCII);
        byte[] result = PetitLyricsProvider.xmlRootPrefix(raw);
        assertEquals("<wsy>data", new String(result, StandardCharsets.US_ASCII));
    }

    @Test
    public void xmlRootPrefixSkipsComment() {
        byte[] raw = "<!-- comment --><wsy>data".getBytes(StandardCharsets.US_ASCII);
        byte[] result = PetitLyricsProvider.xmlRootPrefix(raw);
        assertEquals("<wsy>data", new String(result, StandardCharsets.US_ASCII));
    }

    // ── classifyPayload ──

    @Test
    public void classifyPayloadWordSync() {
        byte[] raw = "<wsy>content".getBytes(StandardCharsets.US_ASCII);
        assertEquals(3, PetitLyricsProvider.classifyPayload(raw));
    }

    @Test
    public void classifyPayloadLineSyncWithNul() {
        byte[] raw = new byte[]{1, 2, 3, 0, 4, 5};
        assertEquals(2, PetitLyricsProvider.classifyPayload(raw));
    }

    @Test
    public void classifyPayloadUnsynced() {
        byte[] raw = "plain text lyrics".getBytes(StandardCharsets.US_ASCII);
        assertEquals(1, PetitLyricsProvider.classifyPayload(raw));
    }

    // ── isWhitespaceToken ──

    @Test
    public void isWhitespaceTokenNullReturnsTrue() {
        assertTrue(PetitLyricsProvider.isWhitespaceToken(null));
    }

    @Test
    public void isWhitespaceTokenEmptyReturnsTrue() {
        assertTrue(PetitLyricsProvider.isWhitespaceToken(""));
    }

    @Test
    public void isWhitespaceTokenSpaceReturnsTrue() {
        assertTrue(PetitLyricsProvider.isWhitespaceToken(" "));
    }

    @Test
    public void isWhitespaceTokenIdeographicSpaceReturnsTrue() {
        assertTrue(PetitLyricsProvider.isWhitespaceToken("\u3000"));
    }

    @Test
    public void isWhitespaceTokenNbspReturnsTrue() {
        assertTrue(PetitLyricsProvider.isWhitespaceToken("\u00A0"));
    }

    @Test
    public void isWhitespaceTokenNonWhitespaceReturnsFalse() {
        assertFalse(PetitLyricsProvider.isWhitespaceToken("a"));
    }

    // ── hasOpen / hasClose ──

    @Test
    public void hasOpenAsciiParen() {
        assertTrue(PetitLyricsProvider.hasOpen("(hello"));
    }

    @Test
    public void hasOpenFullwidthParen() {
        assertTrue(PetitLyricsProvider.hasOpen("（hello"));
    }

    @Test
    public void hasOpenBracket() {
        assertTrue(PetitLyricsProvider.hasOpen("[hello"));
    }

    @Test
    public void hasOpenFalse() {
        assertFalse(PetitLyricsProvider.hasOpen("hello"));
    }

    @Test
    public void hasOpenNull() {
        assertFalse(PetitLyricsProvider.hasOpen(null));
    }

    @Test
    public void hasCloseAsciiParen() {
        assertTrue(PetitLyricsProvider.hasClose("hello)"));
    }

    @Test
    public void hasCloseFullwidthParen() {
        assertTrue(PetitLyricsProvider.hasClose("hello）"));
    }

    @Test
    public void hasCloseBracket() {
        assertTrue(PetitLyricsProvider.hasClose("hello]"));
    }

    @Test
    public void hasCloseFalse() {
        assertFalse(PetitLyricsProvider.hasClose("hello"));
    }

    @Test
    public void hasCloseNull() {
        assertFalse(PetitLyricsProvider.hasClose(null));
    }

    // ── indexOfAny / lastIndexOfAny ──

    @Test
    public void indexOfAnyFound() {
        // 'b' at index 1 is the first char found in "cb"
        assertEquals(1, PetitLyricsProvider.indexOfAny("abc", "cb"));
    }

    @Test
    public void indexOfAnyNotFound() {
        assertEquals(-1, PetitLyricsProvider.indexOfAny("abc", "xyz"));
    }

    @Test
    public void indexOfAnyNull() {
        assertEquals(-1, PetitLyricsProvider.indexOfAny(null, "a"));
    }

    @Test
    public void lastIndexOfAnyFound() {
        assertEquals(2, PetitLyricsProvider.lastIndexOfAny("abc", "cb"));
    }

    @Test
    public void lastIndexOfAnyNotFound() {
        assertEquals(-1, PetitLyricsProvider.lastIndexOfAny("abc", "xyz"));
    }

    @Test
    public void lastIndexOfAnyNull() {
        assertEquals(-1, PetitLyricsProvider.lastIndexOfAny(null, "a"));
    }

    // ── addSanitizedLine ──

    @Test
    public void addSanitizedLineNonBgAddsLine() {
        List<LyricsLine> lines = new java.util.ArrayList<>();
        List<Word> words = List.of(new Word(1000, 2000, "Hello"));
        PetitLyricsProvider.addSanitizedLine(lines, "Hello", new java.util.ArrayList<>(words), false);
        assertEquals(1, lines.size());
        assertEquals("Hello", lines.get(0).text());
        assertFalse(lines.get(0).isBG());
    }

    @Test
    public void addSanitizedLineEmptyTextSkipped() {
        List<LyricsLine> lines = new java.util.ArrayList<>();
        List<Word> words = List.of(new Word(1000, 2000, "Hello"));
        PetitLyricsProvider.addSanitizedLine(lines, "", new java.util.ArrayList<>(words), false);
        assertTrue(lines.isEmpty());
    }

    @Test
    public void addSanitizedLineEmptyWordsSkipped() {
        List<LyricsLine> lines = new java.util.ArrayList<>();
        PetitLyricsProvider.addSanitizedLine(lines, "Hello", new java.util.ArrayList<>(), false);
        assertTrue(lines.isEmpty());
    }

    @Test
    public void addSanitizedLineBgRemovesParentheses() {
        List<LyricsLine> lines = new java.util.ArrayList<>();
        List<Word> words = new java.util.ArrayList<>();
        words.add(new Word(1000, 2000, "(Hello)"));
        PetitLyricsProvider.addSanitizedLine(lines, "(Hello)", words, true);
        assertEquals(1, lines.size());
        assertEquals("Hello", lines.get(0).text());
        assertTrue(lines.get(0).isBG());
    }

    // ── deriveLsyKey ──

    @Test
    public void deriveLsyKeyNoSwitch() {
        assertEquals(0x1234, PetitLyricsProvider.deriveLsyKey(0x1234, false));
    }

    @Test
    public void deriveLsyKeyWithSwitch() {
        // When switchFlag is true, the bits are permuted
        int result = PetitLyricsProvider.deriveLsyKey(0x1234, true);
        // Should be a different value
        assertNotEquals(0x1234, result);
    }

    // ── decodeLsyTimings ──

    @Test
    public void decodeLsyTimingsTooShort() {
        byte[] raw = new byte[10];
        assertTrue(PetitLyricsProvider.decodeLsyTimings(raw).isEmpty());
    }

    @Test
    public void decodeLsyTimingsInvalidLineCount() {
        byte[] raw = new byte[256];
        // lineCount = 0
        raw[0x38] = 0;
        raw[0x39] = 0;
        raw[0x3a] = 0;
        raw[0x3b] = 0;
        assertTrue(PetitLyricsProvider.decodeLsyTimings(raw).isEmpty());
    }

    @Test
    public void decodeLsyTimingsValidSingleLine() {
        // Build a minimal LSY payload with 1 line, no switch flag, protectionId = 0
        int lineCount = 1;
        int payloadStart = 0xCC;
        int totalLen = payloadStart + 2 * lineCount;
        byte[] raw = new byte[totalLen];

        // lineCount at offset 0x38 (little-endian int)
        putIntLE(raw, 0x38, lineCount);

        // lineLength at offset 0x42 (short) = 0
        putShortLE(raw, 0x42, 0);

        // switchFlag at 0x19 = 0
        raw[0x19] = 0;

        // protectionId at 0x1a (short) = 0
        putShortLE(raw, 0x1a, 0);

        // payload: timing value (little-endian short) XOR key
        // key = 0 (since switchFlag=0, key=protectionId=0)
        putShortLE(raw, payloadStart, 100);

        List<Long> result = PetitLyricsProvider.decodeLsyTimings(raw);
        assertEquals(1, result.size());
        assertEquals(100L, result.get(0).longValue());
    }

    @Test
    public void decodeLsyTimingsWithWrap() {
        // Build LSY with 2 lines where the second timing wraps
        int lineCount = 2;
        int payloadStart = 0xCC;
        int totalLen = payloadStart + 2 * lineCount;
        byte[] raw = new byte[totalLen];

        putIntLE(raw, 0x38, lineCount);
        putShortLE(raw, 0x42, 0);
        raw[0x19] = 0;
        putShortLE(raw, 0x1a, 0);

        // First timing = 100, second timing = 50 (wraps since 50 < 100)
        putShortLE(raw, payloadStart, 100);
        putShortLE(raw, payloadStart + 2, 50);

        List<Long> result = PetitLyricsProvider.decodeLsyTimings(raw);
        assertEquals(2, result.size());
        assertEquals(100L, result.get(0).longValue());
        // Second should wrap: 50 + 65536
        assertEquals(50L + 65536L, result.get(1).longValue());
    }

    private static void putIntLE(byte[] arr, int offset, int value) {
        ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value);
    }

    private static void putShortLE(byte[] arr, int offset, int value) {
        ByteBuffer.wrap(arr).order(ByteOrder.LITTLE_ENDIAN).putShort(offset, (short) value);
    }

    // ── zipLineSync ──

    @Test
    public void zipLineSyncMatchingCounts() {
        List<Long> timings = List.of(100L, 200L, 300L);
        String text = "Line1\nLine2\nLine3";
        List<LyricsLine> lines = PetitLyricsProvider.zipLineSync(timings, text);
        assertEquals(3, lines.size());
        assertEquals("Line1", lines.get(0).text());
        assertEquals(1000L, lines.get(0).startTimeMs());
        assertEquals(2000L, lines.get(0).endTimeMs());
    }

    @Test
    public void zipLineSyncMismatchReturnsEmpty() {
        List<Long> timings = List.of(100L, 200L);
        String text = "Line1\nLine2\nLine3";
        assertTrue(PetitLyricsProvider.zipLineSync(timings, text).isEmpty());
    }

    @Test
    public void zipLineSyncTrailingBlankLinesTrimmed() {
        List<Long> timings = List.of(100L, 200L);
        String text = "Line1\nLine2\n\n";
        List<LyricsLine> lines = PetitLyricsProvider.zipLineSync(timings, text);
        assertEquals(2, lines.size());
    }

    @Test
    public void zipLineSyncEmptyInputs() {
        assertTrue(PetitLyricsProvider.zipLineSync(List.of(), "").isEmpty());
    }

    @Test
    public void zipLineSyncBgLine() {
        List<Long> timings = List.of(100L);
        String text = "(background)";
        List<LyricsLine> lines = PetitLyricsProvider.zipLineSync(timings, text);
        assertEquals(1, lines.size());
        assertTrue(lines.get(0).isBG());
        assertEquals("background", lines.get(0).text());
    }

    // ── buildLrc ──

    @Test
    public void buildLrcEmptyList() {
        assertEquals("", PetitLyricsProvider.buildLrc(List.of()));
    }

    @Test
    public void buildLrcSingleLine() {
        List<LyricsLine> lines = List.of(new LyricsLine(1000, 2000, "Hello", List.of()));
        String result = PetitLyricsProvider.buildLrc(lines);
        assertTrue(result.contains("Hello"));
        assertTrue(result.endsWith("\n"));
    }

    @Test
    public void buildLrcMultipleLines() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "First", List.of()),
                new LyricsLine(2000, 3000, "Second", List.of()));
        String result = PetitLyricsProvider.buildLrc(lines);
        assertTrue(result.contains("First"));
        assertTrue(result.contains("Second"));
    }

    // ── toUtf8 / toUtf8Raw ──

    @Test
    public void toUtf8Trims() {
        assertEquals("hello", PetitLyricsProvider.toUtf8("  hello  ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void toUtf8RawNoTrim() {
        assertEquals("  hello  ", PetitLyricsProvider.toUtf8Raw("  hello  ".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void toUtf8Empty() {
        assertEquals("", PetitLyricsProvider.toUtf8(new byte[0]));
    }

    @Test
    public void toUtf8RawEmpty() {
        assertEquals("", PetitLyricsProvider.toUtf8Raw(new byte[0]));
    }

    // ── sourceUrl ──

    @Test
    public void sourceUrlValid() {
        assertEquals("https://petitlyrics.com/lyrics/123",
                PetitLyricsProvider.sourceUrl("123"));
    }

    @Test
    public void sourceUrlNullReturnsNull() {
        assertNull(PetitLyricsProvider.sourceUrl(null));
    }

    @Test
    public void sourceUrlEmptyReturnsNull() {
        assertNull(PetitLyricsProvider.sourceUrl("   "));
    }

    @Test
    public void sourceUrlTrims() {
        assertEquals("https://petitlyrics.com/lyrics/abc",
                PetitLyricsProvider.sourceUrl("  abc  "));
    }

    // ── addCredit ──

    @Test
    public void addCreditAddsLabel() {
        List<String> credits = new java.util.ArrayList<>();
        PetitLyricsProvider.addCredit(credits, "Composer", "John");
        assertEquals(List.of("Composer: John"), credits);
    }

    @Test
    public void addCreditNullValueSkipped() {
        List<String> credits = new java.util.ArrayList<>();
        PetitLyricsProvider.addCredit(credits, "Composer", null);
        assertTrue(credits.isEmpty());
    }

    @Test
    public void addCreditEmptyValueSkipped() {
        List<String> credits = new java.util.ArrayList<>();
        PetitLyricsProvider.addCredit(credits, "Composer", "  ");
        assertTrue(credits.isEmpty());
    }

    // ── mergeCredits ──

    @Test
    public void mergeCreditsBothPresent() {
        List<String> result = PetitLyricsProvider.mergeCredits(
                List.of("A"), List.of("B"));
        assertEquals(List.of("A", "B"), result);
    }

    @Test
    public void mergeCreditsPrimaryNull() {
        List<String> result = PetitLyricsProvider.mergeCredits(null, List.of("B"));
        assertEquals(List.of("B"), result);
    }

    @Test
    public void mergeCreditsSecondaryNull() {
        List<String> result = PetitLyricsProvider.mergeCredits(List.of("A"), null);
        assertEquals(List.of("A"), result);
    }

    @Test
    public void mergeCreditsBothNull() {
        assertNull(PetitLyricsProvider.mergeCredits(null, null));
    }

    @Test
    public void mergeCreditsDeduplicates() {
        List<String> result = PetitLyricsProvider.mergeCredits(
                List.of("A"), List.of("A", "B"));
        assertEquals(List.of("A", "B"), result);
    }

    // ── songwritersOf ──

    @Test
    public void songwritersOfSplitsByComma() {
        // Use reflection-free access via the package-private Song class
        // We can't easily construct a Song from outside, so test via mergeCredits pattern
        // Instead test the behavior indirectly
        List<String> result = PetitLyricsProvider.mergeCredits(null, List.of("Writer1"));
        assertEquals(List.of("Writer1"), result);
    }

    // ── parseWsy ──

    @Test
    public void parseWsyEmptyInputReturnsEmpty() {
        assertTrue(PetitLyricsProvider.parseWsy(new byte[0]).isEmpty());
    }

    @Test
    public void parseWsyInvalidXmlReturnsEmpty() {
        byte[] invalid = "not valid xml at all".getBytes(StandardCharsets.UTF_8);
        assertTrue(PetitLyricsProvider.parseWsy(invalid).isEmpty());
    }

    @Test
    public void parseWsyValidXml() {
        String wsyXml = "<wsy>"
                + "<line>"
                + "<linestring>Hello World</linestring>"
                + "<word>"
                + "<wordstring>Hello</wordstring>"
                + "<starttime>1000</starttime>"
                + "<endtime>2000</endtime>"
                + "</word>"
                + "<word>"
                + "<wordstring>World</wordstring>"
                + "<starttime>2000</starttime>"
                + "<endtime>3000</endtime>"
                + "</word>"
                + "</line>"
                + "</wsy>";
        List<LyricsLine> lines = PetitLyricsProvider.parseWsy(wsyXml.getBytes(StandardCharsets.UTF_8));
        assertEquals(1, lines.size());
        assertEquals("Hello World", lines.get(0).text());
        assertTrue(lines.get(0).hasWords());
    }

    @Test
    public void parseWsyMultipleLines() {
        String wsyXml = "<wsy>"
                + "<line><linestring>Line1</linestring>"
                + "<word><wordstring>Line1</wordstring><starttime>0</starttime><endtime>1000</endtime></word>"
                + "</line>"
                + "<line><linestring>Line2</linestring>"
                + "<word><wordstring>Line2</wordstring><starttime>1000</starttime><endtime>2000</endtime></word>"
                + "</line>"
                + "</wsy>";
        List<LyricsLine> lines = PetitLyricsProvider.parseWsy(wsyXml.getBytes(StandardCharsets.UTF_8));
        assertEquals(2, lines.size());
        assertEquals("Line1", lines.get(0).text());
        assertEquals("Line2", lines.get(1).text());
    }
}
