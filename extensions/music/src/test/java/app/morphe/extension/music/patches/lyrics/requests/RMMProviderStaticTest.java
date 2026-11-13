/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 Section 7 terms that apply to this code.
 */

package app.morphe.extension.music.patches.lyrics.requests;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

import app.morphe.extension.music.patches.lyrics.model.LyricsLine;

/** Unit tests for the static helpers of {@link RMMProvider}: lrcFormat and usableUrl. */
public class RMMProviderStaticTest {

    // ── lrcFormat ──

    @Test
    public void lrcFormatSingleLine() {
        List<LyricsLine> lines = List.of(new LyricsLine(1000, 2000, "Hello", List.of()));
        String result = RMMProvider.lrcFormat(lines);
        assertTrue(result.contains("Hello"));
        assertTrue(result.endsWith("\n"));
    }

    @Test
    public void lrcFormatMultipleLines() {
        List<LyricsLine> lines = List.of(
                new LyricsLine(1000, 2000, "First", List.of()),
                new LyricsLine(2000, 3000, "Second", List.of()));
        String result = RMMProvider.lrcFormat(lines);
        assertTrue(result.contains("First"));
        assertTrue(result.contains("Second"));
        // Each line ends with a newline, so 2 lines = 2 newlines
        assertEquals(2, countChar(result, '\n'));
    }

    private static int countChar(String s, char c) {
        int count = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) count++;
        }
        return count;
    }

    @Test
    public void lrcFormatEmptyList() {
        String result = RMMProvider.lrcFormat(List.of());
        assertEquals("", result);
    }

    // ── usableUrl ──

    @Test
    public void usableUrlNullReturnsNull() {
        assertNull(RMMProvider.usableUrl(null));
    }

    @Test
    public void usableUrlEmptyReturnsNull() {
        assertNull(RMMProvider.usableUrl(""));
    }

    @Test
    public void usableUrlWhitespaceReturnsNull() {
        assertNull(RMMProvider.usableUrl("   "));
    }

    @Test
    public void usableUrlProtocolRelativeAddsHttps() {
        assertEquals("https://example.com", RMMProvider.usableUrl("//example.com"));
    }

    @Test
    public void usableUrlNoSchemeAddsHttps() {
        assertEquals("https://example.com", RMMProvider.usableUrl("example.com"));
    }

    @Test
    public void usableUrlWithSchemeKeeps() {
        assertEquals("https://example.com", RMMProvider.usableUrl("https://example.com"));
    }

    @Test
    public void usableUrlTrimsWhitespace() {
        assertEquals("https://example.com", RMMProvider.usableUrl("  example.com  "));
    }

    @Test
    public void usableUrlHttpKeeps() {
        assertEquals("http://example.com", RMMProvider.usableUrl("http://example.com"));
    }
}
